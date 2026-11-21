#!/usr/bin/env python3
"""Regression checks: terminal incidents must never be shown as active."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()

assert "val activeIncidents = state.incidents.filter { it.status.isActive }" in screen, (
    "The active incident collection must use the shared lifecycle predicate."
)
assert "items(activeIncidents, key = { it.id })" in screen, (
    "The Active incidents section must render the filtered active collection."
)
assert "val recentClosedIncidents = state.incidents" in screen, (
    "Resolved/cancelled incidents should be handled separately from active incidents."
)
assert ".filter { it.status == IncidentStatus.RESOLVED || it.status == IncidentStatus.CANCELLED }" in screen, (
    "The closed-history collection must explicitly contain only terminal statuses."
)
assert "val active = activeIncidents.size" in screen, (
    "The overview active count must derive from the same active collection as the list."
)

print("PASS: terminal incidents are excluded from the active dispatcher list and handled as closed history.")


# Dispatcher notification targets must remain reachable in the current bounded
# all-incidents snapshot, and terminal incidents must stay read-only.
dispatcher = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
api = (ROOT / "backend/src/routes/api.php").read_text()

closed_start = dispatcher.index("if (recentClosedIncidents.isNotEmpty())")
closed_end = dispatcher.index('SectionHeading(\n                "Field units"', closed_start)
closed_history = dispatcher[closed_start:closed_end]
assert "items(recentClosedIncidents, key = { \"closed-${it.id}\" })" in closed_history
assert "StatusPill(incident.status)" in closed_history
assert "View assignment history" in closed_history
for forbidden in ("vm.advanceIncident(", "assignmentCandidate =", "releaseCandidate =", "onOpenChat("):
    assert forbidden not in closed_history, (
        f"Closed incident history must remain read-only; found active action: {forbidden}"
    )

focus_start = dispatcher.index('if (focusedIncidentId != null)')
focus_end = dispatcher.index("item {\n            SyncStatus(", focus_start)
focused_target = dispatcher[focus_start:focus_end]
assert "focusedIncident != null -> IncidentCard(focusedIncident)" in focused_target
assert "state.loading ->" in focused_target and "not in the current authorized dispatch queue" in focused_target
assert "vm.refresh(); openIncident(incidentId)" in root

route_start = api.index("Route::get('/incidents',")
route_end = api.index("Route::get('/units',", route_start)
dispatcher_route = api[route_start:route_end]
assert "$onlyRoles($request, ['dispatcher'])" in dispatcher_route
assert "Incident::orderByDesc('created_at')->orderByDesc('id')->limit($limit)->get()" in dispatcher_route
assert "whereIn('status'" not in dispatcher_route and "where('status'" not in dispatcher_route
assert "Latest 5 by request date" in dispatcher

print("PASS: dispatcher notification targets, closed history, and read-only terminal state are contract-checked.")
