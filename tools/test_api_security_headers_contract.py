#!/usr/bin/env python3
"""Static contract for non-cacheable, privacy-safe Laravel API responses."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOOTSTRAP = (ROOT / "backend/src/bootstrap/app.php").read_text()
MIDDLEWARE = (ROOT / "backend/src/app/Http/Middleware/ApiSecurityHeaders.php").read_text()

assert "use App\\Http\\Middleware\\ApiSecurityHeaders;" in BOOTSTRAP
assert "$middleware->append(ApiSecurityHeaders::class);" in BOOTSTRAP

for required in (
    "$request->is('api/*')",
    "'Cache-Control', 'no-store, private'",
    "'Pragma', 'no-cache'",
    "'Expires', '0'",
    "'X-Content-Type-Options', 'nosniff'",
    "'Referrer-Policy', 'no-referrer'",
):
    assert required in MIDDLEWARE, f"Missing API response protection: {required}"

assert MIDDLEWARE.index("$response = $next($request);") < MIDDLEWARE.index("$request->is('api/*')")
assert "Authorization" not in MIDDLEWARE
assert "Set-Cookie" not in MIDDLEWARE
assert "Content-Security-Policy" not in MIDDLEWARE

print("PASS: Laravel API responses carrying account, incident, assignment, or location data are non-cacheable.")
print("PASS: API responses include MIME-sniffing and referrer protections.")
print("PASS: non-API portal responses are not modified by this middleware.")
