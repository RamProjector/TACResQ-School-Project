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


# The bridge must reject malformed/oversized arguments and non-allowlisted
# RPC names before authenticating or forwarding anything upstream.
rpc_route = route_block("Route::post('/bridge/rpc", "// One-off deployment diagnostic")
assert rpc_route.index("strlen($request->getContent()) > 32768") < rpc_route.index("$bridge->getUser($token)")
assert rpc_route.index("JSON_THROW_ON_ERROR") < rpc_route.index("$bridge->getUser($token)")
assert "instanceof \\stdClass" in rpc_route
assert rpc_route.index("$allowlist = config('tacru.bridge_rpc_allowlist', [])") < rpc_route.index("$bridge->getUser($token)")
assert "abort_unless(in_array($function, $allowlist, true), 404" in rpc_route

# Active-profile preflight must use the exact same verified bearer token,
# precede the requested RPC, and fail closed for missing/unprovisioned profiles.
assert rpc_route.index("$bridge->callRpc($token, 'api_me', [])") < rpc_route.index("$bridge->callRpc($token, $function, $arguments)")
assert "active_profile_required" in rpc_route
assert "is_array($profile['body'])" in rpc_route
assert "isset($profile['body']['id'])" in rpc_route
assert "LOWER(email)" not in rpc_route and "where('email'" not in rpc_route.lower(), (
    "Bridge authorization must not query or match a Supabase profile by email."
)
assert "test_suspended_or_unprovisioned_profile_is_denied_before_bridge_rpc" in tests
