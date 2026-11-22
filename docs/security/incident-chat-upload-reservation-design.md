# Incident-chat upload reservation design

**Status:** Design only; no migration or runtime behavior change is included in this change.

## Decision summary

The image upload currently reaches Supabase Storage before the image-message RPC. The message RPC's sender rate limit therefore does not protect Storage from repeated uploads that never become messages. The bucket remains private and incident-scoped, but an authenticated participant can create distinct UUID paths and consume storage without creating message rows.

The recommended next implementation is a database-backed upload reservation that must exist before Storage accepts an object. Reservation creation must be atomic and must bind the authenticated profile, incident, exact deterministic object path, MIME type, and a short expiry. The Storage INSERT policy must validate that reservation. The existing image-message RPC must consume the same reservation after independently verifying the uploaded object.

This document deliberately does **not** choose quota numbers, retention periods, reservation expiry, or cleanup schedules. Those are product/operational policy decisions and must be approved before a migration is authored.

## Verified current flow

Source review of `SupabaseTacApi.sendIncidentImageMessage` confirms:

1. Android validates a positive incident/profile ID, supported MIME type, non-empty payload, 5 MiB maximum, and UUID client-message ID.
2. It derives the deterministic object path `incident_id/profile_id/client_message_uuid.extension`.
3. It uploads to the private `incident-chat-attachments` bucket with the existing authenticated Storage transport and no-overwrite behavior.
4. An HTTP 409 is treated as an ambiguous prior upload; the idempotent image-message RPC decides whether the matching message can be created.
5. The RPC is called only after upload and independently checks the authenticated sender, incident access, expected path, MIME type, object existence, and object metadata.
6. The message-row rate limit is enforced by the RPC after the Storage upload. It cannot prevent pre-message object creation.

The live read-only inventory documented in the lifecycle review found one object, one matching message reference, no path mismatch, and no orphan at the time of inspection. This snapshot does not establish a long-term orphan rate or prove object-byte recoverability.

## Additional source-level constraints

A deeper comparison of the Android transport, current SQL migration, and existing concurrency controls establishes these implementation details:

- The Android method receives a `senderId` to construct the deterministic path, but the database RPC independently resolves the authoritative profile from `auth.uid()` and checks that the path embeds that profile. The reservation RPC must follow the database-side identity pattern; a client-supplied profile ID must never authorize a reservation.
- The current Storage INSERT policy checks the bucket, path shape, path profile, and current incident-chat membership. It does **not** bind the object to a one-time operation record, so a participant can create multiple distinct UUID paths while still being an authorized chat member.
- The image-message RPC takes a per-profile advisory transaction lock before its idempotency and message-rate checks. Existing location writers also use one-key advisory locks keyed by profile ID. A future reservation implementation must define a consistent lock namespace/order and avoid introducing deadlocks or accidental cross-feature contention. A reservation lock must serialize quota accounting with other reservations, not assume the message RPC's later lock protects uploads that already happened.
- The current image-message RPC verifies Storage object path, MIME metadata, and byte-size metadata. It does not compare a server-verified content digest. The Android pending retry state may preserve a local hash, but a client-provided hash is not proof of stored object bytes. If the system requires cryptographic same-content retry binding, the future design must identify a server-verifiable checksum source or explicitly document that current server idempotency is path/MIME/size-based rather than byte-for-byte.
- The HTTP 409 path is only a signal that the deterministic object name already exists. It is not, by itself, proof that the existing object's bytes match the current device payload. The finalization RPC must verify the existing object against the reservation's authoritative metadata before creating or returning a message.

These points are constraints for the implementation and acceptance tests, not evidence of a confirmed cross-account disclosure.

## Required reservation contract

### 1. Reservation creation

Add a narrowly granted authenticated RPC, for example `api_reserve_incident_chat_upload`, with inputs limited to:

- incident ID;
- client message UUID;
- supported MIME type;
- optionally a client-reported byte count for early rejection and diagnostics only.

The RPC must derive the profile ID from `auth.uid()`; it must never accept a caller-selected profile ID as authority. It must validate current incident-chat membership using the same authorization helper as the message RPC and derive the exact object path server-side.

A client-reported byte count is untrusted. It may be used to reject a clearly oversized request, but it must not be the sole basis for enforcing a byte quota. The Storage bucket's 5 MiB maximum remains an independent hard ceiling.

### 2. Atomic quota accounting

The reservation transaction must serialize quota decisions for the relevant account and, if selected by policy, incident scope. Concurrent requests must not both observe the same remaining quota and exceed it.

A safe implementation can use a locked per-profile/per-incident quota row or a transaction-scoped advisory lock, then calculate usage from:

