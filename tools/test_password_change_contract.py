#!/usr/bin/env python3
"""Static contract for authenticated password changes and Sanctum token rotation."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/src/routes/api.php").read_text()
AUTH_START = SOURCE.index("Route::middleware(['auth:sanctum', 'account.active'])->group")
AUTH_END = SOURCE.index("\n});", AUTH_START) + 4
AUTH = SOURCE[AUTH_START:AUTH_END]
START = AUTH.index("Route::post('/change-password'")
END = AUTH.index("\n    });", START) + 7
CHANGE = AUTH[START:END]

REQUIRED = (
    "'current_password' => ['required', 'string', 'max:72']",
    "'password' => ['required', 'string', 'min:12', 'max:72', 'confirmed']",
    "auth-password-change:",
    "RateLimiter::tooManyAttempts($attemptKey, 5)",
    "RateLimiter::hit($attemptKey, 900)",
    "RateLimiter::availableIn($attemptKey)",
    "Hash::check($data['current_password'], $user->password)",
    "Hash::check($data['password'], $user->password)",
    "$user->forceFill(['password' => Hash::make($data['password'])])->save()",
    "$user->tokens()->delete()",
    "$user->createToken('android')->plainTextToken",
    "RateLimiter::clear($attemptKey)",
    "->header('Cache-Control', 'no-store, private')",
)
for fragment in REQUIRED:
    assert fragment in CHANGE, f"Missing password-change security contract: {fragment}"

assert CHANGE.index("RateLimiter::tooManyAttempts") < CHANGE.index("Hash::check($data['current_password']")
assert CHANGE.index("$user->tokens()->delete()") < CHANGE.index("$user->createToken('android')")
assert "Route::post('/change-password'" in AUTH
assert "Route::middleware('auth:sanctum')->group" in SOURCE[:AUTH_START + 100]
assert CHANGE.count("RateLimiter::hit($attemptKey, 900)") == 1

print("PASS: password changes require the current password and a confirmed 12-character replacement.")
print("PASS: repeated failed current-password checks are rate-limited per account.")
print("PASS: successful changes revoke all previous Sanctum tokens and issue one fresh token.")
print("PASS: password-change responses are non-cacheable.")
