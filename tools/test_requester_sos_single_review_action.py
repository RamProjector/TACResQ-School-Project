#!/usr/bin/env python3
"""Guard one deliberate SOS send action on the final requester review screen."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text(encoding="utf-8")

assert 'Text("Review before sending"' in source
assert 'else Text("Send SOS", fontWeight = FontWeight.Bold)' in source
assert 'onClick = { selectedType?.let { vm.sendSos(it, description, locationNotes) } }' in source
assert 'enabled = selectedType != null && requesterActionsEnabled &&' in source
assert '!state.pendingSosCheckInProgress && state.pendingSosCheckError == null' in source
assert 'This one-time pin is sent with the SOS; ongoing tracking is not.' in source
assert 'Text("Only send if help is needed now."' in source

# The final review is the single explicit confirmation. Keep draft-discard and
# other unrelated dialogs, but do not reopen a second SOS confirmation dialog.
assert "confirmingSos" not in source
assert 'Text("Send emergency request?")' not in source
assert 'Text("Confirm SOS"' not in source
assert source.count("vm.sendSos(it, description, locationNotes)") == 1

print("Requester SOS review has one explicit, safely gated Send SOS action.")
