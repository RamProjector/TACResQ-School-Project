# Admin account suspension: adversarial regression and transaction test plan (batch 9)

## Purpose

Turn the source-review findings into a testable acceptance contract before any account-state migration is written. This is a test specification only. It does not introduce an account-state column, enforcement helper, RPC, policy, or runtime behavior.

## Test fixture model

Use disposable PostgreSQL only. Seed synthetic Auth users and profiles for:
- requester A and requester B;
- responder A and responder B;
- driver A;
- dispatcher A and dispatcher B;
- active Admin A and suspended Admin B;
- a profile with an active incident assignment and another with no active assignment.

Keep each Auth UUID distinct and never match identities by email. Give each role only the privileges and MFA claims needed for its test. All tests for suspended behavior must retain a valid, unexpired JWT subject so they prove server-side current-state enforcement rather than merely testing an invalid token.

## A. Account state and administrative transition

| Case | Actor | Target | Expected |
|---|---|---|---|
| Suspend ordinary requester | Active Admin A with AAL2 + TOTP | Active requester | State changes once; one immutable audit event |
| Suspend responder/driver with active incident | Active Admin A | Assigned field unit | Rejected atomically; profile, duty, incident, assignments, notifications unchanged |
| Suspend already suspended account | Active Admin A | Suspended account | Explicit no-op/error; no duplicate transition event |
| Reactivate suspended requester | Active Admin A | Suspended requester | State changes to active; one audit event |
| Reactivate already active account | Active Admin A | Active account | Explicit no-op/error; no duplicate event |
| Suspend Admin target | Active Admin A | Admin B | Rejected; trusted-operator-only boundary retained |
| Self-suspend | Active Admin A | Admin A | Rejected |
| Missing/short/long reason | Active Admin A | Ordinary account | Rejected before mutation |
| Non-Admin, no TOTP, AAL1, or missing JWT | Unprivileged actor | Ordinary account | Rejected; no audit success event |
| Role-change RPC on suspended target | Active Admin A | Suspended account | Does not reactivate or mutate account state implicitly |

Audit/state atomicity assertion: force the audit insert to fail in a disposable transaction and prove the account-state update rolls back. Also prove that failed authorization attempts do not create successful state-transition audit rows.

## B. Still-valid JWT denial matrix

For every role, acquire a valid token while the profile is active, then mark the profile suspended through the test fixture's trusted setup path without refreshing/revoking the JWT. Reuse that same JWT against every relevant surface.

| Surface | Requester | Responder / driver | Dispatcher | Admin |
|---|---|---|---|---|
| Profile self-read and allowed self-update | Deny protected access | Deny protected access | Deny protected access | Deny protected access |
| Incident list/detail and status history | Deny | Deny | Deny | Deny unless explicitly permitted break-glass design is approved |
| Create/edit/cancel incident | Deny | Not applicable / deny | Deny dispatch actions | Deny Admin operations |
| Claim/assign/progress/duty operations | Not applicable | Deny | Deny | Deny Admin operations |
| GPS write and live-location read | Not applicable | Deny | Deny dispatcher location access | Deny |
| Chat list/send/image RPC | Deny | Deny | Deny | Deny unless explicitly approved |
| Storage attachment upload/download | Deny | Deny | Deny | Deny unless explicitly approved |
| Notifications read/mark-read and FCM registration | Deny | Deny | Deny | Deny |
| Admin directory/audit/investigation | Deny | Deny | Deny | Deny for suspended Admin |

Test both direct Android-style PostgREST requests and browser Admin direct RPC requests. The optional Laravel bridge may be tested as defense in depth, but passing bridge middleware must never be sufficient to authorize a database request.

## C. Direct table RLS and policy composition

For each direct table listed in batch 8, test SELECT, INSERT, UPDATE, and DELETE separately against the effective post-replay catalog. Include:
- self vs other user;
- requester vs assigned team member vs unrelated user vs dispatcher;
- active vs suspended caller with same role/relationship;
- active vs suspended target profile;
- row ownership before and after UPDATE;
- existing permissive and restrictive policy combinations.

The expected universal rule is that a suspended caller cannot use ordinary application privileges, even if another permissive policy grants the row. Implementations must verify effective policy composition; a permissive active-account policy is not a universal deny. Preserve existing restrictive AAL2/TOTP Admin policies. Any cleanup exception (e.g. caller deleting only their own FCM token) must be isolated to that operation and tested not to grant SELECT/INSERT/UPDATE.

