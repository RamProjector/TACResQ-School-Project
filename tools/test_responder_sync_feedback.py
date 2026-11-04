from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()

assert "if (state.syncError != null) item { SyncStatus(state.lastSyncedAtEpochMs, state.syncError) }" in source
assert "canChange = active.isEmpty() && !state.loading && !state.refreshing && state.syncError == null" in source
assert 'state.syncError != null -> "Could not verify assignments. Refresh before changing duty status."' in source

start = source.index("// A failed sync is already explained by SyncStatus")
end = source.index("items(active, key = { it.id })", start)
empty_state = source[start:end]
assert "state.syncError == null && state.dutyStatus == DutyStatus.AVAILABLE" in empty_state
# Ignore explanatory comments; assert that the duplicate failure UI is absent.
assert 'Text("Assignments unavailable")' not in empty_state
assert 'Text("Refresh before assuming there are no active calls.")' not in empty_state

assert '"Location sharing",' in source
assert 'state.trackingError != null -> "Needs attention"' in source
assert 'sharing && state.lastLocationSentAt == null -> "Starting"' in source
assert 'sharing -> "Enabled"' in source
assert 'else -> "Off"' in source
assert "GPS sharing reports your location; it does not confirm arrival." in source
assert '"Last sent $time' in source
assert 'Text("Stop location sharing")' in source

print("Responder sync feedback hierarchy contract passed.")
