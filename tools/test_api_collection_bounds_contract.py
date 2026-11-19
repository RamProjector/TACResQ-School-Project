#!/usr/bin/env python3
"""Static contract for bounded, backward-compatible Laravel collection endpoints."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/src/routes/api.php").read_text()


def route_block(path: str, next_path: str) -> str:
    start = SOURCE.index(path)
    end = SOURCE.index(next_path, start)
    return SOURCE[start:end]


routes = {
    "personal incident history": route_block(
        "Route::get('/incidents/mine'",
        "Route::post('/incidents'",
    ),
    "dispatcher incident queue": route_block(
        "Route::get('/incidents'",
        "Route::get('/units'",
    ),
    "dispatcher unit list": route_block(
        "Route::get('/units'",
        "Route::post('/incidents/{incident}/assign'",
    ),
}

for name, block in routes.items():
    assert "'limit' => ['sometimes', 'integer', 'min:1', 'max:100']" in block, (
        f"{name} must validate an optional limit between 1 and 100"
    )
    assert "$validated['limit'] ?? 100" in block, f"{name} must default to a bounded limit"
    assert ".limit($limit)->get()" in block, f"{name} must apply the SQL limit before retrieval"
    assert "->middleware('throttle:120,1')" in block, f"{name} must have a per-user request ceiling"
    assert "response()->json(" in block and "->values()" in block, (
        f"{name} must preserve the existing JSON array response shape"
    )

assert "Incident::latest()->get()" not in SOURCE, "Unbounded dispatcher incident collection remains"
assert "orderBy('name')->get()" not in SOURCE, "Unbounded dispatcher unit collection remains"
assert "$query->latest()->get()" not in SOURCE, "Unbounded personal incident collection remains"

print("PASS: all three Laravel collection endpoints validate a maximum limit of 100.")
print("PASS: SQL limits are applied before materializing response collections.")
print("PASS: authenticated collection endpoints are throttled at 120 requests per minute.")
print("PASS: existing JSON array response shapes remain compatible with current clients.")
