#!/usr/bin/env python3
"""Guard deliberate confirmation for responder status transitions."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text(encoding="utf-8")

assert "var pendingStatusChange by remember { mutableStateOf<Pair<Incident, IncidentStatus>?>(null) }" in source
assert "pendingStatusChange = incident to next" in source
assert "if (next == IncidentStatus.EN_ROUTE)" in source

start = source.index("pendingStatusChange?.let { (incident, nextStatus) ->")
end = source.index("responseToStart?.let { incident ->", start)
dialog = source[start:end]
assert 'Text(if (resolving) "Confirm response completion?" else "Confirm status update?")' in dialog
assert "Only mark this response resolved when your assigned response is complete." in dialog
assert "This unit status is shared with dispatch and the requester." in dialog
assert "vm.advance(incident, nextStatus)" in dialog
assert 'Text(if (resolving) "Mark resolved" else nextStatus.actionLabel())' in dialog
assert 'Text("Keep current status")' in dialog
assert "enabled = !state.busy" in dialog

# The only status advance from the UI must happen after explicit confirmation.
assert source.count("vm.advance(incident, next)") == 0
assert source.count("vm.advance(incident, nextStatus)") == 1

print("Responder status changes require confirmation, with a distinct resolution warning.")
