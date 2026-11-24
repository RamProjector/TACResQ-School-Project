<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Database\QueryException;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Route;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class LocalSanctumAccountLifecycleTest extends TestCase
{
    use RefreshDatabase;

    private string $adminTotpCode;

    public function test_ci_can_require_the_lifecycle_suite_to_use_postgresql(): void
    {
        if (getenv('TACRESQ_REQUIRE_POSTGRES_LIFECYCLE') === '1') {
            $this->assertSame(
                'pgsql',
                DB::connection()->getDriverName(),
                'The required lifecycle audit CI run must execute against PostgreSQL, not silently skip PostgreSQL-only guards.',
            );
        }
    }

    private function createLocalUser(): User
    {
        return User::create([
            'name' => 'Lifecycle Test User',
            'email' => 'lifecycle-test@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);
    }


    private function registerLocalAdminReauthenticationTestRoute(): void
    {
        $secret = app(\App\Services\LocalAdminTotpService::class)->generateSecret();
        DB::table('local_admin_totp_credentials')->insert([
            'user_id' => auth('sanctum')->id(),
            'secret_ciphertext' => app(\App\Services\LocalAdminTotpService::class)->encryptSecret($secret),
            'confirmed_at' => now(),
            'expires_at' => null,
            'last_used_step' => null,
            'last_used_code_hash' => null,
            'created_at' => now(),
            'updated_at' => now(),
        ]);
        $step = intdiv(now()->timestamp, 30);
        $this->adminTotpCode = app(\App\Services\LocalAdminTotpService::class)->codeForStep($secret, $step);

        Route::post('/_test/local-admin-sensitive-action', fn () => response()->json(['ok' => true]))
            ->middleware([
                'auth:sanctum',
                'account.active',
                'local.admin.reauthenticated',
            ]);
    }

    public function test_existing_local_accounts_remain_active_by_default(): void
    {
        $user = $this->createLocalUser();
        Sanctum::actingAs($user);

        $this->getJson('/api/me')->assertOk()->assertJsonPath('id', $user->id);
        $this->assertFalse($user->fresh()->is_suspended);
    }

    public function test_suspended_local_account_cannot_access_protected_sanctum_routes(): void
    {
        $user = $this->createLocalUser();
        DB::table('users')->where('id', $user->id)->update([
            'is_suspended' => true,
            'suspended_at' => now(),
            'suspension_reason' => 'Synthetic test suspension',
        ]);
        Sanctum::actingAs($user->fresh());

        $this->getJson('/api/me')->assertForbidden()
            ->assertExactJson([
                'message' => 'This local TACResQ account is suspended. Contact an administrator for assistance.',
            ])
            ->assertHeader('Cache-Control', 'no-store, private');
    }

    public function test_existing_sanctum_token_is_denied_across_protected_incident_and_account_routes_after_suspension(): void
    {
        $user = $this->createLocalUser();
        $plainTextToken = $user->createToken('lifecycle-regression')->plainTextToken;

        // Suspend after token issuance to prove that an already-issued credential
        // is checked against current account state on every protected request.
        DB::table('users')->where('id', $user->id)->update([
            'is_suspended' => true,
            'suspended_at' => now(),
            'suspension_reason' => 'Synthetic same-token regression',
        ]);

        $requests = [
            ['GET', '/api/me'],
            ['GET', '/api/incidents/mine'],
            ['GET', '/api/incidents'],
            ['GET', '/api/units'],
            ['POST', '/api/incidents'],
            ['PATCH', '/api/me/duty-status'],
            ['POST', '/api/change-password'],
        ];

        foreach ($requests as [$method, $uri]) {
            $response = $this->withToken($plainTextToken)->json($method, $uri);
            $response->assertForbidden()
                ->assertExactJson([
                    'message' => 'This local TACResQ account is suspended. Contact an administrator for assistance.',
                ])
                ->assertHeader('Cache-Control', 'no-store, private');
        }

        // Denial must not depend on revoking the token as a side effect.
        $this->assertSame(1, $user->tokens()->count());
    }

    public function test_suspended_local_account_cannot_obtain_a_new_sanctum_token(): void
    {
        $user = $this->createLocalUser();
        DB::table('users')->where('id', $user->id)->update(['is_suspended' => true]);

        $this->postJson('/api/login', [
            'email' => $user->email,
            'password' => 'synthetic-test-password',
        ])->assertForbidden()
            ->assertExactJson([
                'message' => 'This local TACResQ account is suspended. Contact an administrator for assistance.',
            ])
            ->assertHeader('Cache-Control', 'no-store, private');

        $this->assertSame(0, $user->tokens()->count());
    }

    public function test_local_lifecycle_audit_schema_retains_original_actor_and_target_ids_after_user_deletion(): void
    {
        $actor = $this->createLocalUser();
        $target = User::create([
            'name' => 'Lifecycle Target',
            'email' => 'lifecycle-target@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);

        $eventId = DB::table('local_account_lifecycle_events')->insertGetId([
            'actor_user_id' => $actor->id,
            'target_user_id' => $target->id,
            'action' => 'suspended',
            'reason' => 'Synthetic retention test',
            'previous_state' => 'active',
            'new_state' => 'suspended',
            'created_at' => now(),
        ]);

        $actor->delete();
        $target->delete();

        $this->assertDatabaseHas('local_account_lifecycle_events', [
            'id' => $eventId,
            'actor_user_id' => $actor->id,
            'target_user_id' => $target->id,
            'action' => 'suspended',
            'reason' => 'Synthetic retention test',
        ]);
    }

    public function test_local_lifecycle_audit_event_rejects_actor_or_target_reference_mutation_on_postgres(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('The append-only trigger is PostgreSQL-specific.');
        }

        $actor = $this->createLocalUser();
        $target = User::create([
            'name' => 'Lifecycle Reference Target',
            'email' => 'lifecycle-reference-target@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);
        $eventId = DB::table('local_account_lifecycle_events')->insertGetId([
            'actor_user_id' => $actor->id,
            'target_user_id' => $target->id,
            'action' => 'suspended',
            'reason' => 'Immutable references',
            'previous_state' => 'active',
            'new_state' => 'suspended',
            'created_at' => now(),
        ]);

        foreach ([
            ['actor_user_id' => null],
            ['target_user_id' => null],
            ['actor_user_id' => null, 'reason' => 'Tampered during reference cleanup'],
        ] as $changes) {
            try {
                DB::transaction(fn () => DB::table('local_account_lifecycle_events')->where('id', $eventId)->update($changes));
                $this->fail('PostgreSQL must reject every lifecycle-event reference mutation.');
            } catch (QueryException $exception) {
                $this->assertSame('55000', (string) ($exception->errorInfo[0] ?? $exception->getCode()));
            $this->assertStringContainsString('append-only', $exception->getMessage());
            }
        }

        $this->assertDatabaseHas('local_account_lifecycle_events', [
            'id' => $eventId,
            'actor_user_id' => $actor->id,
            'target_user_id' => $target->id,
            'reason' => 'Immutable references',
        ]);
    }

    public function test_local_lifecycle_audit_event_rejects_content_updates_on_postgres(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('The append-only trigger is PostgreSQL-specific.');
        }

        $eventId = DB::table('local_account_lifecycle_events')->insertGetId([
            'actor_user_id' => 10,
            'target_user_id' => 20,
            'action' => 'suspended',
            'reason' => 'Original reason',
            'previous_state' => 'active',
            'new_state' => 'suspended',
            'created_at' => now(),
        ]);

        try {
            DB::transaction(fn () => DB::table('local_account_lifecycle_events')->where('id', $eventId)
                ->update(['reason' => 'Tampered reason']));
            $this->fail('PostgreSQL must reject lifecycle-event content updates.');
        } catch (QueryException $exception) {
            $this->assertSame('55000', (string) ($exception->errorInfo[0] ?? $exception->getCode()));
            $this->assertStringContainsString('append-only', $exception->getMessage());
        }

        $this->assertDatabaseHas('local_account_lifecycle_events', [
            'id' => $eventId,
            'reason' => 'Original reason',
        ]);
    }

    public function test_postgres_identity_migration_fails_closed_when_legacy_events_are_unattributed(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('The lifecycle identity migration is PostgreSQL-specific.');
        }

        DB::statement('ALTER TABLE local_account_lifecycle_events ALTER COLUMN actor_user_id DROP NOT NULL');
        DB::statement('ALTER TABLE local_account_lifecycle_events ALTER COLUMN target_user_id DROP NOT NULL');
        DB::table('local_account_lifecycle_events')->insert([
            [
                'actor_user_id' => null,
                'target_user_id' => 20,
                'action' => 'suspended',
                'reason' => 'Legacy event missing actor',
                'previous_state' => 'active',
                'new_state' => 'suspended',
                'created_at' => now(),
            ],
            [
                'actor_user_id' => 10,
                'target_user_id' => null,
                'action' => 'suspended',
                'reason' => 'Legacy event missing target',
                'previous_state' => 'active',
                'new_state' => 'suspended',
                'created_at' => now(),
            ],
        ]);

        $migration = require database_path('migrations/2026_11_23_000012_require_local_lifecycle_audit_identities.php');

        try {
            $migration->up();
            $this->fail('The identity migration must refuse to invent or accept a missing actor.');
        } catch (\RuntimeException $exception) {
            $this->assertStringContainsString('existing events have missing actor or target IDs', $exception->getMessage());
        }

        $this->assertSame(1, DB::table('local_account_lifecycle_events')->whereNull('actor_user_id')->count());
        $this->assertSame(1, DB::table('local_account_lifecycle_events')->whereNull('target_user_id')->count());
        $column = DB::selectOne(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
            ['local_account_lifecycle_events', 'actor_user_id'],
        );
        $this->assertSame('YES', $column->is_nullable, 'A failed preflight must not partially enforce NOT NULL.');
    }

    public function test_postgres_identity_migration_enforces_and_rolls_back_both_required_columns(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('The lifecycle identity migration is PostgreSQL-specific.');
        }

        DB::statement('ALTER TABLE local_account_lifecycle_events ALTER COLUMN actor_user_id DROP NOT NULL');
        DB::statement('ALTER TABLE local_account_lifecycle_events ALTER COLUMN target_user_id DROP NOT NULL');

        $migration = require database_path('migrations/2026_11_23_000012_require_local_lifecycle_audit_identities.php');
        $migration->up();

        $columns = DB::select(
            "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND column_name IN (?, ?) ORDER BY column_name",
            ['local_account_lifecycle_events', 'actor_user_id', 'target_user_id'],
        );
        $this->assertCount(2, $columns);
        foreach ($columns as $column) {
            $this->assertSame('NO', $column->is_nullable, $column->column_name.' must be NOT NULL after migration.');
        }

        $migration->down();

        $rolledBack = DB::select(
            "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND column_name IN (?, ?) ORDER BY column_name",
            ['local_account_lifecycle_events', 'actor_user_id', 'target_user_id'],
        );
        $this->assertCount(2, $rolledBack);
        foreach ($rolledBack as $column) {
            $this->assertSame('YES', $column->is_nullable, $column->column_name.' must be nullable after rollback.');
        }
    }

    public function test_postgres_lifecycle_audit_requires_actor_and_target_ids(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('Lifecycle audit identity requirements are PostgreSQL-specific.');
        }

        $base = [
            'actor_user_id' => 10,
            'target_user_id' => 20,
            'action' => 'suspended',
            'reason' => 'Required audit identities',
            'previous_state' => 'active',
            'new_state' => 'suspended',
            'created_at' => now(),
        ];

        foreach (['actor_user_id', 'target_user_id'] as $requiredIdentity) {
            $record = $base;
            $record[$requiredIdentity] = null;

            try {
                DB::transaction(fn () => DB::table('local_account_lifecycle_events')->insert($record));
                $this->fail("PostgreSQL must reject a lifecycle event without {$requiredIdentity}.");
            } catch (QueryException $exception) {
                $this->assertSame('23502', (string) ($exception->errorInfo[0] ?? $exception->getCode()));
                $this->assertStringContainsString('not-null constraint', strtolower($exception->getMessage()));
                $this->assertStringContainsString($requiredIdentity, strtolower($exception->getMessage()));
            }
        }

        $this->assertDatabaseCount('local_account_lifecycle_events', 0);
    }

    public function test_postgres_lifecycle_audit_constraints_reject_invalid_records(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('Lifecycle audit CHECK constraints are PostgreSQL-specific.');
        }

        $base = [
            'actor_user_id' => 10,
            'target_user_id' => 20,
            'action' => 'suspended',
            'reason' => 'Valid reason',
            'previous_state' => 'active',
            'new_state' => 'suspended',
            'created_at' => now(),
        ];

        $invalidRecords = [
            [['actor_user_id' => 20], 'local_lifecycle_distinct_parties_check'],
            [['actor_user_id' => -1], 'local_lifecycle_actor_positive_check'],
            [['target_user_id' => -1], 'local_lifecycle_target_positive_check'],
            [['reason' => '   '], 'local_lifecycle_reason_nonblank_check'],
            [['action' => 'suspended', 'previous_state' => 'suspended', 'new_state' => 'active'], 'local_lifecycle_transition_check'],
            [['action' => 'unknown', 'previous_state' => 'active', 'new_state' => 'suspended'], 'local_lifecycle_transition_check'],
        ];

        foreach ($invalidRecords as [$overrides, $expectedConstraint]) {
            try {
                DB::transaction(fn () => DB::table('local_account_lifecycle_events')
                    ->insert(array_merge($base, $overrides)));
                $this->fail('PostgreSQL must reject invalid lifecycle audit records.');
            } catch (QueryException $exception) {
                $this->assertSame('23514', (string) ($exception->errorInfo[0] ?? $exception->getCode()));
                $this->assertStringContainsString(
                    $expectedConstraint,
                    $exception->getMessage(),
                    'The expected named CHECK constraint must reject this record.',
                );
            }
        }

        $this->assertDatabaseCount('local_account_lifecycle_events', 0);
    }

    public function test_local_lifecycle_audit_event_rejects_truncate_on_postgres(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('The append-only trigger is PostgreSQL-specific.');
        }

        DB::table('local_account_lifecycle_events')->insert([
            'actor_user_id' => 10,
            'target_user_id' => 20,
            'action' => 'suspended',
            'reason' => 'Truncate protection',
            'previous_state' => 'active',
            'new_state' => 'suspended',
            'created_at' => now(),
        ]);

        try {
            DB::transaction(fn () => DB::statement('TRUNCATE TABLE local_account_lifecycle_events'));
            $this->fail('PostgreSQL must reject lifecycle-event truncation.');
        } catch (QueryException $exception) {
            $this->assertSame('55000', (string) ($exception->errorInfo[0] ?? $exception->getCode()));
            $this->assertStringContainsString('append-only', $exception->getMessage());
        }

        $this->assertDatabaseHas('local_account_lifecycle_events', [
            'reason' => 'Truncate protection',
        ]);
    }

    public function test_local_lifecycle_audit_event_rejects_deletion_on_postgres(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('The append-only trigger is PostgreSQL-specific.');
        }

        $eventId = DB::table('local_account_lifecycle_events')->insertGetId([
            'actor_user_id' => 10,
            'target_user_id' => 20,
            'action' => 'suspended',
            'reason' => 'Retention reason',
            'previous_state' => 'active',
            'new_state' => 'suspended',
            'created_at' => now(),
        ]);

        try {
            DB::transaction(fn () => DB::table('local_account_lifecycle_events')->where('id', $eventId)->delete());
            $this->fail('PostgreSQL must reject lifecycle-event deletion.');
        } catch (QueryException $exception) {
            $this->assertSame('55000', (string) ($exception->errorInfo[0] ?? $exception->getCode()));
            $this->assertStringContainsString('append-only', $exception->getMessage());
        }

        $this->assertDatabaseHas('local_account_lifecycle_events', ['id' => $eventId]);
    }

    public function test_suspended_local_account_can_still_revoke_its_current_token(): void
    {
        $user = $this->createLocalUser();
        DB::table('users')->where('id', $user->id)->update(['is_suspended' => true]);
        Sanctum::actingAs($user->fresh());

        $this->postJson('/api/logout')->assertNoContent();
    }

    public function test_existing_dispatcher_token_is_denied_for_incident_assignment_and_status_after_suspension(): void
    {
        $requester = $this->createLocalUser();
        $dispatcher = User::create([
            'name' => 'Lifecycle Dispatcher',
            'email' => 'lifecycle-dispatcher@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'dispatcher',
        ]);
        $incident = \App\Models\Incident::create([
            'requester_id' => $requester->id,
            'type' => 'medical',
            'description' => 'Synthetic lifecycle route-guard test',
            'latitude' => 14.5995,
            'longitude' => 120.9842,
            'status' => 'pending',
        ]);
        $plainTextToken = $dispatcher->createToken('dispatcher-lifecycle-regression')->plainTextToken;

        DB::table('users')->where('id', $dispatcher->id)->update([
            'is_suspended' => true,
            'suspended_at' => now(),
            'suspension_reason' => 'Synthetic dispatcher route regression',
        ]);

        foreach ([
            ['PATCH', '/api/incidents/'.$incident->id.'/status', ['status' => 'en_route']],
            ['POST', '/api/incidents/'.$incident->id.'/assign', ['user_id' => $requester->id]],
        ] as [$method, $uri, $payload]) {
            $this->withToken($plainTextToken)->json($method, $uri, $payload)
                ->assertForbidden()
                ->assertExactJson([
                    'message' => 'This local TACResQ account is suspended. Contact an administrator for assistance.',
                ])
                ->assertHeader('Cache-Control', 'no-store, private');
        }

        $this->assertSame(1, $dispatcher->tokens()->count());
        $this->assertDatabaseHas('incidents', [
            'id' => $incident->id,
            'status' => 'pending',
            'assigned_to' => null,
        ]);
    }

    public function test_public_registration_ignores_privileged_and_lifecycle_fields(): void
    {
        $response = $this->postJson('/api/register', [
            'name' => 'Public Signup',
            'email' => 'public-signup@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
            'is_suspended' => true,
            'suspended_at' => now()->toISOString(),
            'suspension_reason' => 'Injected public value',
        ]);

        $response->assertCreated()->assertJsonPath('user.role', 'requester');
        $user = User::where('email', 'public-signup@example.test')->firstOrFail();
        $this->assertSame('requester', $user->role);
        $this->assertFalse($user->is_suspended);
        $this->assertNull($user->suspended_at);
        $this->assertNull($user->suspension_reason);
    }

    public function test_lifecycle_recorder_appends_valid_transition_inside_transaction(): void
    {
        $actor = $this->createLocalUser();
        $target = User::create([
            'name' => 'Lifecycle Recorder Target',
            'email' => 'lifecycle-recorder-target@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);

        $eventId = DB::transaction(fn () => app(\App\Services\LocalAccountLifecycleRecorder::class)->record(
            $actor->id,
            $target->id,
            'suspended',
            '  Verified policy violation  ',
            'active',
            'suspended',
        ));

        $this->assertDatabaseHas('local_account_lifecycle_events', [
            'id' => $eventId,
            'actor_user_id' => $actor->id,
            'target_user_id' => $target->id,
            'action' => 'suspended',
            'reason' => 'Verified policy violation',
            'previous_state' => 'active',
            'new_state' => 'suspended',
        ]);
    }

    public function test_lifecycle_recorder_accepts_reactivation_and_maximum_reason_length(): void
    {
        $actor = $this->createLocalUser();
        $target = User::create([
            'name' => 'Lifecycle Reactivation Target',
            'email' => 'lifecycle-reactivation-target@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);
        $reason = str_repeat('R', 500);

        $eventId = DB::transaction(fn () => app(\App\Services\LocalAccountLifecycleRecorder::class)->record(
            $actor->id,
            $target->id,
            'reactivated',
            $reason,
            'suspended',
            'active',
        ));

        $this->assertDatabaseHas('local_account_lifecycle_events', [
            'id' => $eventId,
            'actor_user_id' => $actor->id,
            'target_user_id' => $target->id,
            'action' => 'reactivated',
            'reason' => $reason,
            'previous_state' => 'suspended',
            'new_state' => 'active',
        ]);
    }

    public function test_lifecycle_recorder_event_rolls_back_with_account_transaction(): void
    {
        $actor = $this->createLocalUser();
        $target = User::create([
            'name' => 'Lifecycle Rollback Target',
            'email' => 'lifecycle-rollback-target@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);

        try {
            DB::transaction(function () use ($actor, $target): void {
                app(\App\Services\LocalAccountLifecycleRecorder::class)->record(
                    $actor->id,
                    $target->id,
                    'suspended',
                    'Synthetic rollback test',
                    'active',
                    'suspended',
                );
                throw new \RuntimeException('Force transaction rollback');
            });
            $this->fail('The synthetic transaction must roll back.');
        } catch (\RuntimeException $exception) {
            $this->assertSame('Force transaction rollback', $exception->getMessage());
        }

        $this->assertDatabaseMissing('local_account_lifecycle_events', [
            'actor_user_id' => $actor->id,
            'target_user_id' => $target->id,
            'reason' => 'Synthetic rollback test',
        ]);
    }

    public function test_lifecycle_recorder_rejects_invalid_transition_and_empty_reason(): void
    {
        $actor = $this->createLocalUser();
        $target = User::create([
            'name' => 'Lifecycle Invalid Target',
            'email' => 'lifecycle-invalid-target@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);
        $recorder = app(\App\Services\LocalAccountLifecycleRecorder::class);

        try {
            DB::transaction(fn () => $recorder->record(
                $actor->id, $target->id, 'suspended', '   ', 'active', 'active'
            ));
            $this->fail('Invalid transitions and blank reasons must be rejected.');
        } catch (\InvalidArgumentException) {
            $this->assertDatabaseMissing('local_account_lifecycle_events', [
                'actor_user_id' => $actor->id,
                'target_user_id' => $target->id,
            ]);
        }
    }


    public function test_lifecycle_recorder_rejects_calls_outside_a_transaction(): void
    {
        $actor = $this->createLocalUser();
        $target = User::create([
            'name' => 'Lifecycle No Transaction Target',
            'email' => 'lifecycle-no-transaction@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);

        $this->expectException(\LogicException::class);
        app(\App\Services\LocalAccountLifecycleRecorder::class)->record(
            $actor->id,
            $target->id,
            'suspended',
            'Valid reason',
            'active',
            'suspended',
        );
    }

    public function test_lifecycle_recorder_rejects_non_positive_actor_and_target_ids(): void
    {
        $recorder = app(\App\Services\LocalAccountLifecycleRecorder::class);
        $invalidPairs = [
            [0, 20],
            [-1, 20],
            [10, 0],
            [10, -1],
        ];

        foreach ($invalidPairs as [$actorId, $targetId]) {
            try {
                DB::transaction(fn () => $recorder->record(
                    $actorId,
                    $targetId,
                    'suspended',
                    'Valid reason',
                    'active',
                    'suspended',
                ));
                $this->fail('Lifecycle actor and target IDs must both be positive.');
            } catch (\InvalidArgumentException $exception) {
                $this->assertStringContainsString('valid, distinct actor and target', $exception->getMessage());
            }
        }

        $this->assertDatabaseCount('local_account_lifecycle_events', 0);
    }

    public function test_lifecycle_recorder_rejects_same_actor_and_target(): void
    {
        $user = $this->createLocalUser();

        try {
            DB::transaction(fn () => app(\App\Services\LocalAccountLifecycleRecorder::class)->record(
                $user->id,
                $user->id,
                'suspended',
                'Valid reason',
                'active',
                'suspended',
            ));
            $this->fail('The actor must not be the lifecycle target.');
        } catch (\InvalidArgumentException $exception) {
            $this->assertStringContainsString('distinct actor and target', $exception->getMessage());
        }

        $this->assertDatabaseCount('local_account_lifecycle_events', 0);
    }

    public function test_lifecycle_recorder_rejects_reasons_longer_than_database_limit(): void
    {
        $actor = $this->createLocalUser();
        $target = User::create([
            'name' => 'Lifecycle Long Reason Target',
            'email' => 'lifecycle-long-reason@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);

        try {
            DB::transaction(fn () => app(\App\Services\LocalAccountLifecycleRecorder::class)->record(
                $actor->id,
                $target->id,
                'suspended',
                str_repeat('x', 501),
                'active',
                'suspended',
            ));
            $this->fail('Reasons longer than the database limit must be rejected.');
        } catch (\InvalidArgumentException $exception) {
            $this->assertStringContainsString('between 1 and 500', $exception->getMessage());
        }

        $this->assertDatabaseCount('local_account_lifecycle_events', 0);
    }

    public function test_local_admin_sensitive_action_requires_admin_password_and_totp(): void
    {
        $admin = User::create([
            'name' => 'Local Lifecycle Admin',
            'email' => 'local-lifecycle-admin@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $this->adminTotpCode,
        ])->assertOk()->assertExactJson(['ok' => true]);
    }

    public function test_local_admin_sensitive_action_rejects_non_admin_even_with_correct_password(): void
    {
        $requester = $this->createLocalUser();
        Sanctum::actingAs($requester);
        $this->registerLocalAdminReauthenticationTestRoute();

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
        ])->assertForbidden()->assertExactJson([
            'message' => 'Administrator access is required.',
        ])->assertHeader('Cache-Control', 'no-store, private');
    }

    public function test_local_admin_sensitive_action_rechecks_demoted_role_from_database(): void
    {
        $admin = User::create([
            'name' => 'Demoted Step-up Admin',
            'email' => 'demoted-step-up@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        DB::table('users')->where('id', $admin->id)->update(['role' => 'requester']);
        Sanctum::actingAs($admin->fresh());

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $this->adminTotpCode,
        ])->assertForbidden()->assertExactJson([
            'message' => 'Administrator access is required.',
        ])->assertHeader('Cache-Control', 'no-store, private');

        $credential = DB::table('local_admin_totp_credentials')->where('user_id', $admin->id)->first();
        $this->assertNull($credential->last_used_step);
    }

    public function test_local_admin_sensitive_action_rechecks_suspension_from_database(): void
    {
        $admin = User::create([
            'name' => 'Suspended Step-up Admin',
            'email' => 'suspended-step-up@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        DB::table('users')->where('id', $admin->id)->update([
            'is_suspended' => true,
            'suspended_at' => now(),
            'suspension_reason' => 'Synthetic security test',
        ]);
        Sanctum::actingAs($admin->fresh());

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $this->adminTotpCode,
        ])->assertForbidden();

        $credential = DB::table('local_admin_totp_credentials')->where('user_id', $admin->id)->first();
        $this->assertNull($credential->last_used_step);
    }

    public function test_local_admin_sensitive_action_rejects_incorrect_password(): void
    {
        $admin = User::create([
            'name' => 'Local Lifecycle Admin',
            'email' => 'local-lifecycle-admin@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'incorrect-password',
            'totp_code' => $this->adminTotpCode,
        ])->assertForbidden()->assertExactJson([
            'message' => 'Administrator re-authentication failed.',
        ])->assertHeader('Cache-Control', 'no-store, private');
    }

    public function test_local_admin_reauthentication_is_rate_limited_after_repeated_failures(): void
    {
        $admin = User::create([
            'name' => 'Local Lifecycle Admin',
            'email' => 'local-lifecycle-admin@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        for ($attempt = 0; $attempt < 5; $attempt++) {
            $this->postJson('/_test/local-admin-sensitive-action', [
                'current_password' => 'incorrect-password',
                'totp_code' => $this->adminTotpCode,
            ])->assertForbidden();
        }

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $this->adminTotpCode,
        ])->assertTooManyRequests()
            ->assertHeader('Cache-Control', 'no-store, private');
    }



    public function test_local_admin_sensitive_action_rejects_incorrect_totp_code(): void
    {
        $admin = User::create([
            'name' => 'Local Lifecycle Admin',
            'email' => 'local-lifecycle-admin@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => '000000',
        ])->assertForbidden()->assertExactJson([
            'message' => 'Administrator re-authentication failed.',
        ]);
    }

    public function test_local_admin_totp_code_cannot_be_replayed(): void
    {
        $admin = User::create([
            'name' => 'Local Lifecycle Admin',
            'email' => 'local-lifecycle-admin@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        $payload = [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $this->adminTotpCode,
        ];

        $this->postJson('/_test/local-admin-sensitive-action', $payload)->assertOk();
        $this->postJson('/_test/local-admin-sensitive-action', $payload)
            ->assertForbidden()->assertExactJson([
                'message' => 'Administrator re-authentication failed.',
            ]);
    }



    public function test_local_admin_totp_implementation_matches_rfc_6238_sha1_vector(): void
    {
        $service = app(\App\Services\LocalAdminTotpService::class);
        $this->assertSame('287082', $service->codeForStep('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 1));
    }

    public function test_local_admin_can_enroll_and_confirm_authenticator_factor(): void
    {
        $admin = User::create([
            'name' => 'Local TOTP Admin',
            'email' => 'local-totp-admin@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);

        $enrollment = $this->postJson('/api/admin/security/totp/enroll', [
            'current_password' => 'synthetic-test-password',
        ])->assertOk()->assertHeader('Cache-Control', 'no-store, private')
            ->assertJsonStructure(['secret', 'otpauth_uri', 'message']);

        $secret = $enrollment->json('secret');
        $this->assertIsString($secret);
        $this->assertStringContainsString('issuer=TACResQ', $enrollment->json('otpauth_uri'));
        $this->assertDatabaseHas('local_admin_totp_credentials', [
            'user_id' => $admin->id,
            'confirmed_at' => null,
        ]);
        $pendingCredential = DB::table('local_admin_totp_credentials')->where('user_id', $admin->id)->first();
        $this->assertNotNull($pendingCredential->expires_at);

        $step = intdiv(now()->timestamp, 30);
        $code = app(\App\Services\LocalAdminTotpService::class)->codeForStep($secret, $step);
        $this->postJson('/api/admin/security/totp/confirm', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $code,
        ])->assertOk()->assertJsonPath('enabled', true)
            ->assertHeader('Cache-Control', 'no-store, private');

        $credential = DB::table('local_admin_totp_credentials')->where('user_id', $admin->id)->first();
        $this->assertNotNull($credential->confirmed_at);
        $this->assertNull($credential->expires_at);
        $this->assertSame($step, (int) $credential->last_used_step);
        $this->assertSame(hash('sha256', $code), $credential->last_used_code_hash);
        $this->assertStringNotContainsString($secret, $credential->secret_ciphertext);
    }

    public function test_suspended_local_admin_cannot_start_totp_enrollment(): void
    {
        $admin = User::create([
            'name' => 'Suspended TOTP Admin',
            'email' => 'suspended-totp-admin@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        DB::table('users')->where('id', $admin->id)->update([
            'is_suspended' => true,
            'suspended_at' => now(),
            'suspension_reason' => 'Synthetic security test',
        ]);
        Sanctum::actingAs($admin->fresh());

        $this->postJson('/api/admin/security/totp/enroll', [
            'current_password' => 'synthetic-test-password',
        ])->assertForbidden();

        $this->assertDatabaseMissing('local_admin_totp_credentials', [
            'user_id' => $admin->id,
        ]);
    }

    public function test_demoted_local_account_cannot_start_totp_enrollment(): void
    {
        $admin = User::create([
            'name' => 'Demoted TOTP Admin',
            'email' => 'demoted-totp-admin@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        DB::table('users')->where('id', $admin->id)->update(['role' => 'requester']);
        Sanctum::actingAs($admin->fresh());

        $this->postJson('/api/admin/security/totp/enroll', [
            'current_password' => 'synthetic-test-password',
        ])->assertForbidden();

        $this->assertDatabaseMissing('local_admin_totp_credentials', [
            'user_id' => $admin->id,
        ]);
    }

    public function test_suspended_admin_cannot_confirm_pending_totp_enrollment(): void
    {
        $admin = User::create([
            'name' => 'Suspended Confirmation Admin',
            'email' => 'suspended-confirmation@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $enrollment = $this->postJson('/api/admin/security/totp/enroll', [
            'current_password' => 'synthetic-test-password',
        ])->assertOk();
        $secret = $enrollment->json('secret');
        $code = app(\App\Services\LocalAdminTotpService::class)->codeForStep($secret, intdiv(now()->timestamp, 30));

        DB::table('users')->where('id', $admin->id)->update([
            'is_suspended' => true,
            'suspended_at' => now(),
            'suspension_reason' => 'Synthetic security test',
        ]);

        $this->postJson('/api/admin/security/totp/confirm', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $code,
        ])->assertForbidden()->assertHeader('Cache-Control', 'no-store, private');

        $this->assertDatabaseHas('local_admin_totp_credentials', [
            'user_id' => $admin->id,
            'confirmed_at' => null,
        ]);
    }

    public function test_demoted_account_cannot_confirm_pending_totp_enrollment(): void
    {
        $admin = User::create([
            'name' => 'Demoted Confirmation Admin',
            'email' => 'demoted-confirmation@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $enrollment = $this->postJson('/api/admin/security/totp/enroll', [
            'current_password' => 'synthetic-test-password',
        ])->assertOk();
        $secret = $enrollment->json('secret');
        $code = app(\App\Services\LocalAdminTotpService::class)->codeForStep($secret, intdiv(now()->timestamp, 30));

        DB::table('users')->where('id', $admin->id)->update(['role' => 'requester']);

        $this->postJson('/api/admin/security/totp/confirm', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $code,
        ])->assertForbidden()->assertHeader('Cache-Control', 'no-store, private');

        $this->assertDatabaseHas('local_admin_totp_credentials', [
            'user_id' => $admin->id,
            'confirmed_at' => null,
        ]);
    }

    public function test_local_admin_totp_enrollment_rechecks_password_after_locking_account(): void
    {
        $admin = User::create([
            'name' => 'Local TOTP Enrollment Password Admin',
            'email' => 'local-totp-enrollment-password@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);

        DB::table('users')->where('id', $admin->id)->update([
            'password' => bcrypt('changed-password-after-authentication'),
        ]);

        $this->postJson('/api/admin/security/totp/enroll', [
            'current_password' => 'synthetic-test-password',
        ])->assertForbidden()->assertExactJson([
            'message' => 'Administrator re-authentication failed.',
        ])->assertHeader('Cache-Control', 'no-store, private');

        $this->assertDatabaseMissing('local_admin_totp_credentials', [
            'user_id' => $admin->id,
        ]);
    }

    public function test_local_admin_totp_confirmation_requires_current_password(): void
    {
        $admin = User::create([
            'name' => 'Local TOTP Password Admin',
            'email' => 'local-totp-password@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);

        $enrollment = $this->postJson('/api/admin/security/totp/enroll', [
            'current_password' => 'synthetic-test-password',
        ])->assertOk();
        $secret = $enrollment->json('secret');
        $code = app(\App\Services\LocalAdminTotpService::class)->codeForStep(
            $secret,
            intdiv(now()->timestamp, 30),
        );

        $this->postJson('/api/admin/security/totp/confirm', [
            'current_password' => 'incorrect-password',
            'totp_code' => $code,
        ])->assertForbidden()->assertExactJson([
            'message' => 'Administrator re-authentication failed.',
        ])->assertHeader('Cache-Control', 'no-store, private');

        $this->assertDatabaseHas('local_admin_totp_credentials', [
            'user_id' => $admin->id,
            'confirmed_at' => null,
        ]);
    }

    public function test_local_admin_cannot_silently_replace_a_confirmed_authenticator(): void
    {
        $admin = User::create([
            'name' => 'Local TOTP Admin',
            'email' => 'local-totp-rotate@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        $this->postJson('/api/admin/security/totp/enroll', [
            'current_password' => 'synthetic-test-password',
        ])->assertConflict();
    }



    public function test_local_admin_totp_enrollment_expires_after_ten_minutes(): void
    {
        $admin = User::create([
            'name' => 'Local TOTP Expiry Admin',
            'email' => 'local-totp-expiry@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);

        $enrollment = $this->postJson('/api/admin/security/totp/enroll', [
            'current_password' => 'synthetic-test-password',
        ])->assertOk();

        $secret = $enrollment->json('secret');
        $step = intdiv(now()->timestamp, 30);
        $code = app(\App\Services\LocalAdminTotpService::class)->codeForStep($secret, $step);

        DB::table('local_admin_totp_credentials')->where('user_id', $admin->id)
            ->update(['expires_at' => now()->subSecond()]);

        $this->postJson('/api/admin/security/totp/confirm', ['current_password' => 'synthetic-test-password', 'totp_code' => $code])
            ->assertUnprocessable();
        $this->assertDatabaseHas('local_admin_totp_credentials', [
            'user_id' => $admin->id,
            'confirmed_at' => null,
        ]);
    }



    public function test_unconfirmed_local_admin_totp_factor_cannot_authorize_sensitive_action(): void
    {
        $admin = User::create([
            'name' => 'Local Pending TOTP Admin',
            'email' => 'local-pending-totp@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        DB::table('local_admin_totp_credentials')->where('user_id', $admin->id)
            ->update(['confirmed_at' => null]);

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $this->adminTotpCode,
        ])->assertForbidden()->assertExactJson([
            'message' => 'Administrator re-authentication failed.',
        ]);
    }

    public function test_previously_used_totp_value_is_rejected_even_if_a_later_step_collides(): void
    {
        $admin = User::create([
            'name' => 'Local TOTP Collision Admin',
            'email' => 'local-totp-collision@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        DB::table('local_admin_totp_credentials')->where('user_id', $admin->id)->update([
            'last_used_step' => intdiv(now()->timestamp, 30) - 1,
            'last_used_code_hash' => hash('sha256', $this->adminTotpCode),
        ]);

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $this->adminTotpCode,
        ])->assertForbidden()->assertExactJson([
            'message' => 'Administrator re-authentication failed.',
        ]);
    }


    public function test_malformed_local_admin_step_up_attempts_are_non_cacheable_and_rate_limited(): void
    {
        $admin = User::create([
            'name' => 'Malformed Step-up Admin',
            'email' => 'malformed-step-up@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        for ($attempt = 1; $attempt <= 4; $attempt++) {
            $this->postJson('/_test/local-admin-sensitive-action', [
                'current_password' => 'synthetic-test-password',
                'totp_code' => 'not-a-code',
            ])->assertUnprocessable()
                ->assertHeader('Cache-Control', 'no-store, private');
        }

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => 'not-a-code',
        ])->assertTooManyRequests()
            ->assertHeader('Retry-After')
            ->assertHeader('Cache-Control', 'no-store, private');
    }

    public function test_successful_local_admin_step_up_clears_malformed_attempt_throttle(): void
    {
        $admin = User::create([
            'name' => 'Throttle Reset Admin',
            'email' => 'throttle-reset-admin@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'admin',
        ]);
        Sanctum::actingAs($admin);
        $this->registerLocalAdminReauthenticationTestRoute();

        $key = 'local-admin-reauth:'.hash('sha256', (string) $admin->id.'|127.0.0.1');
        for ($attempt = 0; $attempt < 4; $attempt++) {
            \\Illuminate\\Support\\Facades\\RateLimiter::hit($key, 900);
        }

        $this->postJson('/_test/local-admin-sensitive-action', [
            'current_password' => 'synthetic-test-password',
            'totp_code' => $this->adminTotpCode,
        ])->assertOk();

        for ($attempt = 1; $attempt <= 4; $attempt++) {
            $this->postJson('/_test/local-admin-sensitive-action', [
                'current_password' => 'synthetic-test-password',
                'totp_code' => 'not-a-code',
            ])->assertUnprocessable()
                ->assertHeader('Cache-Control', 'no-store, private');
        }
    }

}
