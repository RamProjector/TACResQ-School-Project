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
    "fun closeConversation()",
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
    "chatVm.closeConversation()",
    "Text(\"Open conversation\")",
):
    assert required in root, f"Chat navigation wiring missing: {required}"

requester = read("app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt")
assert "onOpenChat: (Long) -> Unit" in requester
assert "onClick = { onOpenChat(activeIncident.id) }" in requester
assert 'Text("Open incident messages")' in requester
assert 'Text("Review", maxLines = 1, softWrap = false)' in requester
assert 'Text("Review request")' not in requester, "compact request review action must not wrap on narrow layouts"
assert "RequesterScreen(vm, user, logout, onOpenChat = openChat, onOpenNotifications = openNotifications)" in root

dispatcher = read("app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt")
assert "onOpenChat: (Long) -> Unit" in dispatcher
assert 'Text("Open conversation")' in dispatcher

api_source = read("app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt")
assert "rest/v1/incident_messages" not in api_source, "Android must not access the private chat table directly"
assert "rest/v1/rpc/api_my_incident_messages" in api_source, "Android must use the scoped list RPC"
assert "rest/v1/rpc/api_send_incident_message" in api_source, "Android must use the scoped send RPC"
screen_source = read("app/src/main/java/com/tacresq/app/ui/chat/IncidentChatScreen.kt")
assert "incident_messages" not in screen_source, "UI must not directly access the private chat table"

print("PASS: incident chat uses caller-scoped RPCs, role-scoped navigation, lifecycle-bound polling, bounded text, idempotent retry, and no direct table access.")


# The database flood guard counts a sender's messages in a rolling one-minute
# window. Its query needs a matching composite index so cost remains bounded as
# the incident-message history grows.
chat_sql = read("supabase/migrations/20261026200000_incident_scoped_chat.sql")
index_sql = read("supabase/migrations/20261030130000_incident_message_rate_limit_index.sql")
for label, passed in {
    "rate limit filters by sender": "WHERE m.sender_id = actor_profile_id" in chat_sql,
    "rate limit uses a rolling one-minute window": "m.created_at >= now() - interval '1 minute'" in chat_sql,
    "concurrent sends are serialized before counting": "pg_advisory_xact_lock(actor_profile_id)" in chat_sql,
    "index leads with sender and then timestamp": "ON public.incident_messages (sender_id, created_at DESC)" in index_sql,
    "migration verifies the actual index definition": "actual_index_definition NOT ILIKE '%(sender_id, created_at DESC)%'" in index_sql,
}.items():
    assert passed, f"Incident-chat rate-limit index contract failed: {label}"

print("PASS: incident-chat sender rate-limit query has a matching composite index.")
