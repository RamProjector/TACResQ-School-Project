#!/usr/bin/env python3
"""Lock the local Sanctum account-state and append-only audit boundaries."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "backend/src/database/migrations/2026_11_23_000007_add_local_sanctum_account_lifecycle_state.php").read_text()
audit_migration = (ROOT / "backend/src/database/migrations/2026_11_23_000008_create_local_account_lifecycle_events.php").read_text()
append_only_migration = (ROOT / "backend/src/database/migrations/2026_11_23_000009_enforce_local_lifecycle_event_append_only.php").read_text()
audit_constraints_migration = (ROOT / "backend/src/database/migrations/2026_11_23_000011_constrain_local_account_lifecycle_events.php").read_text()
audit_identity_migration = (ROOT / "backend/src/database/migrations/2026_11_23_000012_require_local_lifecycle_audit_identities.php").read_text()

model = (ROOT / "backend/src/app/Models/User.php").read_text()
middleware = (ROOT / "backend/src/app/Http/Middleware/EnsureLocalAccountIsActive.php").read_text()
routes = (ROOT / "backend/src/routes/api.php").read_text()
bootstrap = (ROOT / "backend/src/bootstrap/app.php").read_text()
feature = (ROOT / "backend/src/tests/Feature/LocalSanctumAccountLifecycleTest.php").read_text()
workflow = (ROOT / ".github/workflows/ci.yml").read_text()
recorder = (ROOT / "backend/src/app/Services/LocalAccountLifecycleRecorder.php").read_text()
admin_reauth = (ROOT / "backend/src/app/Http/Middleware/EnsureLocalAdminReauthenticated.php").read_text()
totp_migration = (ROOT / "backend/src/database/migrations/2026_11_23_000010_create_local_admin_totp_credentials.php").read_text()
totp_service = (ROOT / "backend/src/app/Services/LocalAdminTotpService.php").read_text()

assert "boolean('is_suspended')->default(false)" in migration
assert "timestampTz('suspended_at')->nullable()" in migration
assert "string('suspension_reason', 500)->nullable()" in migration
assert "'is_suspended' => 'boolean'" in model
assert "'is_suspended'" not in model.split("protected $fillable", 1)[1].split(";", 1)[0], "Public mass assignment must not control suspension"
assert "EnsureLocalAccountIsActive::class" in bootstrap
assert "EnsureLocalAdminReauthenticated::class" in bootstrap
assert "'local.admin.reauthenticated'" in bootstrap
assert "Route::middleware(['auth:sanctum', 'account.active'])->group" in routes
login = routes[routes.index("Route::post('/login'"):routes.index("Route::post('/register'")]
assert "if ($user->is_suspended)" in login, "Suspended local accounts must not receive new tokens"
assert "->withoutMiddleware('account.active')" in routes, "Self-service logout routes must remain available"
assert "if ($user !== null && (bool) $user->is_suspended)" in middleware
assert "403" in middleware and "no-store, private" in middleware
assert "test_existing_local_accounts_remain_active_by_default" in feature
assert "test_suspended_local_account_cannot_access_protected_sanctum_routes" in feature
assert "test_suspended_local_account_can_still_revoke_its_current_token" in feature
assert "test_suspended_local_account_cannot_obtain_a_new_sanctum_token" in feature
assert "test_existing_sanctum_token_is_denied_across_protected_incident_and_account_routes_after_suspension" in feature
for protected_path in ("/api/me", "/api/incidents/mine", "/api/incidents", "/api/units", "/api/change-password"):
    assert protected_path in feature, f"Missing same-token suspension coverage for {protected_path}"
assert "withToken($plainTextToken)->json($method, $uri)" in feature
assert "test_existing_dispatcher_token_is_denied_for_incident_assignment_and_status_after_suspension" in feature
assert "'/status'" in feature and "'/assign'" in feature
assert "test_public_registration_ignores_privileged_and_lifecycle_fields" in feature
assert "email" not in migration.lower(), "Do not add cross-provider identity mapping"
assert "local_account_lifecycle_events" in audit_migration
for column in ("actor_user_id", "target_user_id", "action", "reason", "previous_state", "new_state", "created_at"):
    assert column in audit_migration, f"Missing local lifecycle audit field: {column}"
assert "unsignedBigInteger('actor_user_id')->nullable()" in audit_migration
assert "unsignedBigInteger('target_user_id')->nullable()" in audit_migration
assert "constrained('users')" not in audit_migration, "Audit identifiers must remain immutable after user deletion"
assert "CREATE TRIGGER local_lifecycle_events_append_only" in append_only_migration
assert "BEFORE UPDATE OR DELETE" in append_only_migration
assert "BEFORE TRUNCATE" in append_only_migration
assert "local_lifecycle_events_no_truncate" in append_only_migration
assert "RAISE EXCEPTION 'local account lifecycle events are append-only'" in append_only_migration
assert "OLD.actor_user_id" not in append_only_migration and "OLD.target_user_id" not in append_only_migration
assert "test_local_lifecycle_audit_schema_retains_original_actor_and_target_ids_after_user_deletion" in feature
assert "test_local_lifecycle_audit_event_rejects_actor_or_target_reference_mutation_on_postgres" in feature
assert "test_local_lifecycle_audit_event_rejects_content_updates_on_postgres" in feature
assert "test_local_lifecycle_audit_event_rejects_deletion_on_postgres" in feature
assert "test_local_lifecycle_audit_event_rejects_truncate_on_postgres" in feature
assert "final class LocalAccountLifecycleRecorder" in recorder
assert "$this->database->connection()->transactionLevel() < 1" in recorder, "Lifecycle writes must require an active transaction"
assert "ConnectionResolverInterface $database" in recorder, "Lifecycle recorder must use the injected Laravel connection resolver"
assert "self::TRANSITIONS[$action]" in recorder, "Only explicit lifecycle transitions may be recorded"
assert "mb_strlen($reason) > 500" in recorder, "Lifecycle reason must respect the database limit"
assert "insertGetId" in recorder and "created_at" in recorder
assert "test_lifecycle_recorder_appends_valid_transition_inside_transaction" in feature
assert "test_lifecycle_recorder_accepts_reactivation_and_maximum_reason_length" in feature
assert "'reactivated'" in feature and "'suspended'" in feature and "'active'" in feature
assert "str_repeat('R', 500)" in feature
assert "test_lifecycle_recorder_event_rolls_back_with_account_transaction" in feature
assert "test_lifecycle_recorder_rejects_invalid_transition_and_empty_reason" in feature
assert "final class EnsureLocalAdminReauthenticated" in admin_reauth
assert "$user->role !== 'admin'" in admin_reauth, "Only local Laravel Admins may pass the privileged guard"
assert "Hash::check($credentials['current_password'], $lockedUser->password)" in admin_reauth
assert "RateLimiter::tooManyAttempts($key, 5)" in admin_reauth
assert "RateLimiter::hit($key, 900)" in admin_reauth
assert "test_local_admin_sensitive_action_requires_admin_password_and_totp" in feature
assert "test_local_admin_sensitive_action_rejects_non_admin_even_with_correct_password" in feature
assert "test_local_admin_sensitive_action_rejects_incorrect_password" in feature
assert "test_local_admin_reauthentication_is_rate_limited_after_repeated_failures" in feature
assert "'totp_code'" in admin_reauth and "verifyAndConsume" in admin_reauth
assert "LocalAdminTotpService::class" in admin_reauth
assert "verifyAndConsume($lockedUser, $credentials['totp_code'])" in admin_reauth
assert "local_admin_totp_credentials" in totp_migration
assert "secret_ciphertext" in totp_migration and "last_used_step" in totp_migration and "last_used_code_hash" in totp_migration
assert "confirmed_at" in totp_migration and "expires_at" in totp_migration and "unique()->constrained('users')" in totp_migration
assert "Crypt::encryptString" in totp_service and "Crypt::decryptString" in totp_service
assert "hash_hmac('sha1'" in totp_service and "PERIOD_SECONDS = 30" in totp_service
assert "lockForUpdate()" in totp_service and "$candidateStep <= $lastUsedStep" in totp_service
assert "last_used_code_hash" in totp_service and "hash('sha256', $code)" in totp_service
assert "confirmEnrollment" in totp_service and "verifyAndConsume" in totp_service
assert "prefix('admin/security/totp')" in routes and "Route::post('/enroll'" in routes and "Route::post('/confirm'" in routes
assert "secret_ciphertext" in routes and "Cache-Control', 'no-store, private'" in routes
assert "test_local_admin_totp_implementation_matches_rfc_6238_sha1_vector" in feature
assert "test_local_admin_can_enroll_and_confirm_authenticator_factor" in feature
assert "test_local_admin_cannot_silently_replace_a_confirmed_authenticator" in feature
assert "test_local_admin_totp_enrollment_expires_after_ten_minutes" in feature
assert "test_local_admin_sensitive_action_rejects_incorrect_totp_code" in feature
assert "test_local_admin_totp_code_cannot_be_replayed" in feature
assert "test_unconfirmed_local_admin_totp_factor_cannot_authorize_sensitive_action" in feature
assert "test_previously_used_totp_value_is_rejected_even_if_a_later_step_collides" in feature
assert "test_ci_can_require_the_lifecycle_suite_to_use_postgresql" in feature
assert "TACRESQ_REQUIRE_POSTGRES_LIFECYCLE=1" in workflow
assert "php artisan test --filter=LocalSanctumAccountLifecycleTest" in workflow

print("Local Sanctum lifecycle guard and append-only audit contracts passed.")


# TOTP confirmation is a sensitive factor-binding operation: require password re-auth,
# lock and re-check the local Admin row, and never confirm for suspended/demoted users.
confirm_start = routes.index("Route::post('/confirm'")
confirm_end = routes.index("})->middleware('throttle:5,1');", confirm_start)
confirm_route = routes[confirm_start:confirm_end]
assert "current_password" in confirm_route
assert "Hash::check($data['current_password'], $lockedUser->password)" in confirm_route
assert "User::whereKey($user->id)->lockForUpdate()" in confirm_route
assert "$lockedUser->role !== 'admin'" in confirm_route
assert "$lockedUser->is_suspended" in confirm_route
assert "test_local_admin_totp_confirmation_requires_current_password" in feature


# Enrollment rechecks the local password under the locked user row to close
# the password-change race between initial request validation and factor creation.
enroll_start = routes.index("Route::post('/enroll'")
enroll_end = routes.index("})->middleware('throttle:3,1');", enroll_start)
enroll_route = routes[enroll_start:enroll_end]
assert "DB::transaction(function () use ($user, $data)" in enroll_route
assert "Hash::check($data['current_password'], $lockedUser->password)" in enroll_route
assert "test_local_admin_totp_enrollment_rechecks_password_after_locking_account" in feature


# Local Admin TOTP enrollment must not be available to suspended or demoted users.
assert "test_suspended_local_admin_cannot_start_totp_enrollment" in feature
assert "test_demoted_local_account_cannot_start_totp_enrollment" in feature
enroll_start = routes.index("Route::post('/enroll'")
enroll_end = routes.index("})->middleware('throttle:3,1');", enroll_start)
enroll_route = routes[enroll_start:enroll_end]
assert "$lockedUser->role !== 'admin'" in enroll_route
assert "$lockedUser->is_suspended" in enroll_route


# Pending local Admin TOTP enrollment cannot be confirmed after suspension or demotion.
assert "test_suspended_admin_cannot_confirm_pending_totp_enrollment" in feature
assert "test_demoted_account_cannot_confirm_pending_totp_enrollment" in feature
confirm_start = routes.index("Route::post('/confirm'")
confirm_end = routes.index("})->middleware('throttle:5,1');", confirm_start)
confirm_route = routes[confirm_start:confirm_end]
assert "$lockedUser->role !== 'admin'" in confirm_route
assert "$lockedUser->is_suspended" in confirm_route
assert "User::whereKey($user->id)->lockForUpdate()" in confirm_route


# Step-up authorization re-reads and locks the local account before consuming TOTP.
assert "User::whereKey($user->getAuthIdentifier())->lockForUpdate()->first()" in admin_reauth
assert "DB::transaction(function () use ($user, $credentials)" in admin_reauth
assert "test_local_admin_sensitive_action_rechecks_demoted_role_from_database" in feature
assert "test_local_admin_sensitive_action_rechecks_suspension_from_database" in feature


# Recorder input boundaries: transaction required, distinct actor/target, bounded reason.
assert "test_lifecycle_recorder_rejects_calls_outside_a_transaction" in feature
assert "test_lifecycle_recorder_rejects_same_actor_and_target" in feature
assert "test_lifecycle_recorder_rejects_non_positive_actor_and_target_ids" in feature
assert "Lifecycle actor and target IDs must both be positive." in feature
assert "[0, 20]" in feature and "[-1, 20]" in feature and "[10, 0]" in feature and "[10, -1]" in feature
assert "test_lifecycle_recorder_rejects_reasons_longer_than_database_limit" in feature
assert "A valid, distinct actor and target are required." in recorder
assert "mb_strlen($reason) > 500" in recorder
assert "$this->database->connection()->transactionLevel() < 1" in recorder

# PostgreSQL rejection tests use nested transactions/savepoints so expected trigger
# exceptions do not poison Laravel's surrounding RefreshDatabase transaction.
assert "test_local_lifecycle_audit_event_rejects_truncate_on_postgres" in feature
assert "DB::transaction(fn () => DB::statement('TRUNCATE TABLE local_account_lifecycle_events'))" in feature
assert "DB::transaction(fn () => DB::table('local_account_lifecycle_events')->where('id', $eventId)->delete())" in feature
assert "DB::transaction(fn () => DB::table('local_account_lifecycle_events')->where('id', $eventId)->update($changes))" in feature
assert "local_lifecycle_events_no_truncate" in append_only_migration

# The PostgreSQL constraint migration must remain valid PHP and reversible.
assert "use Illuminate\\Database\\Migrations\\Migration;" in audit_constraints_migration
assert "use Illuminate\\Support\\Facades\\DB;" in audit_constraints_migration
assert "use Illuminate\\\\Database" not in audit_constraints_migration
assert "DB::connection()->getDriverName() !== 'pgsql'" in audit_constraints_migration
assert "DROP CONSTRAINT IF EXISTS local_lifecycle_actor_positive_check" in audit_constraints_migration
assert audit_constraints_migration.rstrip().endswith("};")

# PostgreSQL independently enforces audit event shape, identity, and transition invariants.
constraint_names = (
    "local_lifecycle_actor_positive_check",
    "local_lifecycle_target_positive_check",
    "local_lifecycle_distinct_parties_check",
    "local_lifecycle_reason_nonblank_check",
    "local_lifecycle_transition_check",
)
for constraint in constraint_names:
    assert constraint in audit_constraints_migration
    assert f"DROP CONSTRAINT IF EXISTS {constraint}" in audit_constraints_migration, (
        f"Rollback must remove the exact constraint it creates: {constraint}"
    )
up_sql, down_sql = audit_constraints_migration.split("public function down(): void", 1)
assert up_sql.count("ADD CONSTRAINT") == len(constraint_names), "Migration must add each named invariant exactly once"
assert down_sql.count("DROP CONSTRAINT IF EXISTS") == len(constraint_names), "Rollback must drop every named invariant exactly once"
assert "DB::connection()->getDriverName() !== 'pgsql'" in up_sql
assert "DB::connection()->getDriverName() !== 'pgsql'" in down_sql
assert "actor_user_id <> target_user_id" in audit_constraints_migration
assert "length(btrim(reason)) BETWEEN 1 AND 500" in audit_constraints_migration
assert "action = 'suspended' AND previous_state = 'active' AND new_state = 'suspended'" in audit_constraints_migration
assert "action = 'reactivated' AND previous_state = 'suspended' AND new_state = 'active'" in audit_constraints_migration
assert "test_postgres_lifecycle_audit_constraints_reject_invalid_records" in feature
assert "test_postgres_lifecycle_audit_requires_actor_and_target_ids" in feature
assert "test_postgres_identity_migration_fails_closed_when_legacy_events_are_unattributed" in feature, "PostgreSQL migration preflight must have an executable fail-closed test"
assert "test_postgres_identity_migration_enforces_and_rolls_back_both_required_columns" in feature, "PostgreSQL migration must have executable up/down coverage"
assert "migration->up()" in feature and "migration->down()" in feature, "Migration integration tests must execute both directions"
assert "ACCESS EXCLUSIVE" in audit_identity_migration, "Identity migration must serialize its preflight with concurrent writes"
assert "assertSame('55000'" in feature, "Append-only trigger tests must assert PostgreSQL SQLSTATE 55000"
assert "assertSame('23502'" in feature, "Required audit identity tests must assert PostgreSQL SQLSTATE 23502"
assert "assertSame('23514'" in feature, "Audit CHECK constraint tests must assert PostgreSQL SQLSTATE 23514"
assert "ALTER COLUMN actor_user_id SET NOT NULL" in audit_identity_migration
assert "ALTER COLUMN target_user_id SET NOT NULL" in audit_identity_migration
assert "ALTER COLUMN actor_user_id DROP NOT NULL" in audit_identity_migration
assert "ALTER COLUMN target_user_id DROP NOT NULL" in audit_identity_migration
assert "whereNull('actor_user_id')" in audit_identity_migration
assert "LOCK TABLE local_account_lifecycle_events IN ACCESS EXCLUSIVE MODE" in audit_identity_migration
assert audit_identity_migration.index("LOCK TABLE local_account_lifecycle_events") < audit_identity_migration.index("$unattributedEvents =")
assert "orWhereNull('target_user_id')" in audit_identity_migration
assert "$unattributedEvents > 0" in audit_identity_migration
assert "separately approved audit-preserving process" in audit_identity_migration
assert audit_identity_migration.index("$unattributedEvents > 0") < audit_identity_migration.index("ALTER COLUMN actor_user_id SET NOT NULL")
assert audit_identity_migration.count("DB::connection()->getDriverName() !== 'pgsql'") == 2
assert "use Illuminate\\Database\\Migrations\\Migration;" in audit_identity_migration
assert "use Illuminate\\Support\\Facades\\DB;" in audit_identity_migration
assert audit_identity_migration.rstrip().endswith("};")
for constraint in (
    "local_lifecycle_distinct_parties_check",
    "local_lifecycle_actor_positive_check",
    "local_lifecycle_target_positive_check",
    "local_lifecycle_reason_nonblank_check",
    "local_lifecycle_transition_check",
):
    assert constraint in feature, f"PostgreSQL rejection tests must identify the exact failing constraint: {constraint}"
assert "The expected named CHECK constraint must reject this record." in feature


# Route-boundary regression: only the two self-service token-revocation routes
# bypass account.active. Every other Sanctum route remains inside the guarded
# group, and the Supabase bridge remains a separate bearer-token domain.
group_start = routes.index("Route::middleware(['auth:sanctum', 'account.active'])->group")
group_end = routes.index("\n});", group_start)
sanctum_group = routes[group_start:group_end]
assert sanctum_group.count("->withoutMiddleware('account.active')") == 2
assert "Route::post('/logout'," in sanctum_group
assert "Route::post('/logout-all'," in sanctum_group
assert "Route::post('/change-password'" in sanctum_group
assert "Route::get('/incidents/mine'" in sanctum_group
assert "Route::post('/incidents/{incident}/assign'" in sanctum_group
assert "Route::get('/bridge/me'" not in sanctum_group
assert "Route::post('/bridge/rpc/{function}'" not in sanctum_group

# The login denial must occur after credential verification but before token
# issuance; public registration must always force requester role and must not
# accept caller-controlled lifecycle fields.
login_block = routes[routes.index("Route::post('/login'"):routes.index("Route::post('/register'")]
assert login_block.index("Hash::check") < login_block.index("if ($user->is_suspended)") < login_block.index("createToken")
register_block = routes[routes.index("Route::post('/register'"):routes.index("Route::middleware(['auth:sanctum'")]
assert "'role' => 'requester'" in register_block
assert "'is_suspended'" not in register_block
assert "'suspended_at'" not in register_block
assert "'suspension_reason'" not in register_block

# The local Admin step-up middleware is only a prerequisite, not a public
# transition API. Keep lifecycle transitions unavailable until their audited
# transactional endpoint and disposable-PostgreSQL concurrency tests exist.
assert "local.admin.reauthenticated" in bootstrap
assert "Route::post('/admin/accounts/{user}/suspend'" not in routes
assert "Route::post('/admin/accounts/{user}/reactivate'" not in routes


# Requiring actor/target attribution must serialize writers and fail closed
# rather than silently fabricating or deleting historical audit identities.
identity_migration = (ROOT / "backend/src/database/migrations/2026_11_23_000012_require_local_lifecycle_audit_identities.php").read_text(encoding="utf-8")
lock_position = identity_migration.index("LOCK TABLE local_account_lifecycle_events IN ACCESS EXCLUSIVE MODE")
count_position = identity_migration.index("$unattributedEvents = DB::table")
fail_position = identity_migration.index("if ($unattributedEvents > 0)")
not_null_position = identity_migration.index("ALTER COLUMN actor_user_id SET NOT NULL")
assert lock_position < count_position < fail_position < not_null_position
assert "whereNull('actor_user_id')" in identity_migration
assert "orWhereNull('target_user_id')" in identity_migration
assert "throw new RuntimeException" in identity_migration
assert "separately approved audit-preserving process" in identity_migration
assert "DROP NOT NULL" in identity_migration



# PostgreSQL audit immutability covers row mutation and table truncation.
append_only_source = append_only_migration.lower()
assert "create or replace function tacresq_guard_local_lifecycle_event_mutation()" in append_only_source
assert "before update or delete on local_account_lifecycle_events" in append_only_source
assert "before truncate on local_account_lifecycle_events" in append_only_source
assert "for each row" in append_only_source and "for each statement" in append_only_source
assert "using errcode = '55000'" in append_only_source
assert "security definer" not in append_only_source
assert "drop trigger if exists local_lifecycle_events_no_truncate" in append_only_source



# The local audit recorder is intentionally a transaction-scoped append-only
# writer, not an authorization or account-state mutation service.
recorder_source = recorder
assert recorder_source.index("DB::connection()->transactionLevel() < 1") < recorder_source.index("insertGetId")
assert "actorUserId === $targetUserId" in recorder_source
assert "trim($reason)" in recorder_source and "mb_strlen($reason) > 500" in recorder_source
assert "self::TRANSITIONS[$action]" in recorder_source
assert "DB::table('local_account_lifecycle_events')->insertGetId" in recorder_source
assert "update(" not in recorder_source.lower() and "delete(" not in recorder_source.lower()



# Privileged local Sanctum actions must re-read and lock the current Admin
# state before password/TOTP verification, with bounded failure throttling.
reauth_source = admin_reauth
lock_position = reauth_source.index("User::whereKey($user->getAuthIdentifier())->lockForUpdate()->first()")
role_position = reauth_source.index("$lockedUser->role !== 'admin'")
suspension_position = reauth_source.index("$lockedUser->is_suspended")
password_position = reauth_source.index("Hash::check($credentials['current_password'], $lockedUser->password)")
totp_position = reauth_source.index("verifyAndConsume($lockedUser, $credentials['totp_code'])")
assert lock_position < role_position < suspension_position < password_position < totp_position
assert "RateLimiter::tooManyAttempts($key, 5)" in reauth_source
assert "RateLimiter::hit($key, 900)" in reauth_source
assert "Cache-Control', 'no-store, private'" in reauth_source
assert "Retry-After" in reauth_source



# Suspended local Sanctum requests must fail closed without caching a
# potentially sensitive account-state response.
middleware_source = middleware
assert middleware_source.index("if ($user !== null && (bool) $user->is_suspended)") < middleware_source.index("return $next($request)")
assert "], 403)->header('Cache-Control', 'no-store, private')" in middleware_source
assert "contact an administrator" in middleware_source.lower()
assert "withoutMiddleware('account.active')" in routes


# Malformed Admin step-up payloads are also counted toward the same bounded
# failure window and return non-cacheable validation/lockout responses.
assert "use Illuminate\\Validation\\ValidationException;" in admin_reauth
assert "catch (ValidationException $exception)" in admin_reauth
validation_catch = admin_reauth[admin_reauth.index("catch (ValidationException $exception)"):admin_reauth.index("$authorized =")]
assert "RateLimiter::hit($key, 900)" in validation_catch
assert "RateLimiter::tooManyAttempts($key, 5)" in validation_catch
assert "Cache-Control', 'no-store, private'" in validation_catch
assert "Retry-After" in validation_catch
assert "test_malformed_local_admin_step_up_attempts_are_non_cacheable_and_rate_limited" in feature
assert "test_successful_local_admin_step_up_clears_malformed_attempt_throttle" in feature

assert "use Illuminate\\Support\\Facades\\RateLimiter;" in feature
assert "RateLimiter::hit($key, 900)" in feature
assert "->assertTooManyRequests()" in feature

assert "test_missing_local_admin_step_up_fields_are_counted_as_failures" in feature
assert "per-account-and-client-IP five-attempt/900-second throttle" in (ROOT / "docs/security/legacy-sanctum-account-lifecycle-boundary.md").read_text()
