#!/usr/bin/env python3
"""Static contract for authenticated Laravel API-token revocation."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/src/routes/api.php").read_text()
AUTH_START = SOURCE.index("Route::middleware(['auth:sanctum', 'account.active'])->group")
AUTH_END = SOURCE.index("\n});", AUTH_START) + 4
AUTH = SOURCE[AUTH_START:AUTH_END]

assert "Route::post('/logout'," in AUTH
assert "currentAccessToken()?->delete()" in AUTH
assert "Route::post('/logout-all'" in AUTH
assert "$request->user()->tokens()->delete();" in AUTH
assert "All Laravel API tokens have been revoked." in AUTH
assert "->header('Cache-Control', 'no-store, private')" in AUTH
assert AUTH.index("Route::post('/logout-all'") > AUTH.index("Route::post('/logout'")

# The broad revocation endpoint must remain inside the authenticated route group.
assert AUTH_START >= 0 and "auth:sanctum" in SOURCE[AUTH_START:AUTH_START + 100]
print("PASS: logout revokes only the current Sanctum token.")
print("PASS: authenticated logout-all revokes every personal access token.")
print("PASS: token-revocation responses are non-cacheable.")
