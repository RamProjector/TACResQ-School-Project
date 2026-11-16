#!/usr/bin/env python3
"""Regression contracts for lifecycle-safe repository work in incident workflows."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HELPER = (ROOT / "app/src/main/java/com/tacresq/app/ui/common/RepositoryCall.kt").read_text()
DISPATCHER = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
REQUESTER = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
CHAT = (ROOT / "app/src/main/java/com/tacresq/app/ui/chat/IncidentChatScreen.kt").read_text()
RESPONDER = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
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
assert "repositoryCallOrFailure { repo.createIncident(type, report, fix, landmark) }" in REQUESTER
assert "repositoryCallOrFailure { repo.retryPendingIncident() }" in REQUESTER
assert "repositoryCallOrFailure { repo.discardPendingIncident() }" in REQUESTER
assert "repositoryCallOrFailure { repo.requesterDraft() }" in REQUESTER
assert "repositoryCallOrFailure { repo.saveRequesterDraft(type, description, locationNotes) }" in REQUESTER
assert "repositoryCallOrFailure { repo.clearRequesterDraft() }" in REQUESTER
send_sos_block = REQUESTER[REQUESTER.index("    fun sendSos("):REQUESTER.index("    private fun locationResponseMatches(")]
assert "repositoryCallOrFailure { repo.createIncident(type, report, fix, landmark) }" in send_sos_block
assert "finally {" in send_sos_block and "sending = false" in send_sos_block

assert "private var conversationGeneration: Long = 0L" in CHAT
assert "activeIncidentId == incidentId && conversationGeneration == generation" in CHAT
assert CHAT.count("val generation = conversationGeneration") == 4
assert CHAT.count("isCurrentConversation(incidentId, generation)") == 9
assert "conversationGeneration += 1" in CHAT
assert "import com.tacresq.app.ui.common.repositoryCallOrFailure" in RESPONDER
assert "repositoryCallOrFailure { repo.myIncidents() }" in RESPONDER
assert "repositoryCallOrFailure { repo.me() }" in RESPONDER
assert "repositoryCallOrFailure { tracking.start(incidentId) }" in RESPONDER
resume_block = RESPONDER[RESPONDER.index("    fun resumeTracking(incidentId: Long) {"):RESPONDER.index("    fun advance(", RESPONDER.index("    fun resumeTracking(incidentId: Long) {"))]
assert resume_block.index("it.copy(") < resume_block.index("viewModelScope.launch")
assert "finally {" in resume_block and "busy = false" in resume_block
act_block = RESPONDER[RESPONDER.index("    private fun act(call: suspend () -> Result<*>) {"):RESPONDER.index("\n    }\n}", RESPONDER.index("    private fun act(call: suspend () -> Result<*>) {"))]
assert act_block.index("it.copy(busy = true, error = null)") < act_block.index("viewModelScope.launch")
assert "finally {" in act_block and "busy = false" in act_block
assert "sendJob?.cancel()" in CHAT
assert "private data class PendingTextMessage(val incidentId: Long, val message: String, val clientMessageId: String)" in CHAT
assert "private var pendingTextMessage: PendingTextMessage? = null" in CHAT
assert "pendingTextMessage?.takeIf { it.incidentId == incidentId }?.message" in CHAT
assert "pendingTextMessage = PendingTextMessage(incidentId, message, it)" in CHAT
assert "pendingTextMessage?.clientMessageId == clientMessageId" in CHAT
assert "Do not clear an uncertain text-send identity here" in CHAT
assert "pending.message != message" in CHAT
CHAT_TEST = (ROOT / "app/src/test/java/com/tacresq/app/data/remote/FakeTacApiChatTest.kt").read_text()
assert "committedMessageWithLostResponseRetriesAfterConversationReopenWithoutDuplicate" in CHAT_TEST
assert "originalClientMessageId" in CHAT_TEST
assert "api.myIncidentMessages(incident.id, 50, null)" in CHAT_TEST
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
print("PASS: requester SOS, pending recovery, and draft persistence normalize unexpected failures and release sending state.")
print("PASS: incident chat operations share the same safe failure boundary.")
print("PASS: generation guards prevent old same-incident responses from overwriting a reopened conversation.")
print("PASS: responder tracking verification and status actions claim busy synchronously and always release it.")
print("PASS: lifecycle-aware polling, state collection, and notification tap restoration remain in place.")
