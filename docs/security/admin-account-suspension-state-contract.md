# Account suspension — account-state and operational-continuity contract

**Status (PR #347 current implementation):** the website Admin directory now displays Active/Suspended state and exposes reasoned suspend/reactivate controls for non-Admin accounts. The authenticated lifecycle RPC is granted to `authenticated` and requires an active AAL2/TOTP Admin, rejects self-change and Admin targets, checks the expected current state under a target-row lock, blocks suspension while active incident assignments remain, and writes the immutable audit event in the same transaction. This document retains the staged design history below; it is not evidence that the migrations have been applied to a live Supabase project. No production account has been changed and no live migration has been applied.

## Implemented database foundation

Migrations `20261123090000` through `20261123170000` currently:

- Add `profiles.account_status` (`active` or `suspended`, NOT NULL, default `active`), plus current suspension timestamp, actor profile-ID snapshot, and bounded reason.
- Enforce state/metadata invariants: active profiles have no current suspension metadata; suspended profiles require timestamp, actor snapshot, and a 10–500 character reason.
- Add an RLS-enabled, client/service-role-inaccessible, append-only `account_lifecycle_audit_logs` ledger used by the atomic Admin transition RPC.
- Add `private.tacresq_current_account_active()`, which reads the current profile row, does not trust JWT metadata, and takes a profile `FOR SHARE` lock.
- Add restrictive `FOR ALL` policies to RLS-enabled public tables with direct authenticated table/column privileges.
- Add a row-level write guard to every public table. Writes carrying a suspended end-user JWT are rejected, including writes inside SECURITY DEFINER functions that bypass RLS.
- Make actor-role, chat profile-ID, incident-assignee, and Admin MFA helpers account-aware. Admin MFA still requires AAL2 and a TOTP AMR entry.
- Guard notification list/detail, incident-chat history, and claimable-incident discovery read RPCs; the field-unit directory now rejects suspended dispatchers and excludes suspended units.
- Make incident-chat Storage policy identity checks fail closed for suspended callers. The Storage replay contract now tests prior-object download denial, new upload denial, chat-history denial, and chat-message denial with the same still-valid JWT.
- Update the notification Edge Function to reject suspended actors and filter recipients to active profiles before FCM token lookup.
- Implement the Admin account-status transition RPC with AAL2/TOTP, a shared advisory lock coordinated with Admin role changes, expected-state compare-and-set, actor/target row locking, self-change and Admin-target exclusion, active-assignment protection, idempotent transitions, and atomic immutable audit insertion. The final website migration grants the narrowly scoped RPC to authenticated clients; its internal checks remain authoritative.

All existing profiles receive the default `active` state. Role, duty status, identity, incident assignments, and account status remain separate concepts.

## Remaining before suspension can be enabled

- Review every remaining read/idempotent RPC path and every callable public/private function; some less common operations may still have pre-write/idempotent response branches that need an explicit early active-state guard.
- Inventory every Storage bucket and prove upload/download denial for each account-owned bucket.
- Run isolated Supabase Edge Function tests for actor denial, suspended-recipient filtering, and zero FCM/Google OAuth calls on denied paths.
- Verify every Laravel/Sanctum bridge route and establish immutable Auth-user mapping before claiming account-state enforcement there.
- Complete the remaining same-valid-JWT and concurrency validation for any newly discovered RPC, Storage, Edge Function, or bridge path; do not infer live-provider behavior from source-contract tests alone.
- Add investigation-feed rendering for lifecycle audit events.
- Add disposable Supabase concurrency tests proving that operations either complete before the suspension lock wins or observe the suspended state afterward.
- Complete manual website acceptance against an isolated test project after the reviewed migrations are applied there; verify suspend, protected-operation denial with the same still-valid JWT, audit history, reactivation, and restoration of access.

## Lifecycle state contract

The implemented columns are:

- `account_status`: `active` or `suspended`, NOT NULL, default `active`.
- `suspended_at`: nullable timestamp; set for the current suspension and cleared on reactivation.
- `suspended_by`: nullable profile-ID snapshot of the administrator who applied the current suspension; cleared on reactivation.
- `suspension_reason`: nullable bounded text (10–500 characters); cleared on reactivation while the immutable audit ledger retains the historical reason.

Only the trusted Admin transition transaction may change lifecycle fields. Authenticated clients have no direct update privilege for them. The narrowly scoped transition RPC is executable by authenticated clients but independently enforces active Admin identity, AAL2/TOTP, expected-state compare-and-set, target restrictions, active-assignment protection, and atomic audit insertion.

## Suspension transaction and operational continuity

The future Admin RPC must perform, in one transaction:

1. Authenticate the actor through the existing Admin authorization helper, preserving AAL2 and TOTP.
2. Lock and re-read the actor and target profile rows in a deterministic order.
3. Reject self-suspension and protect the last eligible active Admin from suspension.
4. Verify the target's current state and apply an idempotent transition.
5. Insert an immutable audit event containing actor, target, action, timestamp, and bounded reason.
6. Commit both state and audit event together; any audit failure rolls back the state change.

Suspension must not silently cancel incidents, clear assignments, rewrite duty status, or transfer operational ownership. Existing active incidents remain visible to authorized active participants and dispatchers according to the incident continuity policy. A suspended assignee cannot perform protected writes; dispatchers must explicitly resolve reassignment through the normal audited workflow.

Reactivation changes only account lifecycle state. It must not silently restore duty availability, assignment eligibility, sessions, or device trust.

## Authorization semantics

- Database authorization reads current account state; JWT claims alone are not the source of truth.
- Direct public-table RLS and public-table write triggers are implemented, but read-only/idempotent RPCs still require a complete final inventory.
- Supabase Storage incident-chat attachment checks use the active-aware profile/chat helpers; other buckets still require inventory and tests.
- The notification Edge Function now checks actor and recipient account state because service-role queries bypass RLS; isolated runtime tests and deployment verification remain outstanding.
- Any privileged server bridge must validate the original Supabase caller and current account state; it must not replace caller identity with service-role identity.
- Legacy Sanctum sessions remain a separate access domain until an immutable identity mapping and lifecycle semantics are proven.
- Narrow self-service cleanup operations (for example, unregistering one's own obsolete FCM token) may be considered explicit suspended-account exceptions, but only with dedicated tests and no access to another account's data.

## Required tests before release

1. Active account: all currently permitted paths retain existing behavior.
2. Suspended account with a still-valid JWT: protected RPC reads and writes are denied, including idempotent retries.
3. Suspended account: direct table reads/writes are denied by effective RLS/grants and row triggers.
4. Suspended account: incident-chat Storage upload and download are denied.
5. Suspended account: notification Edge Function actor invocation is denied.
6. Suspended recipient: no FCM registration is selected for delivery.
7. Concurrent suspension and incident mutation: row-lock order is deterministic; no protected mutation commits after suspension wins the authorization boundary.
8. Audit insertion failure: suspension/reactivation rolls back.
9. Last-Admin and self-suspension protections hold under concurrent Admin requests.
10. Active incident assignments remain intact; reactivation does not auto-assign or set a field unit on duty.
11. Existing AAL2 and TOTP requirements remain restrictive and cannot be bypassed by policy composition.
12. Sanctum tokens do not gain or lose Supabase access through email-based matching.

## Release gate

Keep production use gated on isolated-project migration and manual acceptance. The PR implementation and automated checks do not authorize applying migrations to a connected production project, changing real accounts, or claiming immediate invalidation of already-issued JWTs.