- referenced object metadata for existing messages;
- unexpired reservations that have not yet been reconciled;
- the conservative maximum bytes reserved for each in-flight upload.

Until Storage metadata is available, reserve the full bucket maximum per upload slot rather than trusting the client's declared size. After upload, reconcile against Storage's server-recorded object metadata. If the selected policy is count-based rather than byte-based, document that explicitly and still enforce the independent per-object ceiling.

Do not rely on a simple `COUNT(*)` inside the Storage INSERT policy as the concurrency control. Multiple concurrent uploads can pass the same count check.

### 3. Storage INSERT policy

The INSERT policy must require a matching, unexpired reservation for the exact bucket and object path, with:

- `auth.uid()` mapped to the reservation's profile;
- incident access still valid;
- matching incident/profile path components;
- matching allowed MIME type;
- no prior object at the deterministic path;
- reservation state eligible for one upload.

The policy must not accept a reservation for a different user, incident, MIME type, or path. Keep the bucket private. Do not add authenticated UPDATE or DELETE access.

The policy should use a small private helper with a fixed search path and narrowly scoped EXECUTE grants if needed. Avoid exposing the reservation ledger through direct table privileges.

### 4. Message commit and idempotency

The image-message RPC must continue to independently verify the actual Storage object and current incident authorization. It must additionally bind the message to the reservation and mark that reservation consumed in the same transaction that inserts the message row.

A retry with the same client UUID and same deterministic path must remain idempotent. A reservation must not permit overwriting an existing object. If a message already exists for the same sender/client UUID, return the existing message only after validating that the object path and reservation refer to that same operation.

### 5. Expiry and abandoned uploads

An upload may succeed while the subsequent message RPC fails permanently. The reservation then remains unconsumed and the object may be orphaned.

An expired reservation must stop authorizing a new Storage INSERT even if its ledger row has not yet been physically deleted. Reconciliation must distinguish:
- reservation exists, no object;
- object exists, no message;
- message exists and object matches;
- object path or metadata mismatch.

Do not automatically delete objects in this change. Server-side cleanup needs an approved grace period, retention policy, a bounded service-role deletion process, and evidence that the object is unreferenced. Pending retry state is device-local, so the server cannot know whether every apparently abandoned upload is still recoverable.

No `pg_cron` extension or cleanup scheduler was present in the connected project during the read-only inspection. Do not assume a scheduled cleanup mechanism exists.

## Evidence-based threat assessment

### System boundary and verified facts

The assessment is limited to TACResQ's incident-chat image path. It does not claim that the whole application or Supabase project is insecure.

Verified source and configuration facts:

- Android uploads directly to the private Supabase Storage bucket before calling the image-message RPC.
- The bucket enforces a 5 MiB per-object ceiling and JPEG/PNG/WebP allowlist.
- Storage INSERT authorization checks the authenticated profile's path and incident-chat access, but it does not require a one-time upload operation record.
- The image-message RPC independently checks authenticated sender, incident access, deterministic path, object existence, MIME metadata, and byte-size metadata.
- The message rate limit is applied by the RPC after the object upload.
- The point-in-time read-only inventory recorded one object with one message reference and no detected orphan or path mismatch. This is evidence that the inspected snapshot was clean, not evidence that the lifecycle gap cannot be abused.
- No confirmed exploitation, storage exhaustion incident, or measured orphan rate has been established.

### Threat scenarios and risk reasoning

| ID | Scenario | Existing control | Remaining exposure | Proposed mitigation |
|---|---|---|---|---|
| T1 | Authenticated incident member repeatedly uploads distinct UUID paths without creating messages | Private bucket, membership/path checks, 5 MiB per-object cap | Message rate limit runs after upload; no one-time upload authorization or aggregate accounting | Atomic reservation before Storage INSERT; configurable resource budget |
| T2 | Concurrent requests race past a naïve quota check | Existing per-profile lock in the message RPC | That lock is acquired after upload and does not serialize reservation decisions | Serialize reservation accounting in the reservation transaction |
| T3 | Caller attempts to reserve for another profile or incident | Message RPC derives profile from auth.uid() and checks incident access | A new reservation endpoint could introduce an IDOR if it trusts client profile IDs | Derive profile from auth.uid(); recheck current incident membership |
| T4 | Expired or mismatched reservation is reused for another path or content type | Current deterministic path and no-overwrite upload | No reservation currently binds the upload operation to its intended path/type | Bind user, incident, exact path, MIME type, and state; Storage policy checks the same binding |
| T5 | Upload succeeds but message finalization times out | Deterministic object path, no-overwrite behavior, idempotent message RPC | A retry may see HTTP 409; that status alone does not prove the stored bytes match the intended payload | Keep same operation identity; verify authoritative stored metadata before finalization; never treat 409 alone as proof |
| T6 | Premature cleanup deletes evidence or an upload awaiting retry | No client DELETE privilege; no automatic cleanup currently | A future cleanup job could misclassify an unreferenced object | Keep deletion disabled; require reconciliation, approved retention, bounded service-role process, and a referenced-object exclusion |
| T7 | Spoofed MIME metadata or malformed image is accepted | MIME allowlist and Storage metadata checks | Client-supplied content type is not proof of actual file content | Validate file signature and safely decode/inspect content where technically feasible; retain server-side size/type checks |

