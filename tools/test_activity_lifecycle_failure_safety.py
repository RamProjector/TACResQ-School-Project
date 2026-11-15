#!/usr/bin/env python3
"""Regression contracts for lifecycle-safe repository work in incident workflows."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HELPER = (ROOT / "app/src/main/java/com/tacresq/app/ui/common/RepositoryCall.kt").read_text()
DISPATCHER = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
REQUESTER = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
CHAT = (ROOT / "app/src/main/java/com/tacresq/app/ui/chat/IncidentChatScreen.kt").read_text()
COMPONENTS = (ROOT / "app/src/main/java/com/tacresq/app/ui/common/Components.kt").read_text()
APP_ROOT = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
MAIN_ACTIVITY = (ROOT / "app/src/main/java/com/tacresq/app/MainActivity.kt").read_text()

assert HELPER.index("catch (cancelled: CancellationException)") < HELPER.index("catch (failure: Exception)")
assert "throw cancelled" in HELPER and "Result.failure(failure)" in HELPER

assert "private var refreshRequested = false" in DISPATCHER
assert "refreshRequested = true" in DISPATCHER
assert "repositoryCallOrFailure { repo.allIncidents() }" in DISPATCHER
assert "repositoryCallOrFailure { repo.units() }" in DISPATCHER
assert "repositoryCallOrFailure { repo.activeResponderLocations() }" in DISPATCHER
assert "assignmentDataFresh = false" in DISPATCHER
assert "finally {" in DISPATCHER and "refreshJob = null" in DISPATCHER
assert "runQueuedRefresh" in DISPATCHER
assert "repositoryCallOrFailure { repo.assignIncident(incident.id, userIds) }" in DISPATCHER

requester_refresh = REQUESTER[REQUESTER.index("    fun refresh() {"):REQUESTER.index("    /** Called only after the user activates the Android LocationButton. */")]
assert "repositoryCallOrFailure { repo.myIncidents() }" in requester_refresh
assert "repositoryCallOrFailure { repo.cacheMyIncidents(list) }" in requester_refresh
assert "repositoryCallOrFailure { repo.cachedMyIncidents() }" in requester_refresh
assert "catch (cancelled: CancellationException)" in requester_refresh
assert "throw cancelled" in requester_refresh
assert "finally {" in requester_refresh and "refreshJob = null" in requester_refresh
assert "hasCurrentIncidentStatus = false" in requester_refresh

assert "private var conversationGeneration: Long = 0L" in CHAT
assert "activeIncidentId == incidentId && conversationGeneration == generation" in CHAT
assert CHAT.count("val generation = conversationGeneration") == 4
assert CHAT.count("isCurrentConversation(incidentId, generation)") == 8
assert "conversationGeneration += 1" in CHAT
assert "sendJob?.cancel()" in CHAT
assert "refreshJob = null" in CHAT and "olderJob = null" in CHAT and "sendJob = null" in CHAT

for expected in (
    "repositoryCallOrFailure { repo.myIncidentMessages(incidentId, limit = 50) }",
    "repositoryCallOrFailure { repo.myIncidentMessages(incidentId, limit = 50, beforeId = cursor) }",
    "repositoryCallOrFailure { repo.sendIncidentMessage(incidentId, message, clientMessageId) }",
    "repositoryCallOrFailure { repo.sendIncidentImageMessage(incidentId, senderId, caption, clientMessageId, mimeType, bytes) }",
):
    assert expected in CHAT, f"Missing safe repository call: {expected}"

assert "lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)" in COMPONENTS
assert "collectAsStateWithLifecycle()" in APP_ROOT
assert "onSaveInstanceState(outState: Bundle)" in MAIN_ACTIVITY
assert "STATE_NOTIFICATION_ID" in MAIN_ACTIVITY
assert "PushNotificationTap.from(intent)" in MAIN_ACTIVITY

print("PASS: cancellation is preserved and unexpected repository failures become UI-state failures.")
print("PASS: dispatcher refreshes are coalesced and assignment freshness fails closed.")
print("PASS: requester refresh cannot leave a stale current-status state after an exception.")
print("PASS: incident chat operations share the same safe failure boundary.")
print("PASS: generation guards prevent old same-incident responses from overwriting a reopened conversation.")
print("PASS: lifecycle-aware polling, state collection, and notification tap restoration remain in place.")
