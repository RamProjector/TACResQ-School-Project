# Incident-chat attachment lifecycle and storage-abuse review

## Scope and evidence

Reviewed the Android image-send path, encrypted pending-send recovery, Supabase Storage upload/download transport, the image-message RPC, the migration that creates the private `incident-chat-attachments` bucket and its policies, and the live Supabase bucket/object metadata.

Evidence is split between repository source and a read-only live database inspection on 2026-11-22. No migration was applied, no Storage bucket or policy was changed, no object was downloaded or deleted, and no cleanup job was deployed.

## Current protections confirmed in source

- Android accepts JPEG, PNG, and WebP images up to 5 MiB and reads the selected stream with a byte ceiling before retaining the payload.
- Uploads use a deterministic path: `incident_id/profile_id/client_message_uuid.extension`. A retry after an ambiguous upload can reuse the same object; HTTP 409 is treated as “object may already exist,” after which the idempotent message RPC decides the result.
- The Android Storage transport sends `x-upsert: false`, preventing intentional overwrite of an existing object at the deterministic path.
- The private bucket is configured with a 5 MiB limit and the same MIME allowlist. It is not public.
- Storage INSERT checks the bucket, path shape, authenticated profile folder, and current incident-chat access. SELECT checks incident-chat access. No authenticated UPDATE or DELETE policy is defined for this bucket.
- Android downloads through the authenticated Supabase Storage object endpoint; it does not create a public URL or use a long-lived signed URL.
- The image-message RPC derives the sender from `auth.uid()`, rechecks incident access, validates the exact deterministic path and metadata, and checks that the uploaded object exists before inserting the message.
- The RPC serializes sends per profile and applies a 20-message-per-minute message-row limit.
- Android stores a pending image retry identity (incident, caption, client UUID, MIME type, SHA-256, sender) in the session store. The user must reselect the same image to retry; the app does not silently substitute a different image.
- Chat authorization is re-evaluated at read time: requester access is limited to the incident owner; responder/driver access requires current assignment membership or the legacy assigned-unit field; dispatcher access is allowed. Other roles, including Admin, fail closed.

## Live Storage verification — 2026-11-22

Read-only inspection of Supabase project `kttguamhznoflzagfutr` confirmed:

- Bucket `incident-chat-attachments` exists and is private.
- Maximum object size is 5,242,880 bytes (5 MiB); allowed MIME types are JPEG, PNG, and WebP.
- Bucket versioning is disabled and no bucket lifecycle configuration is set.
- The live Storage policies are limited to authenticated INSERT and SELECT. INSERT checks the deterministic incident/profile path and incident-chat access; SELECT checks current incident-chat access. There is no authenticated UPDATE or DELETE policy.
- The bucket contained one object at inspection time. Its metadata reported 79,547 bytes.
- That object matched one `incident_messages.attachment_path` row. The incident and sender path components matched the message row. No unreferenced object or path mismatch was found in this snapshot.
- The live inventory is only a point-in-time metadata check. It does not prove that the stored bytes are readable, uncorrupted, backed up, or restorable.

## Material gap: orphaned objects and upload-rate abuse

The upload is a separate Storage operation that happens **before** the message RPC. If the upload succeeds but the RPC permanently fails, the object remains in the private bucket without a corresponding message row. The retry record helps recover from temporary failures, but it cannot guarantee recovery if the user abandons the send, loses local app data, loses access to the incident, or never retries.

The database message rate limit runs only after upload. The reviewed Storage INSERT policy does not impose a corresponding per-profile upload rate or quota. An authenticated participant who can access an incident can therefore create many distinct UUID object paths without creating message rows, subject to platform/bucket limits. This can consume storage and leave private but unreferenced objects.

This is not evidence of cross-account disclosure: the bucket is private and its read policy remains incident-scoped. It is a storage lifecycle and resource-consumption gap.

## Recommended remediation, in safe order

1. **Keep measuring before deleting.** The live inventory found no orphan in its one-object snapshot. A reusable read-only summary query is provided at `tools/report_incident_chat_attachment_inventory.sql`. It joins `storage.objects` to `incident_messages.attachment_path` and reports bucket configuration, referenced/unreferenced counts, age, byte size, and path-consistency results without printing object paths, contents, signed URLs, or coordinates. It performs no writes. Re-run it before any future cleanup decision.
2. **Define a grace period and retention policy.** A cleanup candidate must be unreferenced, older than a deliberately chosen grace period, and not associated with an active/recoverable pending send. Because pending-send state is device-local, server-side cleanup cannot know every legitimate retry. Choose a conservative grace period and communicate that an expired retry may require selecting and sending the image again.
3. **Use a privileged scheduled cleanup, not client deletion.** If approved, use a narrowly scoped service-role job to delete only verified orphan candidates in bounded batches. Keep the bucket private; do not grant clients broad DELETE access. Log counts, byte totals, run time, and failures without logging message contents or coordinates.
4. **Add upload abuse controls before the message RPC.** Consider a server-mediated upload authorization/quota mechanism or a bounded per-profile object-count/byte budget. A message-row rate limit alone does not constrain pre-message uploads. Avoid a paid dependency; confirm the selected enforcement mechanism fits the existing Supabase/Render deployment and free-tier constraints before implementation.
5. **Preserve idempotent recovery.** Any upload quota or cleanup design must recognize retries to the same deterministic path and must not delete an object while its matching message RPC is in flight.
6. **Add regression coverage.** Cover successful upload + message, upload success + RPC timeout + retry, abandoned orphan older than grace, a referenced object never being deleted, same-path retry, many distinct uploads by one participant, unrelated-account upload/read denial, removed-member read denial, and cleanup failure/retry.
7. **Acceptance-test real Storage behavior.** In a non-production Supabase project, verify upload, duplicate-path rejection, authorized download, unrelated-account denial, removed-member denial, and absence of client update/delete access. The existing SQL replay test validates the migration's policy contract through a test Storage schema; it is not a substitute for this real Storage acceptance test.

## Operational decision still needed

The repository and current project configuration do not establish an approved attachment-retention period or a maximum storage budget per participant/incident. Those are policy decisions, not safe values to invent in a code change. Until they are agreed, keep cleanup disabled and treat orphan inventory as report-only.

## Verification boundary

This review did not run a real upload/download acceptance test, replay migrations, inspect object bytes, change production configuration, or deploy a cleanup job. Live inspection was read-only and covered bucket settings, policy definitions, and object/message metadata. The source and metadata support the controls and gaps described above; they do not certify end-to-end Storage behavior or backup recoverability.
