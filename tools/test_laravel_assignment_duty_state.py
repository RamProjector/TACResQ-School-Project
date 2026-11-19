#!/usr/bin/env python3
"""Regression contract for Laravel dispatcher assignment duty-state visibility."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
api = (ROOT / "backend/src/routes/api.php").read_text()
migration = (ROOT / "backend/src/database/migrations/2026_10_24_000004_create_owned_backend_workflow_tables.php").read_text()

start = api.index("Route::post('/incidents/{incident}/assign'")
end = api.index("\n    });", start)
route = api[start:end]

required = (
    "$onlyRoles($request, ['dispatcher'])",
    "Incident::whereKey($incident->id)->lockForUpdate()->firstOrFail()",
    "$unit = User::whereKey($data['user_id'])->lockForUpdate()->firstOrFail()",
    "$unit->role, ['responder', 'driver']",
    "$unit->duty_status === 'available'",
    "->from('incident_assignments')",
    "->where('incident_assignments.user_id', $unit->id)",
    "$lockedIncident->update(['assigned_to' => $unit->id, 'status' => 'assigned'])",
    "DB::table('incident_assignments')->insert([",
    "'responder_status' => 'assigned'",
    "$unit->update(['duty_status' => 'assigned'])",
)
for fragment in required:
    assert fragment in route, f"Missing Laravel assignment invariant: {fragment}"

assert route.index("$unit->duty_status === 'available'") < route.index("DB::table('incident_assignments')->insert([")
assert route.index("$lockedIncident->update") < route.index("DB::table('incident_assignments')->insert([")
assert "$unit->update(['duty_status' => 'available'])" not in route
for column in ("'responder_status'", "'status_updated_at'", "'assigned_by'", "'assigned_at'"):
    assert column in migration, f"Assignment mirror migration missing column: {column}"

mine_start = api.index("Route::get('/incidents/mine'")
mine_end = api.index("\n    });", mine_start)
mine_route = api[mine_start:mine_end]
assert "->orWhereExists(function ($assignment)" in mine_route
assert "->from('incident_assignments')" in mine_route
assert "->where('incident_assignments.user_id', $user->id)" in mine_route
assert "->whereColumn('incident_assignments.incident_id', 'incidents.id')" in mine_route
assert "->where('assigned_to', $user->id)" in mine_route
duty_start = api.index("Route::patch('/me/duty-status'")
duty_end = api.index("\n    });", duty_start)
duty_route = api[duty_start:duty_end]
assert "->orWhereExists(function ($assignment)" in duty_route
assert "->from('incident_assignments')" in duty_route

status_start = api.index("Route::patch('/incidents/{incident}/status'")
status_end = api.index("\n    });", status_start)
status_route = api[status_start:status_end]
assert "->where('incident_id', $locked->id)" in status_route
assert "'responder_status' => $data['status']" in status_route
assert "'status_updated_at' => now()" in status_route
assert "->pluck('user_id')" in status_route
assert "$data['status'] === 'resolved' ? 'available' : $data['status']" in status_route

print("PASS: Laravel assignment writes a durable incident_assignments membership mirror.")
print("PASS: busy-unit and self-duty checks include team membership, not only legacy lead assignment.")
print("PASS: responder and driver personal incident lists include every assigned team member.")
print("PASS: dispatcher progress mirrors status and duty state to every assigned team member.")
