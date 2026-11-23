# Account suspension authorization review — batch 24: chat and Storage authentication chain

**Evidence:** repository migrations on `main`, especially `20261108092703_incident_chat_image_attachments.sql`, `20261122210000_incident_chat_upload_reservations.sql`, and `20261122213000_reconcile_incident_chat_upload_policy.sql`.  
**Status:** source trace and implementation boundary; not runtime suspension enforcement.

## Current profile identity helper

`private.tacresq_auth_profile_id()` is a stable SECURITY DEFINER SQL function. It returns `public.profiles.id` for the row whose `auth_user_id = auth.uid()`. It has a fixed `pg_catalog` search path and authenticated EXECUTE, with PUBLIC and anon revoked.

The current `public.profiles` schema has no account lifecycle field. Therefore this helper establishes identity only; its non-null return must not be treated as proof that the account is active. When the lifecycle schema is introduced, choose and document whether this helper returns NULL for suspended callers or whether a separate active-profile helper is added. Preserve any narrowly scoped cleanup operation that needs identity resolution, but do not let identity-only access authorize protected reads or writes.

## Chat membership helper

`private.tacresq_auth_can_access_incident_chat(bigint)` calls the profile identity helper and then `private.tacresq_can_access_incident_chat(incident_id, profile_id)`. It currently returns false only when no profile resolves or membership fails. It does not inspect account lifecycle state.

This helper is a direct authenticated-callable private SECURITY DEFINER endpoint and a shared authorization predicate for Storage policies. The eventual account-state check must be inside the trusted authorization chain, not only in Android UI or the Laravel bridge. Direct authenticated invocation with a still-valid JWT must return false for a suspended account.

## Storage policy chain

The original image migration creates two authenticated Storage policies for the private `incident-chat-attachments` bucket:

- INSERT checks bucket identity, deterministic incident/profile/UUID path shape, caller profile in path segment two, and incident chat access.
- SELECT checks bucket identity and incident chat access for the incident in path segment one.

The reservation migration replaces INSERT with stricter metadata checks and a call to `private.tacresq_upload_reservation_allows_insert(text,text,bigint)`. The reservation validator resolves profile identity first, then checks the staged enforcement flag. When that flag is false, it returns true without requiring a reservation; the outer Storage policy still checks path, MIME, size, and chat membership. When true, it requires an unconsumed, unexpired reservation matching caller, path, MIME, size, and incident membership.

The reconciliation migration restores the same compatibility behavior for databases whose earlier migration payload lacked the flag. Neither INSERT nor SELECT policy currently has an independent active-account predicate. The compatibility-mode early return is therefore a critical place where a future active-account check must execute first.

## Required enforcement ordering

When account-state schema and helper are introduced:

1. Resolve the Auth subject from `auth.uid()`; never accept a client-supplied profile ID as caller identity.
2. Check current database account state before the reservation compatibility return.
3. Require active account state in the shared chat-access predicate used by both Storage INSERT and SELECT.
4. Keep the existing bucket, path, MIME, byte-size, reservation, expiry, consumption, and incident-membership checks unchanged.
5. Preserve historical objects; suspension must deny access without deleting incident evidence.
6. Keep Storage UPDATE and DELETE unavailable unless a separately reviewed requirement justifies them.
7. Recheck state in message history, text send, image reservation, and image finalization RPCs. A Storage policy alone does not protect RPC response data or message creation.

## Required disposable-stack tests

Use a valid, unexpired JWT acquired while each synthetic account is active, then suspend the profile through a trusted fixture/admin path without refreshing the JWT.

- Active participant can upload a valid image and read it.
- Suspended participant cannot upload in both reservation-enforcement modes.
- Suspended participant cannot download a previously uploaded object.
- Suspended participant cannot call the profile/chat helper to obtain an authorization-positive result.
- Suspended participant cannot reserve, send, finalize, or read chat history.
- Unrelated active participant remains denied by incident membership.
- Existing active participants retain access to historical objects.
- Invalid MIME, path, size, expired reservation, consumed reservation, and object replacement remain rejected.
- A concurrent suspension and upload/message operation obey the documented profile-lock serialization boundary; no protected operation may commit after suspension wins.
- Repeat with the exact same still-valid JWT; do not use token expiry/revocation as the test mechanism.

The current SQL replay's lightweight PostgreSQL harness omits the Supabase Storage schema. The repository's Storage SQL tests cover database policy predicates in replay, but real Storage HTTP tests on an isolated Supabase stack are still required for end-to-end assurance.

## Decision

Do not change the current compatibility flag or enable strict reservation mode as a substitute for account suspension. Do not introduce a partial active-state guard before the lifecycle schema and complete authorization matrix exist. This batch identifies the exact shared guard points and negative tests required for the eventual implementation.
