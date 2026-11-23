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

assert "boolean('is_suspended')->default(false)" in migration
assert "timestampTz('suspended_at')->nullable()" in migration
assert "string('suspension_reason', 500)->nullable()" in migration
assert "'is_suspended' => 'boolean'" in model
assert "'is_suspended'" not in model.split("protected $fillable", 1)[1].split(";", 1)[0], "Public mass assignment must not control suspension"
assert "EnsureLocalAccountIsActive::class" in bootstrap
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
print("Local Sanctum lifecycle guard and append-only audit contracts passed.")
