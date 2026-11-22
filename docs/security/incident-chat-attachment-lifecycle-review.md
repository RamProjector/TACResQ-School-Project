# Incident-chat attachment lifecycle and storage-abuse review

## Scope and evidence

Reviewed the Android image-send path, encrypted pending-send recovery, Supabase Storage upload/download transport, the image-message RPC, and the migration that creates the private `incident-chat-attachments` bucket and its policies.

This is a source review only. No migration was applied, no Storage bucket or policy was changed, and no live object/account was inspected.

## Current protections confirmed in source

- Android accepts JPEG, PNG, and WebP images up to 5 MiB and reads the selected stream with a byte ceiling before retaining the payload.
- Uploads use a deterministic path: `incident_id/profile_id/client_message_uuid.extension`. A retry after an ambiguous upload can reuse the same object; HTTP 409 is treated as “object may already exist,” after which the idempotent message RPC decides the result.
- The private bucket is configured with a 5 MiB limit and the same MIME allowlist. It is not public.
- Storage INSERT checks the bucket, path shape, authenticated profile folder, and current incident-chat access. SELECT checks incident-chat access.
- The image-message RPC derives the sender from `auth.uid()`, rechecks incident access, validates the exact deterministic path and metadata, and checks that the uploaded object exists before inserting the message.
- The RPC serializes sends per profile and applies a 20-message-per-minute message-row limit.
- Android stores a pending image retry identity (incident, caption, client UUID, MIME type, SHA-256, sender) in the session store. The user must reselect the same image to retry; the app does not silently substitute a different image.
- No Storage DELETE policy or application cleanup path is present in the reviewed migration/client flow.

## Material gap: orphaned objects and upload-rate abuse

The upload is a separate Storage operation that happens **before** the message RPC. If the upload succeeds but the RPC permanently fails, the object remains in the private bucket without a corresponding message row. The retry record helps recover from temporary failures, but it cannot guarantee recovery if the user abandons the send, loses local app data, loses access to the incident, or never retries.

The database message rate limit runs only after upload. The reviewed Storage INSERT policy does not impose a corresponding per-profile upload rate or quota. An authenticated participant who can access an incident can therefore create many distinct UUID object paths without creating message rows, subject to platform/bucket limits. This can consume storage and leave private but unreferenced objects.

This is not evidence of cross-account disclosure: the reviewed bucket is private and its read policy remains incident-scoped. It is a storage lifecycle and resource-consumption gap.

## Recommended remediation, in safe order

1. **Measure before deleting.** Build a read-only inventory that joins `storage.objects` in this bucket to `incident_messages.attachment_path`, reporting referenced objects, unreferenced objects, object age, byte size, and incident/profile path components. Do not expose object contents or signed/public URLs in the report.
2. **Define a grace period and retention policy.** A cleanup candidate must be unreferenced, older than a deliberately chosen grace period, and not associated with an active/recoverable pending send. Because pending-send state is device-local, server-side cleanup cannot know every legitimate retry. Choose a conservative grace period and communicate that an expired retry may require selecting and sending the image again.
3. **Use a privileged scheduled cleanup, not client deletion.** If approved, use a narrowly scoped service-role job to delete only verified orphan candidates in bounded batches. Keep the bucket private; do not grant clients broad DELETE access. Log counts, byte totals, run time, and failures without logging message contents or coordinates.
4. **Add upload abuse controls before the message RPC.** Consider a server-mediated upload authorization/quota mechanism or a bounded per-profile object-count/byte budget. A message-row rate limit alone does not constrain pre-message uploads. Avoid a paid dependency; confirm the selected enforcement mechanism fits the existing Supabase/Render deployment and free-tier constraints before implementation.
5. **Preserve idempotent recovery.** Any upload quota or cleanup design must recognize retries to the same deterministic path and must not delete an object while its matching message RPC is in flight.
6. **Add regression coverage.** Cover successful upload + message, upload success + RPC timeout + retry, abandoned orphan older than grace, a referenced object never being deleted, same-path retry, many distinct uploads by one participant, unrelated-account upload/read denial, and cleanup failure/retry.

## Operational decision still needed

The repository does not establish an approved attachment-retention period or a maximum storage budget per participant/incident. Those are policy decisions, not safe values to invent in a code change. Until they are agreed, keep cleanup disabled and treat orphan inventory as report-only.

## Verification boundary

This review did not run tests, replay migrations, upload/download a real image, inspect live Storage metadata, change production configuration, or deploy a cleanup job. The source supports the controls and gaps described above; actual Storage behavior still requires acceptance testing in a non-production Supabase project.
