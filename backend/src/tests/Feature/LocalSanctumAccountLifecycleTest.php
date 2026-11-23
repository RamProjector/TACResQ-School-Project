<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Database\QueryException;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class LocalSanctumAccountLifecycleTest extends TestCase
{
    use RefreshDatabase;

    private function createLocalUser(): User
    {
        return User::create([
            'name' => 'Lifecycle Test User',
            'email' => 'lifecycle-test@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
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

    public function test_local_lifecycle_audit_schema_retains_events_after_user_deletion(): void
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
            'actor_user_id' => null,
            'target_user_id' => null,
            'action' => 'suspended',
            'reason' => 'Synthetic retention test',
        ]);
    }

    public function test_local_lifecycle_audit_event_rejects_content_updates_on_postgres(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('The append-only trigger is PostgreSQL-specific.');
        }

        $eventId = DB::table('local_account_lifecycle_events')->insertGetId([
            'action' => 'suspended',
            'reason' => 'Original reason',
            'previous_state' => 'active',
            'new_state' => 'suspended',
            'created_at' => now(),
        ]);

        try {
            DB::table('local_account_lifecycle_events')->where('id', $eventId)
                ->update(['reason' => 'Tampered reason']);
            $this->fail('PostgreSQL must reject lifecycle-event content updates.');
        } catch (QueryException $exception) {
            $this->assertStringContainsString('append-only', $exception->getMessage());
        }

        $this->assertDatabaseHas('local_account_lifecycle_events', [
            'id' => $eventId,
            'reason' => 'Original reason',
        ]);
    }

    public function test_local_lifecycle_audit_event_rejects_deletion_on_postgres(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            $this->markTestSkipped('The append-only trigger is PostgreSQL-specific.');
        }

        $eventId = DB::table('local_account_lifecycle_events')->insertGetId([
            'action' => 'suspended',
            'reason' => 'Retention reason',
            'previous_state' => 'active',
            'new_state' => 'suspended',
            'created_at' => now(),
        ]);

        try {
            DB::table('local_account_lifecycle_events')->where('id', $eventId)->delete();
            $this->fail('PostgreSQL must reject lifecycle-event deletion.');
        } catch (QueryException $exception) {
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

}
