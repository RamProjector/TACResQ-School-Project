#!/usr/bin/env python3
"""Keep the local Laravel Sanctum identity boundary explicit and fail closed against accidental email linking."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
routes = (ROOT / "backend/src/routes/api.php").read_text(encoding="utf-8")
user_model = (ROOT / "backend/src/app/Models/User.php").read_text(encoding="utf-8")
initial_migration = (ROOT / "backend/src/database/migrations/2026_10_16_000001_create_tacresq_tables.php").read_text(encoding="utf-8")
inventory = (ROOT / "docs/security/legacy-sanctum-account-lifecycle-boundary.md").read_text(encoding="utf-8")

group_start = routes.index("Route::middleware(['auth:sanctum', 'account.active'])->group")
group_end = routes.index("\n});", group_start) + len("\n});")
sanctum_group = routes[group_start:group_end]
expected_routes = (
    "Route::post('/logout'",
    "Route::post('/logout-all'",
    "Route::post('/change-password'",
    "Route::get('/me'",
    "Route::patch('/me/duty-status'",
    "Route::get('/incidents/mine'",
    "Route::post('/incidents'",
    "Route::patch('/incidents/{incident}/status'",
    "Route::get('/units'",
    "Route::post('/incidents/{incident}/assign'",
)
for route in expected_routes:
    assert route in sanctum_group, f"Expected local Sanctum route missing from authenticated group: {route}"

# The bridge must stay outside the local Sanctum group and use its own Supabase bearer preflight.
assert routes.index("Route::get('/bridge/me'") > group_end
assert routes.index("Route::post('/bridge/rpc/{function}'") > group_end
assert "auth:sanctum" not in routes[routes.index("Route::get('/bridge/me'"):routes.index("Route::post('/geoapify-smoke-test'")]
assert "$bridge->callRpc($token, 'api_me', [])" in routes

# Prevent accidental provider-identity coupling without an approved immutable link flow.
for source_name, source in (("User model", user_model), ("initial users migration", initial_migration)):
    lowered = source.lower()
    for forbidden in ("auth_user_id", "supabase_user_id", "supabase_auth_id"):
        assert forbidden not in lowered, f"{source_name} unexpectedly introduced {forbidden}; require an explicit identity-linking review"
assert "LOWER(email)" in routes  # local email normalization is allowed for local login/registration
assert "protected $fillable = ['name', 'email', 'phone', 'password', 'role', 'duty_status'];" in user_model
assert "'role' => 'requester'" in routes
assert "do not" in inventory.lower() and "email" in inventory.lower()
assert "Missing:" in inventory and "local suspension state" in inventory
print("Laravel Sanctum identity-boundary contract passed.")
