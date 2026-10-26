#!/usr/bin/env python3
"""Static security and lifecycle contracts for incident chat."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


models = read("app/src/main/java/com/tacresq/app/data/model/Models.kt")
for required in (
    "data class IncidentMessage(",
    '@SerializedName("incident_id") val incidentId: Long',
    '@SerializedName("sender_id") val senderId: Long',
    '@SerializedName("created_at") val createdAt: String?',
):
    assert required in models, f"Chat model contract missing: {required}"

api = read("app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt")
for required in (
    "TacApi, TacNotificationApi, TacChatApi",
    'rest/v1/rpc/api_my_incident_messages',
    'rest/v1/rpc/api_send_incident_message',
    "IncidentMessagesRpc(incidentId, limit, beforeId)",
    "SendIncidentMessageRpc(incidentId, normalized, clientMessageId)",
    "limit in 1..100",
    "1..2000",
):
    assert required in api, f"Supabase chat RPC contract missing: {required}"

repository = read("app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt")
for required in (
    "suspend fun myIncidentMessages(",
    "suspend fun sendIncidentMessage(",
    "api as? TacChatApi",
    "Incident chat is not available with the configured backend.",
):
    assert required in repository, f"Chat repository contract missing: {required}"

fake = read("app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt")
for required in (
    "TacApi, TacNotificationApi, TacChatApi",
    "override suspend fun myIncidentMessages(",
    "override suspend fun sendIncidentMessage(",
    "ensureChatAccess(incidentId, me)",
    "Client message ID was already used for different content.",
    "Message rate limit reached.",
    'addNotification(\n                    recipientId, incidentId, "incident_message"',
):
    assert required in fake, f"Demo chat security/behavior missing: {required}"

screen = read("app/src/main/java/com/tacresq/app/ui/chat/IncidentChatScreen.kt")
for required in (
    "class IncidentChatViewModel",
    "repeatOnLifecycle(Lifecycle.State.STARTED)",
    "delay(10_000)",
    "Load older messages",
    "Message was not confirmed. Retry safely.",
    "pendingClientMessageId",
    "refreshJob?.cancel()",
    "if (activeIncidentId != incidentId) return@onSuccess",
    "2000",
    "Back to incident list",
    "!state.loading && state.error == null",
):
    assert required in screen, f"Chat UI/lifecycle contract missing: {required}"

root = read("app/src/main/java/com/tacresq/app/ui/AppRoot.kt")
for required in (
    "IncidentChatScreen(chatVm, selectedChatIncidentId, user",
    "onOpenChat = openChat",
    "selectedChatIncidentId",
    "Text(\"Open conversation\")",
):
    assert required in root, f"Chat navigation wiring missing: {required}"

dispatcher = read("app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt")
assert "onOpenChat: (Long) -> Unit" in dispatcher
assert 'Text("Open conversation")' in dispatcher

for path in (
    "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt",
    "app/src/main/java/com/tacresq/app/ui/chat/IncidentChatScreen.kt",
):
    assert "incident_messages" not in read(path), f"{path} must not directly access the private chat table"

print("PASS: incident chat uses caller-scoped RPCs, role-scoped navigation, lifecycle-bound polling, bounded text, idempotent retry, and no direct table access.")