## D. Storage and chat

Use real Storage API requests against a disposable Supabase stack or isolated project:
1. Active requester and assigned field unit upload a valid JPEG/PNG/WebP object to their allowed incident path.
2. Unrelated profile, non-member, and removed assignee are denied.
3. Suspended existing participant with the same still-valid JWT is denied upload and download.
4. Existing object remains stored after suspension; do not delete historical incident evidence.
5. Active authorized participant can still read the existing object.
6. Invalid path, MIME, oversized payload, object replacement, and message-finalization mismatch remain rejected.
7. Message finalization remains idempotent; retrying the same client message UUID does not create duplicate message/audit records.

No broad Storage DELETE/UPDATE permission should be introduced to make suspension work.

## E. Service-role and separate identity domains

- Notification Edge Function: valid JWT for suspended caller is rejected after a current profile-state lookup; suspended dispatcher recipients and suspended FCM token owners are filtered before delivery.
- Any future device challenge verifier: service-role operations check the original verified Auth subject and current profile state before challenge issue/consume, device registration, session binding, last-seen updates, or integrity-result persistence. No verifier is assumed to exist today.
- Render bridge: caller JWT is forwarded unchanged; database still rejects suspended state.
- Laravel Sanctum: first resolve whether this separate user/token domain is supported and establish immutable identity linkage. If retained, test login issuance and every protected route with a suspended Laravel account and test logout-all/revocation. Do not treat a Supabase profile suspension as proof that a Sanctum token is revoked.

## F. Concurrency and operational continuity

Run two concurrent transactions in disposable PostgreSQL:
- Suspension acquires the target profile lock before a protected operation begins.
- Protected operation acquires the required lock before suspension begins.
- Two Admins attempt the same state transition concurrently.
- Suspension races with assignment, incident progress, chat send, and location write.

Expected contract: lock ordering is documented and deterministic; once suspension commits, every later protected operation using an old JWT is rejected. If an operation wins the serialization order and commits before suspension, it remains a valid pre-suspension action and is preserved in history. There must be no ambiguous partial state, duplicate audit transition, or silent cancellation/reassignment. Recheck both legacy `incidents.assigned_to` and normalized `incident_assignments` inside the suspension transaction.

## G. Minimum validation gates

1. Clean migration replay from repository history.
2. Static authorization-surface inventory after replay: every callable public/private function, table privilege, policy mode, and Storage policy classified.
3. SQL tests running as `authenticated` with synthetic JWT claims; do not rely on superuser DO blocks to prove RLS.
4. Explicit active-vs-suspended negative tests for each role and each protected boundary.
5. Existing RPC grant, restrictive Admin MFA, Storage, chat, notification, assignment, GPS, and idempotency regressions pass.
6. Android JVM tests and web build remain green; manual UI/device acceptance is separate and must not be claimed from compilation.
7. Laravel bridge tests only prove the bridge path; direct Supabase tests remain mandatory.
8. Live connected-project migration application remains prohibited until the user explicitly approves it.

## Exit criteria

Do not implement the account-state migration until:
- all effective post-replay authorization surfaces have an owner and a concrete guard location;
- the separate Sanctum identity-domain decision is documented;
- a disposable PostgreSQL test can create a suspended account and demonstrate denial using the same still-valid JWT;
- concurrency tests prove no post-commit operation slips through;
- emergency assignments and historical records remain unchanged;
- exact-head CI and human review pass.

This plan intentionally prefers a complete, evidence-backed enforcement layer over a premature Suspend button.
## Anonymous callable review gate

After each clean PostgreSQL migration replay, run tools/check_account_suspension_authorization_surface.sql and classify every effective anon-callable routine in both public and private schemas. Record the intended unauthenticated use, schema exposure, owner, SECURITY DEFINER/INVOKER mode, search_path, nested helper calls, and read/write effects. Check inherited and PUBLIC EXECUTE access as well as explicit role grants.

Do not infer that the anonymous surface is empty from the Python source contract. The catalog query must be executed against the disposable replay database, and every newly discovered or changed signature must be reviewed before account lifecycle transition grants are considered.