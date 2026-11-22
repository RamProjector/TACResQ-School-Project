#!/usr/bin/env python3
"""Security and client contracts for private incident-chat image attachments."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


migration = read("supabase/migrations/20261108092703_incident_chat_image_attachments.sql")
reservation_migration = read("supabase/migrations/20261122210000_incident_chat_upload_reservations.sql")
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

for label, passed in {
    "reservation ledger is private": "CREATE TABLE private.incident_chat_upload_reservations" in reservation_migration
        and "ALTER TABLE private.incident_chat_upload_reservations ENABLE ROW LEVEL SECURITY" in reservation_migration,
    "reservation policy is configurable": "reservation_ttl_seconds integer NOT NULL DEFAULT 900" in reservation_migration
        and "uploads_per_minute integer NOT NULL DEFAULT 20" in reservation_migration,
    "reservation RPC derives the actor from auth.uid": "WHERE p.auth_user_id = auth.uid()" in reservation_migration,
    "reservation uses the same per-profile serialization lock": "PERFORM pg_advisory_xact_lock(actor_profile_id)" in reservation_migration,
    "reservation RPC is authenticated-only": "GRANT EXECUTE ON FUNCTION public.api_reserve_incident_image_upload" in reservation_migration,
    "Storage INSERT requires a live matching reservation": "private.tacresq_upload_reservation_allows_insert(" in reservation_migration
        and "r.expires_at > now()" in reservation_migration,
    "reservation binds exact object metadata": "r.object_path = p_object_path" in reservation_migration
        and "r.mime_type = p_mime_type" in reservation_migration
        and "r.size_bytes = p_size_bytes" in reservation_migration,
    "image finalization requires matching reservation": "A matching unconsumed image upload reservation is required" in reservation_migration,
    "reservation is consumed in the message transaction": "SET consumed_at = now()" in reservation_migration,
    "expired reservations can still finalize uploaded objects": "a delayed finalization may still complete" in reservation_migration,
    "reservation rate limit aligns with existing message cap": "uploads_per_minute BETWEEN 1 AND 20" in reservation_migration,
    "text and image sends share pending reservation accounting": "CREATE OR REPLACE FUNCTION public.api_send_incident_message" in reservation_migration
        and "recent_message_count + recent_reservation_count >= uploads_limit" in reservation_migration,
    "text RPC rejects image reservation key reuse": "Client message ID is already reserved for an image attachment" in reservation_migration
        and "existing_message.attachment_path IS NOT NULL" in reservation_migration,
}.items():
    assert passed, f"Upload reservation migration contract failed: {label}"

storage_tests = read("tools/test_incident_chat_storage_policies.sql")
for required in (
    "public.api_reserve_incident_image_upload(",
    "A participant must not upload an object without a matching reservation",
    "An unrelated account must not reserve an upload for another incident",
    "The 21st image reservation within one minute must be rejected",
):
    assert required in storage_tests, f"Storage reservation regression test missing: {required}"

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
    'api_reserve_incident_image_upload',
    'rpc.reserveIncidentImageUpload',
    'x-upsert: false',
    'failure.code() != 409',
    'downloadIncidentImage(path: String)',
):
    assert required in api, f"Authenticated storage transport missing: {required}"

reserve_call = api.index("rpc.reserveIncidentImageUpload(")
upload_call = api.index("storage.upload(", reserve_call)
finalize_call = api.index("rpc.sendIncidentImageMessage(", upload_call)
assert reserve_call < upload_call < finalize_call, "Image send must reserve, upload, then finalize"

screen = read("app/src/main/java/com/tacresq/app/ui/chat/IncidentChatScreen.kt")
for required in (
    "ActivityResultContracts.PickVisualMedia()",
    "PickVisualMedia.ImageOnly",
    'contentDescription = "Attach image"',
    "Photo ready to send",
    "vm.sendImage(incidentId, user.id)",
    "IncidentMessageImage(path = path, vm = vm)",
    "pendingImageRecord",
    "restored?.clientMessageId",
    "5 * 1024 * 1024",
):
    assert required in screen, f"Image messaging UI contract missing: {required}"

replay = read("tools/test_supabase_migration_replay.sh")
storage_tests = read("tools/test_incident_chat_storage_policies.sql")
assert "tools/test_incident_chat_storage_policies.sql" in replay, "Migration replay must execute Storage RLS tests"
for required in (
    "api_send_incident_image_message",
    "The incident requester must be able to download an assigned responder image",
    "An unrelated account must not read incident image objects",
    "An unrelated account must not upload into another incident conversation",
):
    assert required in storage_tests, f"Storage authorization regression test missing: {required}"

print("PASS: incident chat images use private, incident-authorized storage and bounded Android image attachments.")
