# Admin account suspension: Storage and device/session review (batch 5)

## Scope and evidence

Reviewed the branch's incident-chat Storage migration, device registry foundation migration, device metadata constraints, device privilege contract, device-security implementation plan, and the existing attachment lifecycle review. This is a repository-source review; it is not a fresh live Storage catalog or HTTP acceptance test.

No connected Supabase project, Storage bucket, Auth session, device record, secret, Edge Function, or production data was changed.

## 1. Incident-chat Storage

The migration creates the private `incident-chat-attachments` bucket with a 5 MiB limit and JPEG/PNG/WebP allowlist. It defines authenticated INSERT and SELECT policies only:

- INSERT requires the correct bucket, deterministic incident/profile/UUID path shape, caller profile ID in the second path component, and current incident-chat access through `private.tacresq_auth_can_access_incident_chat(...)`.
- SELECT requires the correct bucket and current incident-chat access for the incident in the first path component.
- No authenticated UPDATE or DELETE policy is created by this migration.
- The image-message RPC independently derives the sender from `auth.uid()`, rechecks chat access, validates the exact path and MIME/size metadata, confirms the object exists, then inserts the message idempotently.

**Suspension gap:** both Storage policies delegate to the chat-access helper. The reviewed helper resolves profile identity and chat membership/role but does not independently establish current account state. Therefore, a still-valid JWT belonging to a suspended account could continue to satisfy Storage authorization while its incident relationship remains valid. A suspension implementation must guard the shared helper (or a single active-account predicate used by it) so both upload and download fail closed. Do not rely on hiding chat UI or revoking only Laravel tokens.

**Important separate risk:** the existing attachment lifecycle review records that uploads occur before the message RPC and the Storage INSERT policy has no matching per-profile upload quota/rate control. That is a storage-abuse/orphan-lifecycle issue, not proof of cross-account disclosure. Do not mix a quota/cleanup change into account suspension without its own design, grace period, and tests. Keep cleanup disabled and do not grant broad client DELETE.

**Required negative tests:** active participant upload/read succeeds; suspended participant upload/read fails with a still-valid JWT; unrelated account and removed participant remain denied; no authenticated UPDATE/DELETE appears; valid same-path retry remains safe; existing referenced objects remain readable to authorized active participants.

## 2. Device/session registry

The registry migration creates five backend-managed tables: `user_devices`, `device_sessions`, `device_challenges`, `device_security_events`, and `device_integrity_checks`.

Confirmed source controls:

- RLS is enabled on all five tables.
- All table privileges are revoked from PUBLIC, anon, authenticated, and service_role before narrowly granting service_role access.
- service_role receives SELECT/INSERT/UPDATE on mutable device/session/challenge records, and SELECT/INSERT only on security events and integrity summaries.
- Device/session/challenge/integrity composite foreign keys bind device IDs to the same profile.
- Challenges store SHA-256 hashes, are single-use by design, and have a database-enforced maximum lifetime of ten minutes.
- Metadata and JSON payload sizes have database constraints.
- A dedicated SQL contract checks RLS, client table/sequence denial, ownership constraints, bounds, and append-only event/integrity privileges.

**Suspension implication:** direct client access to these tables is already denied, so there is no authenticated table policy to extend for this registry. However, a future trusted verifier or Laravel/Edge Function using service_role bypasses caller-scoped RLS. It must explicitly validate the original authenticated user, resolve the current profile state server-side, and reject suspended accounts before issuing/consuming challenges, registering devices, binding sessions, updating last-seen/trust status, or accepting integrity results. A service-role query must not be mistaken for a query running as the caller.

The repository's device-security plan also records that the current Laravel Supabase bridge validates a bearer token with Auth `getUser()` but does not expose verified JWT `session_id` claims; a service-role database request does not automatically carry the caller's `auth.uid()`/`auth.jwt()`. The device registry is backend-only and no complete client-facing challenge verifier should be assumed from the schema alone.

**Do not** make device integrity a prerequisite for emergency SOS, responder claim, dispatch, location, ordinary reads, notifications, or authorized chat in the initial rollout. Device trust is a risk signal, not authentication or a replacement for role/MFA/assignment checks.

## 3. Existing notification Edge Function boundary

The repository's device-security research records one deployed `tacresq-notify` Edge Function with JWT verification enabled and a recovered source mirror in the repository. It validates the bearer token with Supabase Auth, then uses a service-role client for profile/incident/FCM operations. That function is not a device verifier and its service-role queries are not automatically protected by caller RLS.

For suspension, every active caller path through this function must be checked explicitly: verify the caller token, resolve the profile's current account state using the privileged client, and reject a suspended account before any event-specific action or recipient/token access. Do not assume JWT validation alone means the account is still active. Also trace deployed callers before changing or disabling this legacy/parallel notification path; do not deploy or reconfigure it in this PR.

## 4. Enforcement design consequences

1. Introduce one trusted, database-owned active-account predicate with a fail-closed definition and a clear profile-to-Auth-user mapping. Do not infer suspension from role, duty status, device trust, or JWT age.
2. Apply it to the shared incident-chat access helper so both Storage policies and chat RPCs inherit the same decision.
3. Add an explicit server-side active-account check to any service-role Edge/Laravel path before privileged reads or writes; caller-scoped RLS does not protect service-role operations.
4. Keep the device registry backend-only. When a verifier is actually implemented, require verified subject and session claims, atomically consume one-time challenges, and check account state in the same trusted transaction boundary.
5. Preserve emergency continuity: do not auto-cancel incidents, release assignments, change duty state, or require device integrity as a condition for emergency reporting.
6. Add still-valid-JWT negative tests for Storage, chat, notification Edge Function and every future verifier endpoint, alongside existing role/ownership tests.

## Findings classification

| Surface | Current protection | Suspension result |
|---|---|---|
| Incident-chat Storage INSERT | Private bucket, path/profile/chat membership | Not guarded by account state |
| Incident-chat Storage SELECT | Private bucket, current chat membership | Not guarded by account state |
| Device registry tables | RLS + no client grants; service-role-only | No direct client path; future privileged verifier must check caller state |
| Device challenge/session workflow | Schema foundation only; no complete verifier assumed | Not an active client path established by this review |
| `tacresq-notify` Edge Function | JWT validation + manual role/ownership checks + service-role data access | Must explicitly check current account state before privileged operations |

## Verification boundary

The source and existing SQL contract support the schema/privilege conclusions above. This batch did not execute a fresh migration replay, live Storage request, signed-in HTTP test, Edge Function request, or account suspension. Do not treat these findings as deployment verification.
