#!/usr/bin/env python3
"""Lock the local Sanctum account-state migration and request guard boundary."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "backend/src/database/migrations/2026_11_23_000007_add_local_sanctum_account_lifecycle_state.php").read_text()
model = (ROOT / "backend/src/app/Models/User.php").read_text()
middleware = (ROOT / "backend/src/app/Http/Middleware/EnsureLocalAccountIsActive.php").read_text()
routes = (ROOT / "backend/src/routes/api.php").read_text()
bootstrap = (ROOT / "backend/src/bootstrap/app.php").read_text()
feature = (ROOT / "backend/src/tests/Feature/LocalSanctumAccountLifecycleTest.php").read_text()

assert "boolean('is_suspended')->default(false)" in migration
assert "timestampTz('suspended_at')->nullable()" in migration
assert "string('suspension_reason', 500)->nullable()" in migration
assert "'is_suspended' => 'boolean'" in model
assert "'is_suspended'" not in model.split("protected $fillable", 1)[1].split(";", 1)[0], "Public mass assignment must not control suspension"
assert "EnsureLocalAccountIsActive::class" in bootstrap
assert "Route::middleware(['auth:sanctum', 'account.active'])->group" in routes
assert "->withoutMiddleware('account.active')" in routes, "Self-service logout routes must remain available"
assert "if ($user !== null && (bool) $user->is_suspended)" in middleware
assert "403" in middleware and "no-store, private" in middleware
assert "test_existing_local_accounts_remain_active_by_default" in feature
assert "test_suspended_local_account_cannot_access_protected_sanctum_routes" in feature
assert "test_suspended_local_account_can_still_revoke_its_current_token" in feature
assert "email" not in migration.lower(), "Do not add cross-provider identity mapping"
print("Local Sanctum lifecycle guard contract passed.")
