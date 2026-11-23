# Account suspension — account-state and operational-continuity contract

**Status:** schema foundation committed on `security/admin-account-suspension-runtime-foundation`; no suspension transition RPC is enabled. Restrictive RLS now blocks direct authenticated access to public tables with client grants when the current profile is suspended; callable RPC, Storage, Edge Function, and bridge enforcement remain outstanding.  
**Branch:** `security/admin-account-suspension`

## Existing schema constraint

The current `public.profiles` definition has identity/contact fields, role, duty status, and timestamps, but no account lifecycle/status column. Role and duty status are operational attributes, not account-access state. Do not overload either field to represent suspension.

The current legacy Laravel Sanctum identity is also not proven to map immutably to Supabase `auth.users`; matching email addresses is not an acceptable identity link.

## Proposed profile state

When implementation is authorized by complete authorization coverage, add an explicit constrained lifecycle field:

- `account_status`: `active` or `suspended`, NOT NULL, default `active`.
- `suspended_at`: nullable timestamp.
- `suspended_by`: nullable profile-ID snapshot of the administrator who applied the current suspension.
- `suspension_reason`: nullable bounded text; never store secrets or unnecessary sensitive details.

Constraint invariants:

1. Active accounts have all suspension metadata cleared.
2. Suspended accounts have `suspended_at` and `suspended_by` set.
3. Only the trusted Admin suspension/reactivation transaction may change lifecycle fields.
4. Role, duty status, profile identity, incident assignment, and account status remain separate concepts.
5. A suspended account must not regain access merely by refreshing or retaining a previously issued, otherwise-valid JWT.

The current reason bound is 10–500 characters. Reactivation clears current-state suspension metadata; the immutable audit ledger retains the transition reason. System-initiated suspensions are not in scope for this first implementation.

## Suspension transaction and continuity

A future Admin RPC must perform, in one transaction:

1. Authenticate the actor through the existing Admin authorization helper, preserving AAL2 and TOTP.
2. Lock and re-read the actor and target profile rows.
3. Reject self-suspension and protect the last eligible Admin from suspension.
4. Verify the target's current state and apply an idempotent state transition.
5. Insert an immutable audit event containing actor, target, action, timestamp, and bounded reason.
6. Commit both state and audit event together; any audit failure rolls back the state change.

Suspension must not silently cancel incidents, clear assignments, rewrite duty status, or transfer operational ownership. Existing active incidents remain visible to authorized active participants and dispatchers according to the incident continuity policy. A suspended assignee must not perform new protected writes; dispatchers must explicitly resolve reassignment through the normal audited workflow.

Reactivation changes only account lifecycle state. It must not silently restore duty availability, assignment eligibility, sessions, or device trust.

## Authorization semantics

- Database authorization must read current account state on every protected request; JWT claims alone are not the source of truth.
- All public and authenticated-callable private RPCs, direct table/RLS paths, views, triggers, and Storage policies must be covered.
- The service-role notification Edge Function must validate the actor's current state and filter recipients by current state because service-role queries bypass RLS.
- Any privileged server bridge must validate the original Supabase caller and current account state; it must not replace caller identity with service-role identity.
- Legacy Sanctum sessions remain a separate access domain until an immutable identity mapping and its lifecycle semantics are proven.
- Narrow self-service cleanup operations (for example, unregistering one's own obsolete FCM token) may be considered explicit suspended-account exceptions, but only with dedicated tests and no access to another account's data.

## Required tests before enforcement or UI

1. Active account: all currently permitted paths retain their existing behavior.
2. Suspended account with a still-valid JWT: protected RPC reads and writes are denied.
3. Suspended account: direct table reads/writes are denied by effective RLS/grants.
4. Suspended account: incident-chat Storage upload and download are denied.
5. Suspended account: service-role Edge Function actor invocation is denied.
6. Suspended recipient: no FCM registration is selected for delivery.
7. Concurrent suspend and incident mutation: row-lock order is deterministic; no protected mutation commits after suspension wins the authorization boundary.
8. Audit insertion failure: suspension/reactivation rolls back.
9. Last-Admin and self-suspension protections hold under concurrent Admin requests.
10. Active incident assignments remain intact; reactivation does not auto-assign or set a field unit on duty.
11. Existing AAL2 and TOTP requirements remain restrictive and cannot be bypassed by policy composition.
12. Sanctum tokens do not gain or lose Supabase access through email-based matching.

## Release gate

The foundation migration is committed but must not be applied to a connected project until reviewed. Do not add Admin controls or claim suspension is active until the callable/direct-access matrix is complete and the disposable PostgreSQL, Storage HTTP, Edge Function, and bridge tests pass. Keep this PR draft until those gates are evidenced on one exact commit.
