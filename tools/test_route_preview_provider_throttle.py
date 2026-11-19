#!/usr/bin/env python3
"""Static contract for bounded authenticated route-provider discovery."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/src/routes/api.php").read_text()
START = SOURCE.index("Route::get('/route-preview/provider'")
END = SOURCE.index("Route::post('/route-preview'", START)
PROVIDER = SOURCE[START:END]

REQUIRED = (
    "route-preview-provider-user:",
    "hash('sha256', (string) $authUser['id'])",
    "RateLimiter::tooManyAttempts($providerRateKey, 30)",
    "RateLimiter::availableIn($providerRateKey)",
    "RateLimiter::hit($providerRateKey, 60)",
    "->header('Retry-After'",
    "->header('Cache-Control', 'no-store, private')",
    "$bridge->callRpc($token, 'api_me', [])",
)
for fragment in REQUIRED:
    assert fragment in PROVIDER, f"Missing provider-discovery throttle contract: {fragment}"

assert PROVIDER.index("RateLimiter::tooManyAttempts") < PROVIDER.index("$bridge->callRpc"), (
    "Provider discovery must be throttled before its additional Supabase RPC."
)
assert PROVIDER.index("RateLimiter::hit") < PROVIDER.index("$bridge->callRpc"), (
    "Provider discovery must consume its rate-limit budget before its additional Supabase RPC."
)
assert "Route::get('/route-preview/provider'" in SOURCE
assert "RateLimiter::tooManyAttempts($userRateKey, 10)" in SOURCE, (
    "The stricter route-generation POST throttle must remain intact."
)

print("PASS: provider discovery is limited to 30 requests per verified account per minute.")
print("PASS: the throttle is checked and consumed before the extra Supabase RPC.")
print("PASS: 429 responses include Retry-After and are non-cacheable.")
print("PASS: the existing stricter route-generation POST throttle remains intact.")
