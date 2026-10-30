from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()

# A failed local pending-SOS lookup must never be treated as proof that no
# earlier SOS exists. The requester can send only after a successful check.
assert "val pendingSosCheckInProgress: Boolean = true" in source
assert "val pendingSosCheckError: String? = null" in source
assert "private var pendingSosCheckJob: Job? = null" in source

refresh_start = source.index("fun refreshPendingSos()")
refresh_end = source.index("\n    fun retryPendingSos()", refresh_start)
refresh = source[refresh_start:refresh_end]
assert "pendingSosCheckJob?.isActive == true" in refresh
assert "repo.hasPendingIncident()" in refresh
assert "pendingSosAvailable = pending" in refresh
assert "pendingSosCheckInProgress = false" in refresh
assert "pendingSosCheckError = null" in refresh
assert "catch (e: CancellationException)" in refresh
assert "throw e" in refresh
assert "pendingSosCheckError = \"TACResQ couldn't verify" in refresh

send_start = source.index("fun sendSos(")
send_end = source.index("\n    private fun locationResponseMatches", send_start)
send = source[send_start:send_end]
assert "current.pendingSosCheckInProgress || current.pendingSosCheckError != null" in send
assert "if (current.pendingSosAvailable)" in send
assert "repo.createIncident" in send

assert 'Text("Checking saved SOS status",' in source
assert source.count("semantics { liveRegion = LiveRegionMode.Polite }") >= 2
assert "Text(\"Couldn't verify saved SOS status\", style = MaterialTheme.typography.titleSmall" in source
assert "onClick = vm::refreshPendingSos" in source
assert 'Text("Check again")' in source
assert "!state.pendingSosCheckInProgress && state.pendingSosCheckError == null" in source
assert "!state.pendingSosCheckInProgress && state.pendingSosCheckError == null," in source

print("Pending SOS verification state contract passed.")
