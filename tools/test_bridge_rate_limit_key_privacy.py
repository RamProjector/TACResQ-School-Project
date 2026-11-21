#!/usr/bin/env python3
"""Ensure bridge rate-limit cache keys do not embed raw Supabase user identifiers."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/src/routes/api.php").read_text()

for route in (
    "Route::get('/bridge/me'",
    "Route::post('/bridge/rpc/{function}'",
):
    start = SOURCE.index(route)
    end = SOURCE.find("\n});", start)
    assert end != -1, f"Could not find end of {route}"
    block = SOURCE[start:end]
    assert "$userRateKey = 'bridge-user:'.hash('sha256', (string) $user['id']);" in block, (
        f"{route} must hash the verified Supabase user ID before using it in a cache key"
    )
    assert "$userRateKey = 'bridge-user:'.$user['id'];" not in block

print("PASS: both bridge endpoints hash verified account identifiers in rate-limit keys.")
