#!/usr/bin/env python3
"""Guard dispatcher-owned incident progress and responder-only GPS sharing."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text(encoding="utf-8")

# Responders must not have UI or ViewModel methods that advance shared status.
for retired in (
    "pendingStatusChange",
    "responseToStart",
    "startResponse(",
    "fun advance(",
    "repo.updateIncidentStatus",
    "vm.advance(",
):
    assert retired not in source, f"Retired responder-owned status action remains: {retired}"

assert "Incident progress is maintained by dispatch." in source
assert "GPS sharing reports your location; it does not confirm arrival" in source
assert "Location sharing becomes available after dispatch records the team as en route." in source
assert 'Text("Resume location sharing")' in source
assert 'Text("Stop location sharing")' in source
assert "vm.resumeTracking(incident.id)" in source
assert "ownStatus != IncidentStatus.EN_ROUTE" in source
assert "Location sharing is allowed only for your own en-route unit status." in source

# The demo backend and Laravel prototype must enforce the same ownership model.
fake = Path("app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text(encoding="utf-8")
assert "if (me.role != Role.DISPATCHER)" in fake
assert "incident.status.nextSteps()" in fake
assert "teamIds.map { unitId ->" in fake
assert "ResponderAssignmentStatus(unitId, body.status, timestamp)" in fake

laravel = Path("backend/src/routes/api.php").read_text(encoding="utf-8")
status_route_start = laravel.index("Route::patch('/incidents/{incident}/status'")
status_route_end = laravel.index("\n    });", status_route_start)
status_route = laravel[status_route_start:status_route_end]
assert "$onlyRoles($request, ['dispatcher'])" in status_route
assert "User::whereKey($locked->assigned_to)" in status_route
assert "$locked->status" in status_route

print("Responder status ownership contract passed: dispatch advances incidents; responders may only manage optional GPS sharing.")
