#!/usr/bin/env python3
"""Ensure sensitive Laravel mutations have account-aware throttling without throttling SOS submission."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/src/routes/api.php").read_text()

def route_block(start: str, end: str) -> str:
    first = SOURCE.index(start)
    last = SOURCE.index(end, first + len(start))
    return SOURCE[first:last]

duty = route_block("Route::patch('/me/duty-status'", "Route::get('/incidents/mine'")
status = route_block("Route::patch('/incidents/{incident}/status'", "Route::get('/incidents'")
assignment = route_block("Route::post('/incidents/{incident}/assign'", "\n});\n\n// Transitional Render")
sos = route_block("Route::post('/incidents'", "Route::patch('/incidents/{incident}/status'")

for name, block in (
    ("field-unit duty status", duty),
    ("dispatcher incident progress", status),
    ("dispatcher incident assignment", assignment),
):
    assert "->middleware('throttle:30,1')" in block, (
        f"{name} must have a 30-per-minute authenticated mutation ceiling"
    )

assert "throttle:" not in sos, (
    "Do not add a restrictive generic throttle to emergency submission; SOS availability takes priority"
)

print("PASS: duty-status, incident-progress, and assignment mutations are throttled.")
print("PASS: emergency incident submission remains outside the generic mutation throttle.")
