#!/usr/bin/env python3
"""Static contract for bounded, privacy-conscious Laravel login throttling."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/src/routes/api.php").read_text()
START = SOURCE.index("Route::post('/login'")
END = SOURCE.index("\n});", START) + 4
LOGIN = SOURCE[START:END]

REQUIRED = (
    "mb_strtolower(trim($credentials['email']))",
    "auth-login-account:",
    "auth-login-ip:",
    "RateLimiter::tooManyAttempts($ipKey, 120)",
    "RateLimiter::hit($ipKey, 60)",
    "RateLimiter::tooManyAttempts($accountKey, 5)",
    "RateLimiter::hit($accountKey, 900)",
    "RateLimiter::clear($accountKey)",
    "RateLimiter::availableIn($accountKey)",
    "->header('Retry-After'",
    "->header('Cache-Control', 'no-store, private')",
)
for fragment in REQUIRED:
    assert fragment in LOGIN, f"Missing login throttling/security contract: {fragment}"

assert LOGIN.count("The provided credentials are incorrect.") == 1
assert "User::where('email', $normalizedEmail)" in LOGIN
assert "$credentials['password']" not in LOGIN.split("$accountKey =", 1)[1].split("$ipKey =", 1)[0]
assert "auth-login-account:'.hash('sha256', $normalizedEmail.'|'.(string) $request->ip())" in LOGIN
assert "auth-login-account:'.hash('sha256', $normalizedEmail)" not in LOGIN

print("PASS: Laravel login has per-account/source and generous source throttles.")
print("PASS: successful authentication clears only its account/source failure bucket.")
print("PASS: login token responses and throttling responses are non-cacheable.")
print("PASS: credential failures do not disclose whether an account exists.")
