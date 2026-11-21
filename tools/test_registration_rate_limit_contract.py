#!/usr/bin/env python3
"""Static contract for safe, bounded public TACResQ registration."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/src/routes/api.php").read_text()
START = SOURCE.index("Route::post('/register'")
END = SOURCE.index("\n});", START) + 4
REGISTER = SOURCE[START:END]

REQUIRED = (
    "auth-register-ip:",
    "RateLimiter::tooManyAttempts($registrationKey, 10)",
    "RateLimiter::hit($registrationKey, 900)",
    "RateLimiter::availableIn($registrationKey)",
    "->header('Retry-After'",
    "->header('Cache-Control', 'no-store, private')",
    "mb_strtolower(trim($data['email']))",
    "User::whereRaw('LOWER(email) = ?', [$data['email']])->exists()",
    "Unable to create an account with these details.",
    "'role' => 'requester'",
    "'duty_status' => null",
    "->header('Cache-Control', 'no-store, private');",
)
for fragment in REQUIRED:
    assert fragment in REGISTER, f"Missing registration security contract: {fragment}"

assert REGISTER.index("RateLimiter::tooManyAttempts") < REGISTER.index("$request->validate(")
assert "unique:users,email" not in REGISTER
assert "createToken('android')->plainTextToken" in REGISTER
assert REGISTER.count("RateLimiter::hit($registrationKey, 900)") == 1

print("PASS: public registration is rate-limited before validation/database work.")
print("PASS: registration normalizes email and checks case-insensitive duplicates.")
print("PASS: public signup always creates requester accounts only.")
print("PASS: registration responses containing tokens are non-cacheable.")
