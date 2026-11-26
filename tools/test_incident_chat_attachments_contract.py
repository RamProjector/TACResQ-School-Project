#!/usr/bin/env python3
"""Security and client contracts for private incident-chat image attachments."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


migration = read("supabase/migrations/20261108092703_incident_chat_image_attachments.sql")
reservation_migration = read("supabase/migrations/20261123045607_20261122210000_incident_chat_upload_reservations.sql")
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
    "reservation enforcement defaults off for staged rollout": "reservation_enforcement_enabled boolean NOT NULL DEFAULT false" in reservation_migration,
    "legacy Storage uploads remain compatible until enforcement is enabled": "IF NOT enforcement_enabled THEN" in reservation_migration,
    "legacy image finalization is rejected once enforcement is enabled": "ELSIF enforcement_enabled THEN" in reservation_migration,
    "reservation RPC derives the actor from auth.uid": "WHERE p.auth_user_id = auth.uid()" in reservation_migration,
    "reservation uses the same per-profile serialization lock": "PERFORM pg_advisory_xact_lock(actor_profile_id)" in reservation_migration,
    "reservation RPC is authenticated-only": "GRANT EXECUTE ON FUNCTION public.api_reserve_incident_image_upload" in reservation_migration,
    "Storage INSERT requires a live matching reservation": "private.tacresq_upload_reservation_allows_insert(" in reservation_migration
        and "r.expires_at > clock_timestamp()" in reservation_migration,
    "Storage preflight accepts declared content length": "COALESCE(metadata->>'size', metadata->>'contentLength')" in reservation_migration,
    "reservation identity is independent of sender message UUID": "gen_random_uuid(), actor_profile_id, p_incident_id, p_client_message_id" in reservation_migration,
    "reservation expiry helper evaluates wall-clock time": "clock_timestamp()" in reservation_migration,
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
    "reservation RPC rejects existing message key reuse": "Client message ID is already used by a text or image message" in reservation_migration,
}.items():
    assert passed, f"Upload reservation migration contract failed: {label}"

# Both Storage directions must use the shared caller-derived membership
# predicate. A future account-state guard belongs in this shared helper chain.
original_insert_policy = migration.split(
    "CREATE POLICY tacresq_incident_chat_attachment_insert", 1
)[1].split("$sql$", 1)[0]
original_read_policy = migration.split(
    "CREATE POLICY tacresq_incident_chat_attachment_read", 1
)[1].split("$sql$", 1)[0]
assert "private.tacresq_auth_profile_id()" in original_insert_policy, (
    "Storage INSERT must bind the object path to the authenticated profile"
)
assert "private.tacresq_auth_can_access_incident_chat(" in original_insert_policy, (
    "Storage INSERT must require caller-derived incident membership"
)
assert "private.tacresq_auth_can_access_incident_chat(" in original_read_policy, (
    "Storage SELECT must require caller-derived incident membership"
)

reservation_validator = reservation_migration.split(
    "CREATE OR REPLACE FUNCTION private.tacresq_upload_reservation_allows_insert(", 1
)[1].split("$function$;", 1)[0]
identity_check = reservation_validator.index("actor_profile_id := private.tacresq_auth_profile_id()")
compatibility_return = reservation_validator.index("IF NOT enforcement_enabled THEN")
assert identity_check < compatibility_return, (
    "Storage reservation compatibility mode must not bypass caller identity resolution"
)
assert "private.tacresq_can_access_incident_chat(r.incident_id, actor_profile_id)" in reservation_validator, (
    "Strict reservation mode must preserve caller-derived incident membership"
)

storage_policy = reservation_migration.split(
    "CREATE POLICY tacresq_incident_chat_attachment_insert", 1
)[1]
assert "private.tacresq_upload_reservation_allows_insert(" in storage_policy, (
    "Storage INSERT policy must invoke the live reservation validator"
)
assert "COALESCE(metadata->>'size', metadata->>'contentLength')" in storage_policy, (
    "Storage INSERT policy must support the pre-upload contentLength probe"
)

storage_tests = read("tools/test_incident_chat_storage_policies.sql")
for required in (
    "public.api_reserve_incident_image_upload(",
    "legacy_image_compatibility",
    "reservation_enforcement_enabled = true",
    "A participant must not upload an object without a matching reservation",
    "Strict finalization must reject an object without a reservation",
    "Legacy image finalization must remain compatible while enforcement is disabled",
    "An unrelated account must not reserve an upload for another incident",
    "The 21st image reservation within one minute must be rejected",
    '"contentLength":"4"',
    "A duplicate object path must not overwrite the existing attachment",
    "Different senders must receive independent reservation identities",
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

transport_test = read("app/src/test/java/com/tacresq/app/data/remote/SupabaseIncidentImageUploadTest.kt")
for required in (
    "consumedReservationSkipsDuplicateStorageUploadAndFinalizesExistingMessage",
    "liveReservationUploadsBeforeFinalization",
    "server.requestCount",
):
    assert required in transport_test, f"Image upload transport regression test missing: {required}"

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

method_start = api.index("override suspend fun sendIncidentImageMessage(")
method_end = api.index("override suspend fun downloadIncidentImage", method_start)
image_send_method = api[method_start:method_end]
reserve_call = image_send_method.index("rpc.reserveIncidentImageUpload(")
upload_call = image_send_method.find("storage.upload(")
finalize_call = image_send_method.index("rpc.sendIncidentImageMessage(")
assert reserve_call < upload_call < finalize_call, "Image send must reserve, upload, then finalize"
assert upload_call >= 0, "Unconsumed reservations must retain the Storage upload path"
assert "if (!reservation.consumed)" in image_send_method, "Consumed reservations must skip duplicate Storage uploads"

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

inventory = read("tools/report_incident_chat_attachment_inventory.sql")
for required in (
    "private.incident_chat_upload_reservations",
    "pending_unfinalized_object_count",
    "unreserved_orphan_object_count",
    "active_reservation_without_object_count",
    "expired_reservation_without_object_count",
    "consumed_reservation_without_message_count",
    "message_metadata_mismatch_count",
    "reservation_metadata_mismatch_count",
    "messages_with_missing_object_count",
    "active_reserved_declared_bytes",
):
    assert required in inventory, f"Read-only attachment inventory missing: {required}"
assert "o.name AS" not in inventory, "Attachment inventory must not disclose raw object paths"
assert not any(
    line.lstrip().upper().startswith(("DELETE ", "UPDATE ", "INSERT ", "TRUNCATE ", "DROP ", "ALTER "))
    for line in inventory.splitlines()
), "Attachment inventory must remain strictly read-only"


# The standalone inventory query is not part of migration replay, so guard its
# CTE metadata comparisons and final projection explicitly.
for required in (
    "AS has_message_metadata_mismatch",
    "AS has_reservation_metadata_mismatch",
    "o.message_metadata_mismatch_count",
    "o.reservation_metadata_mismatch_count",
    "AS active_reserved_declared_bytes",
    "r.active_reserved_declared_bytes",
    "m.messages_with_missing_object_count",
    "FROM bucket b",
    "CROSS JOIN object_summary o",
    "CROSS JOIN message_summary m",
    "CROSS JOIN reservation_summary r",
):
    assert required in inventory, f"Attachment inventory SQL contract missing: {required}"
assert "r.active_reserved_metadata_bytes" not in inventory, "Inventory must project the declared-byte alias that its CTE defines"
assert "^[0-9]+$" in inventory, "Inventory size metadata parsing must use a complete numeric pattern"
assert inventory.count("WITH bucket AS") == 1, "Inventory must contain one complete query, not duplicated fragments"
assert inventory.rstrip().endswith(";"), "Inventory query must terminate cleanly"


# The Storage surface currently consists of one application bucket. Keep the
# inventory fail-closed so adding another bucket forces a fresh security review.
inventory_sql = read("tools/check_storage_bucket_inventory.sql")
identity_migration = read("supabase/migrations/20261125020823_account_lifecycle_identity_helpers.sql")
for required in (
    "FROM storage.buckets",
    "unknown_bucket_ids",
    "Unreviewed Storage bucket(s) detected",
    "incident-chat-attachments",
    "tacresq_incident_chat_attachment_insert",
    "tacresq_incident_chat_attachment_read",
    "tacresq_auth_profile_id",
    "tacresq_auth_can_access_incident_chat",
    "object_count",
    "declared_object_bytes",
):
    assert required in inventory_sql, f"Storage bucket inventory contract missing: {required}"
assert not any(
    line.lstrip().upper().startswith(("DELETE ", "UPDATE ", "INSERT ", "TRUNCATE ", "DROP ", "ALTER "))
    for line in inventory_sql.splitlines()
), "Storage bucket inventory must remain strictly read-only"
for required in (
    "CREATE OR REPLACE FUNCTION private.tacresq_auth_profile_id()",
    "p.account_status = 'active'",
    "FOR SHARE",
):
    assert required.lower() in identity_migration.lower(), (
        f"Storage active-account identity guard missing: {required}"
    )
for required in (
    "suspended requester must not download previously uploaded incident images",
    "suspended requester must not upload new incident images",
    "suspended requester with an unexpired jwt is denied chat and storage access",
):
    assert required.lower() in storage_tests.lower(), (
        f"Same-valid-JWT Storage suspension regression missing: {required}"
    )

# Admin must distinguish SQL/source checks from real Storage and device acceptance.
admin_page = read("backend/web/src/pages/AdminConsole.tsx")
for required in (
    "END-TO-END ACCEPTANCE",
    "HTTP and device verification",
    "Isolated HTTP Storage acceptance and Android device verification remain outstanding",
    "production rollout is not approved by this workspace",
):
    assert required in admin_page, f"Admin attachment rollout disclosure missing: {required}"

print("PASS: incident chat images use private, incident-authorized storage and bounded Android image attachments.")
