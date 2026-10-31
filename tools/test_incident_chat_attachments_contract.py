#!/usr/bin/env python3
"""Security and client contracts for private incident-chat image attachments."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


migration = read("supabase/migrations/20261031130000_incident_chat_image_attachments.sql")
for label, passed in {
    "attachment metadata is stored with the message": "ADD COLUMN IF NOT EXISTS attachment_path text" in migration,
    "image-only messages remain bounded": "attachment_size_bytes BETWEEN 1 AND 5242880" in migration,
    "only supported image MIME types are accepted": "image/jpeg', 'image/png', 'image/webp" in migration,
    "private bucket is configured": "'incident-chat-attachments'," in migration and "false," in migration,
    "authenticated uploads are incident-scoped": "tacresq_incident_chat_attachment_insert" in migration,
    "downloads require incident chat access": "tacresq_incident_chat_attachment_read" in migration,
    "sender profile is derived from auth.uid": "WHERE p.auth_user_id = auth.uid()" in migration,
    "image RPC rechecks chat access": "private.tacresq_can_access_incident_chat(p_incident_id, actor_profile_id)" in migration,
    "image RPC verifies uploaded object": "FROM storage.objects o" in migration,
    "image RPC verifies stored MIME type": "o.metadata->>'mimetype' = p_attachment_mime_type" in migration,
    "image RPC verifies stored byte size": "(o.metadata->>'size')::bigint = p_attachment_size_bytes" in migration,
    "image RPC has authenticated-only grant": "GRANT EXECUTE ON FUNCTION public.api_send_incident_image_message" in migration,
    "message history includes attachment metadata": "'attachment_path', page.attachment_path" in migration,
}.items():
    assert passed, f"Attachment migration contract failed: {label}"

models = read("app/src/main/java/com/tacresq/app/data/model/Models.kt")
for required in (
    '@SerializedName("attachment_path") val attachmentPath: String? = null',
    '@SerializedName("attachment_mime_type") val attachmentMimeType: String? = null',
    '@SerializedName("attachment_size_bytes") val attachmentSizeBytes: Long? = null',
    '@SerializedName("sender_name") val senderName: String? = null',
):
    assert required in models, f"Chat attachment model missing: {required}"

api = read("app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt")
for required in (
    'storage/v1/object/{bucket}/{path}',
    'api_send_incident_image_message',
    'x-upsert: false',
    'failure.code() != 409',
    'downloadIncidentImage(path: String)',
):
    assert required in api, f"Authenticated storage transport missing: {required}"

screen = read("app/src/main/java/com/tacresq/app/ui/chat/IncidentChatScreen.kt")
for required in (
    "ActivityResultContracts.PickVisualMedia()",
    "PickVisualMedia.ImageOnly",
    'contentDescription = "Attach image"',
    "Photo ready to send",
    "vm.sendImage(incidentId, user.id)",
    "IncidentMessageImage(path = path, vm = vm)",
    "pendingImageClientMessageId",
    "5 * 1024 * 1024",
):
    assert required in screen, f"Image messaging UI contract missing: {required}"

print("PASS: incident chat images use private, incident-authorized storage and bounded Android image attachments.")