### Risk classification

The principal concern is **resource consumption and lifecycle integrity**, not public disclosure. The private bucket and incident-membership checks are meaningful existing protections and must be preserved.

The reservation change is a defense-in-depth improvement: it moves authorization and resource accounting ahead of Storage object creation and ties the resulting object to one intended message operation. It does not by itself prove that image content is harmless, protect a compromised account, or provide backup/recovery.

## Policy decisions and sizing method

Do not hard-code a daily image count, concurrent-upload count, aggregate byte budget, reservation lifetime, or cleanup grace period solely because a proposed number sounds conservative. OWASP and Supabase guidance establish the need for authorization, validation, and resource controls; they do not prescribe TACResQ-specific numeric quotas.

### Controls that are requirements

- Preserve the existing 5 MiB per-object Storage ceiling.
- Preserve the JPEG/PNG/WebP allowlist unless a separately reviewed product requirement changes it.
- Require an authenticated, incident-scoped reservation for each new object path.
- Derive the profile identity from auth.uid(), never from a caller-selected profile ID.
- Make reservation accounting atomic under concurrent requests.
- Keep the bucket private and do not grant client UPDATE or DELETE.
- Make same-operation retries idempotent and verify the existing object before finalization.
- Do not automatically delete attachments as part of this security change.

### Controls that require measured/configured values

The following values should be configuration, not undocumented constants:

- Maximum in-flight reservations per profile and incident.
- Aggregate bytes or upload count over a defined rolling window.
- Reservation expiry and any narrowly scoped finalization/retry grace.
- Orphan reconciliation age threshold.
- Any future retention period.

Before selecting production values, collect representative usage from legitimate incident workflows or controlled pilot tests: image count per incident, bytes per incident, upload duration on weak mobile networks, retry frequency, and concurrent upload behavior. Use percentiles and an explicit safety margin; document the measurement period, sample size, and operational storage budget. If reliable usage data is unavailable, begin with per-object enforcement and atomic reservation state, keep aggregate quotas disabled/configurable, and monitor before tightening them.

Do not use client-reported byte counts as authoritative quota usage. They may support early rejection only. Reserve a conservative amount for in-flight operations or reconcile against server-recorded Storage metadata after upload.

Reservation expiry must prevent a new Storage INSERT after expiry. It must not automatically invalidate an object that was already uploaded and is awaiting message finalization. Define a separate, bounded finalization transition that verifies the existing object and preserves idempotency.

Orphan cleanup grace and attachment retention are separate policy decisions. No cleanup scheduler was present in the connected project during the earlier read-only inspection. Keep cleanup disabled until an owner-approved retention policy, reconciliation evidence, bounded deletion process, and restore/backup implications are established.

## Risk-to-control-to-test traceability

| Risk | Control | Required verification evidence |
|---|---|---|
| T1: repeated pre-message uploads | Reservation required before Storage INSERT; configurable accounting | A member cannot create a second distinct unreserved object; configured quota is enforced server-side |
| T2: concurrent quota bypass | Transaction-level serialization or locked quota row | Parallel reservation test proves no oversubscription |
| T3: cross-user/incident authorization | auth.uid()-derived profile and current membership check | Wrong-profile, unrelated-account, and removed-member denial tests |
| T4: reservation replay/mismatch | Exact path/type/state binding and one-time use | Wrong path, MIME, incident, profile, expiry, and second-use tests |
| T5: ambiguous upload retry | Deterministic operation identity, no overwrite, authoritative object verification | Upload-success/response-lost and HTTP 409 mismatch tests |
| T6: evidence deletion | Cleanup disabled; referenced-object exclusion required for any future cleanup | No deletion path in this change; reconciliation reports referenced/unreferenced separately |
| T7: malformed content | Server-side size/type checks plus content-signature validation where supported | Spoofed MIME, invalid signature, malformed image, and oversize tests |

A test result must be recorded against the exact commit and environment. Static source-contract checks alone are not sufficient evidence for actual Supabase Storage behavior.

## Initial configurable defaults and rationale

The first implementation uses the existing image/message policy as its baseline rather than introducing a new daily quota:

