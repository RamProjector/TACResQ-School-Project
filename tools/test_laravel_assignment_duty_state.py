#!/usr/bin/env python3
"""Regression contract for Laravel dispatcher assignment duty-state visibility."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
api = (ROOT / "backend/src/routes/api.php").read_text()
migration = (ROOT / "backend/src/database/migrations/2026_10_24_000004_create_owned_backend_workflow_tables.php").read_text()
incident_model = (ROOT / "backend/src/app/Models/Incident.php").read_text()
user_model = (ROOT / "backend/src/app/Models/User.php").read_text()


def route_block(marker: str) -> str:
    """Extract a route through its next declaration, independent of closure style."""
    start = api.index(marker)
    end = api.find("\n    Route::", start + len(marker))
    return api[start:end if end >= 0 else len(api)]


route = route_block("Route::post('/incidents/{incident}/assign'")
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

mine_route = route_block("Route::get('/incidents/mine'")
assert "->orWhereExists(function ($assignment)" in mine_route
assert "->from('incident_assignments')" in mine_route
assert "->where('incident_assignments.user_id', $user->id)" in mine_route
assert "->whereColumn('incident_assignments.incident_id', 'incidents.id')" in mine_route
assert "->where('assigned_to', $user->id)" in mine_route
assert "AS assignment_role" in mine_route
assert "CASE WHEN incidents.assigned_to = ? THEN 'lead' ELSE 'team' END" in mine_route
assert "$payload['assignment_role'] = $incident->assignment_role" in mine_route
assert "->map(" in mine_route

duty_route = route_block("Route::patch('/me/duty-status'")
assert "DB::transaction(function () use ($request, $data)" in duty_route
assert "User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail()" in duty_route
assert "->orWhereExists(function ($assignment)" in duty_route
assert "->from('incident_assignments')" in duty_route
assert duty_route.index("lockForUpdate()") < duty_route.index("->whereIn('status', ['assigned', 'en_route', 'on_scene', 'transporting'])->exists()")
assert "$unit->update($data)" in duty_route

status_route = route_block("Route::patch('/incidents/{incident}/status'")
assert "->where('incident_id', $locked->id)" in status_route
assert "'responder_status' => $data['status']" in status_route
assert "'status_updated_at' => now()" in status_route
assert "->pluck('user_id')" in status_route
assert re.search(
    r"User::whereIn\('id', \$assignedUserIds\)\s*"
    r"->orderBy\('id'\)\s*->lockForUpdate\(\)\s*->get\(\)",
    status_route,
), "Assigned team rows must be locked in deterministic ID order before duty updates"
assert "$data['status'] === 'resolved' ? 'available' : $data['status']" in status_route

assert "function responseIncidents()" in user_model
assert "belongsToMany(Incident::class, 'incident_assignments')" in user_model
for pivot_field in ("assigned_by", "assigned_at", "responder_status", "status_updated_at"):
    assert f"'{pivot_field}'" in user_model, f"User response-incidents relation missing pivot field: {pivot_field}"
assert "function responseTeam()" in incident_model
assert "belongsToMany(User::class, 'incident_assignments')" in incident_model
for pivot_field in ("assigned_by", "assigned_at", "responder_status", "status_updated_at"):
    assert f"'{pivot_field}'" in incident_model, f"Response-team relation missing pivot field: {pivot_field}"

print("PASS: Laravel assignment writes a durable incident_assignments membership mirror.")
print("PASS: busy-unit checks include team membership, not only legacy lead assignment.")
print("PASS: duty changes lock the unit row and serialize against dispatcher assignment.")
print("PASS: responder and driver personal incident lists include every assigned team member.")
print("PASS: dispatcher progress mirrors status and duty state to every assigned team member.")
