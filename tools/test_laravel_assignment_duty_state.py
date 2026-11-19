#!/usr/bin/env python3
"""Regression contract for Laravel dispatcher assignment duty-state visibility."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
api = (ROOT / "backend/src/routes/api.php").read_text()

start = api.index("Route::post('/incidents/{incident}/assign'")
end = api.index("\n    });", start)
route = api[start:end]

required = (
    "$onlyRoles($request, ['dispatcher'])",
    "Incident::whereKey($incident->id)->lockForUpdate()->firstOrFail()",
    "$unit = User::whereKey($data['user_id'])->lockForUpdate()->firstOrFail()",
    "$unit->role, ['responder', 'driver']",
    "$unit->duty_status === 'available'",
    "$busy = Incident::where('assigned_to', $unit->id)",
    "$lockedIncident->update(['assigned_to' => $unit->id, 'status' => 'assigned'])",
    "$unit->update(['duty_status' => 'assigned'])",
)
for fragment in required:
    assert fragment in route, f"Missing Laravel assignment invariant: {fragment}"

assert route.index("$unit->duty_status === 'available'") < route.index("$unit->update(['duty_status' => 'assigned'])")
assert route.index("$lockedIncident->update") < route.index("$unit->update(['duty_status' => 'assigned'])")
assert "$unit->update(['duty_status' => 'available'])" not in route, (
    "An assigned unit must not remain visible as available."
)

# Progress mirrors incident milestones; resolution returns the unit to available.
status_start = api.index("Route::patch('/incidents/{incident}/status'")
status_end = api.index("\n    });", status_start)
status_route = api[status_start:status_end]
assert "'duty_status' => $data['status'] === 'resolved' ? 'available' : $data['status']" in status_route

print("PASS: Laravel assignment atomically removes the selected unit from the available pool.")
print("PASS: dispatcher progress mirrors active milestones and returns the unit to available on resolution.")
