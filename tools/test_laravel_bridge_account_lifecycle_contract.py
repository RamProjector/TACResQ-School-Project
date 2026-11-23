#!/usr/bin/env python3
"""Source contract for suspension enforcement at the Laravel Supabase bridge."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
routes = (ROOT / "backend/src/routes/api.php").read_text(encoding="utf-8")
tests = (ROOT / "backend/src/tests/Feature/SupabaseBridgeTest.php").read_text(encoding="utf-8")
audit = (ROOT / "backend/src/app/ApiOperationAuditLogger.php").read_text(encoding="utf-8")

def route_block(start: str, end: str) -> str:
    left = routes.index(start)
    right = routes.index(end, left)
    return routes[left:right]

for start, end in (
    ("Route::get('/bridge/me'", "Route::post('/bridge/rpc"),
    ("Route::post('/bridge/rpc", "// One-off deployment diagnostic"),
):
    block = route_block(start, end)
    auth = block.index("$bridge->getUser($token)")
    rate = block.index("$userRateKey =")
    profile = block.index("$bridge->callRpc($token, 'api_me', [])")
    deny = block.index("'active_profile_required'")
    forwarded = block.find("$bridge->callRpc($token, $function, $arguments)")
    assert auth < rate < profile < deny, f"{start}: auth, rate limit, profile gate and denial audit order changed"
    assert forwarded == -1 or deny < forwarded, f"{start}: protected RPC can run before active-profile denial"
    assert "An active TACResQ profile is required." in block
    assert "Supabase is temporarily unavailable. Please retry." in block
    assert "app(\\App\\ApiOperationAuditLogger::class)->record(" in block

assert routes.count("$bridge->callRpc($token, 'api_me', [])") == 2
assert "'Authorization' => 'Bearer '.$accessToken" in (ROOT / "backend/src/app/SupabaseBridge.php").read_text(encoding="utf-8")
assert "'bridge.active_profile'" in routes
assert "actor_auth_user_id" in audit
assert "test_suspended_or_unprovisioned_profile_is_denied_before_bridge_rpc" in tests
assert "test_bridge_me_does_not_return_auth_identity_without_active_profile" in tests
assert "test_active_profile_preflight_outage_fails_closed_as_502" in tests
assert "Http::assertNotSent" in tests
print("Laravel Supabase bridge account-lifecycle contract passed.")
