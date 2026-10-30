from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()

assert "if (state.syncError != null) item { SyncStatus(state.lastSyncedAtEpochMs, state.syncError) }" in source
assert "canChange = active.isEmpty() && !state.loading && !state.refreshing && state.syncError == null" in source
assert 'state.syncError != null -> "Could not verify assignments. Refresh before changing duty status."' in source

start = source.index("// A failed sync is already explained by SyncStatus")
end = source.index("items(active, key = { it.id })", start)
empty_state = source[start:end]
assert "state.syncError == null && state.dutyStatus == DutyStatus.AVAILABLE" in empty_state
assert "Assignments unavailable" not in empty_state
assert "Refresh before assuming there are no active calls." not in empty_state

print("Responder sync feedback hierarchy contract passed.")
