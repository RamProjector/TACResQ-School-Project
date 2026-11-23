#!/usr/bin/env python3
"""Lock the local Sanctum account-state and append-only audit boundaries."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "backend/src/database/migrations/2026_11_23_000007_add_local_sanctum_account_lifecycle_state.php").read_text()
audit_migration = (ROOT / "backend/src/database/migrations/2026_11_23_000008_create_local_account_lifecycle_events.php").read_text()
append_only_migration = (ROOT / "backend/src/database/migrations/2026_11_23_000009_enforce_local_lifecycle_event_append_only.php").read_text()
model = (ROOT / "backend/src/app/Models/User.php").read_text()
middleware = (ROOT / "backend/src/app/Http/Middleware/EnsureLocalAccountIsActive.php").read_text()
routes = (ROOT / "backend/src/routes/api.php").read_text()
bootstrap = (ROOT / "backend/src/bootstrap/app.php").read_text()
feature = (ROOT / "backend/src/tests/Feature/LocalSanctumAccountLifecycleTest.php").read_text()
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
assert "nullOnDelete()" in audit_migration, "Lifecycle history must survive user deletion"
assert "CREATE TRIGGER local_lifecycle_events_append_only" in append_only_migration
assert "BEFORE UPDATE OR DELETE" in append_only_migration
assert "to_jsonb(NEW)" in append_only_migration and "to_jsonb(OLD)" in append_only_migration
assert "OLD.actor_user_id IS NOT NULL AND NEW.actor_user_id IS NULL" in append_only_migration
assert "OLD.target_user_id IS NOT NULL AND NEW.target_user_id IS NULL" in append_only_migration
assert "test_local_lifecycle_audit_schema_retains_events_after_user_deletion" in feature
assert "test_local_lifecycle_audit_event_rejects_content_updates_on_postgres" in feature
assert "test_local_lifecycle_audit_event_rejects_deletion_on_postgres" in feature
assert "final class LocalAccountLifecycleRecorder" in recorder
assert "DB::connection()->transactionLevel() < 1" in recorder, "Lifecycle writes must require an active transaction"
assert "self::TRANSITIONS[$action]" in recorder, "Only explicit lifecycle transitions may be recorded"
assert "mb_strlen($reason) > 500" in recorder, "Lifecycle reason must respect the database limit"
assert "insertGetId" in recorder and "created_at" in recorder
assert "test_lifecycle_recorder_appends_valid_transition_inside_transaction" in feature
assert "test_lifecycle_recorder_event_rolls_back_with_account_transaction" in feature
assert "test_lifecycle_recorder_rejects_invalid_transition_and_empty_reason" in feature
assert "final class EnsureLocalAdminReauthenticated" in admin_reauth
assert "$user->role !== 'admin'" in admin_reauth, "Only local Laravel Admins may pass the privileged guard"
assert "Hash::check($credentials['current_password'], $user->password)" in admin_reauth
assert "RateLimiter::tooManyAttempts($key, 5)" in admin_reauth
assert "RateLimiter::hit($key, 900)" in admin_reauth
assert "test_local_admin_sensitive_action_requires_admin_password_and_totp" in feature
assert "test_local_admin_sensitive_action_rejects_non_admin_even_with_correct_password" in feature
assert "test_local_admin_sensitive_action_rejects_incorrect_password" in feature
assert "test_local_admin_reauthentication_is_rate_limited_after_repeated_failures" in feature
assert "'totp_code'" in admin_reauth and "verifyAndConsume" in admin_reauth
assert "LocalAdminTotpService::class" in admin_reauth
assert "verifyAndConsume($user, $credentials['totp_code'])" in admin_reauth
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
print("Local Sanctum lifecycle guard and append-only audit contracts passed.")


# TOTP confirmation is a sensitive factor-binding operation: require password re-auth,
# lock and re-check the local Admin row, and never confirm for suspended/demoted users.
confirm_start = routes.index("Route::post('/confirm'")
confirm_end = routes.index("})->middleware('throttle:5,1');", confirm_start)
confirm_route = routes[confirm_start:confirm_end]
assert "current_password" in confirm_route
assert "Hash::check($data['current_password'], $lockedUser->password)" in confirm_route
assert "User::whereKey($user->id)->lockForUpdate()" in confirm_route
assert "!$lockedUser->is_suspended" in confirm_route
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