- Reservation lease: 900 seconds (15 minutes), configurable in the private policy row. This is an operational retry window, not a claim that 15 minutes is an industry standard. It may be adjusted after mobile upload-duration and retry measurements.
- Upload reservations: at most 20 new image operations per profile per rolling minute, configurable up to the existing 20-message-per-minute ceiling. This reuses the current message flood-guard ceiling and moves the check before Storage accepts the object; it does not add a separate daily image quota.
- Per-object size remains 5 MiB. No aggregate daily byte budget or concurrent-reservation ceiling is introduced without usage evidence.
- The reservation is idempotent for the same profile/client-message UUID and binds one exact object path, MIME type, and declared size.
- Expiry blocks a new Storage INSERT. It does not block finalization of an object that Storage already accepted while the reservation was valid.
- The policy row is private and is not exposed as an Admin setting. Changing it requires an authorized database-operator action and should be recorded operationally.

This baseline constrains pre-message image upload attempts to the existing message-rate policy while preserving the option to tune the retry lease. It does not eliminate orphan objects or replace a future evidence-based aggregate storage budget.

## Current implementation status in this PR

Implemented on the PR branch:

- Private reservation ledger and private policy row.
- Authenticated reservation RPC deriving profile identity from auth.uid().
- Same per-profile advisory transaction lock used by message sends.
- Idempotent reservation keyed to the client message UUID.
- Storage INSERT policy requiring a live matching reservation and matching caller/path/MIME/size.
- Image-message finalization requires the matching reservation and consumes it in the same database transaction as message insertion.
- Android now performs reserve → Storage upload → message finalization.
- SQL Storage authorization regression fixture and static source-contract checks updated.
- Read-only attachment inventory extended to distinguish unreserved orphan objects, uploaded-but-unfinalized objects, active/expired reservations without objects, consumed reservations without message references, message rows whose object is missing, and object metadata that differs from its message or reservation. It emits aggregate counts/bytes only and performs no cleanup. The active-reservation byte total is based on the size declared at reservation time, not a server-verified Storage object size; use the object totals for stored-byte reporting.

Still requiring CI and isolated Supabase acceptance:

- Full migration replay and RLS policy execution against the repository's PostgreSQL harness.
- Android debug/release compilation and existing unit tests.
- Real Storage upload/download, ambiguous retry, delayed finalization, and authorization tests in an isolated non-production Supabase project.
- Content-signature and safe image-decoding validation are not implemented by this reservation change. MIME and size metadata remain enforced, but client-provided MIME metadata alone is not proof of file content.
- No production migration, Storage policy, or live bucket setting has been applied.

## Implementation contract

1. Add a private reservation ledger that is not directly readable or writable by ordinary client roles.
2. Add a narrowly granted authenticated reserve RPC. Derive the actor profile from auth.uid(); validate incident access; derive the deterministic object path on the server.
3. Serialize reservation accounting so concurrent requests cannot exceed configured limits.
4. Update Storage INSERT authorization to require a matching eligible reservation for the exact bucket/object path, caller, incident, and allowed MIME type.
5. Update Android to reserve first, upload second, and finalize third. Preserve the existing client UUID and no-overwrite retry behavior.
6. Update the image-message RPC to independently recheck incident access and object metadata, then consume the matching reservation in the same transaction as message creation.
7. Preserve text messaging, image download authorization, private bucket behavior, and current participant access rules.
8. Add migration replay, grant, RLS, authorization, concurrency, idempotency, retry, and storage-policy tests. Run real upload/download acceptance in an isolated non-production Supabase project before production rollout.
9. Do not add an automatic cleanup worker, retention deletion, public bucket, client UPDATE/DELETE permission, or paid service in this PR.

## Panel/technical-review explanation

TACResQ already restricts incident-chat attachments to authorized participants, private storage, approved image types, and a 5 MiB per-object maximum. The identified weakness is that Storage accepts an object before the corresponding chat message is committed, while the message rate limit runs only after upload. Therefore, the message limit does not constrain distinct objects created by uploads that never become messages.

The proposed reservation makes the backend authorize and account for each intended upload before Storage accepts it. It binds the authenticated user, incident, exact object path, and file type to one operation; the message transaction then consumes that operation only after independently verifying the stored object. Concurrency and retry tests demonstrate that the control cannot be bypassed by simultaneous requests or ambiguous mobile network failures. This is a targeted defense-in-depth improvement to resource management and attachment lifecycle integrity, not a claim that the existing private bucket is publicly exposed or that an attack has already occurred.

## Verification boundary

This design is based on the inspected Android upload path, SQL RPC and Storage policies, existing contract tests, and a point-in-time read-only inventory. It does not establish an observed attack rate or justify numeric quotas. No production migration, Storage policy, bucket setting, cleanup job, object deletion, or Android runtime behavior is changed by this document.
