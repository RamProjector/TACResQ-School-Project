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

## Policy decisions required before implementation

The following values must be agreed rather than invented in a migration:

- maximum concurrent in-flight reservations per profile;
- upload count or byte budget per profile and/or incident, including the accounting window;
- reservation expiry duration;
- orphan cleanup grace period;
- attachment retention period, if any;
- whether a quota exhaustion response should block all further image sends or provide a retry-after time.

The 5 MiB per-object limit and JPEG/PNG/WebP allowlist are existing technical constraints, not substitutes for an aggregate quota policy.

## Migration and rollout sequence

1. Approve the quota and retention values above.
2. Add a private reservation ledger and narrowly granted reserve/finalize RPCs.
3. Add concurrency-safe reservation accounting and regression tests.
4. Update the Storage INSERT policy to require the reservation.
5. Update Android to reserve first, upload second, then call the existing image-message RPC. Preserve the deterministic UUID/path and no-overwrite retry behavior.
6. Update the image-message RPC to consume the reservation transactionally.
7. Replay all migrations from a clean database and run grant, RLS, path, concurrency, and idempotency tests.
8. Perform real upload/download, duplicate-path, wrong-account, removed-member, expired-reservation, upload-success/message-failure, and quota-exhaustion acceptance tests in a non-production Supabase project.
9. Only after a separately approved retention policy and cleanup design, add bounded orphan cleanup. Keep cleanup disabled until then.

Do not apply a production migration as part of this design-only change.

## Required regression matrix

- authenticated incident member can reserve only for an incident they can currently access;
- caller cannot reserve for another profile;
- invalid incident, UUID, MIME type, or size is rejected;
- concurrent reservations cannot exceed the configured quota;
- wrong profile, incident, MIME type, path, or expired reservation cannot upload;
- reservation cannot authorize a second distinct object path;
- same-path retry does not overwrite and remains idempotent;
- HTTP 409 with mismatched object metadata or content identity cannot silently create a message;
- message RPC independently rejects a missing or mismatched Storage object;
- successful upload plus message consumes the reservation once;
- upload success plus RPC timeout can safely retry with the same identity;
- removed incident member cannot reserve, upload, or read;
- referenced objects are never cleanup candidates;
- cleanup failure is retryable and does not broaden client privileges.

## Verification boundary

This is an architecture proposal based on the current Android upload path, Storage policy model, image-message RPC, and read-only live configuration inspection. It does not implement a reservation RPC, migration, Android API change, quota, cleanup job, or production setting. No production data or object was changed.
