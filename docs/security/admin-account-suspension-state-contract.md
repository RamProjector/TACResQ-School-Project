# Account suspension — account-state and operational-continuity contract

**Historical status snapshot (superseded 28 Nov 2026):** The initial foundation phase described a revoked transition RPC and no Admin UI. The current branch has since added the User Management Active/Suspended directory state, reason-required suspend/reactivate dialog, recent lifecycle history, and the authenticated-only transition RPC grant in migration `20261125020926_admin_account_lifecycle_compare_and_set.sql`. The RPC itself independently checks active Admin + AAL2/TOTP and enforces the reviewed transition constraints. A read-only inspection of the connected Supabase project confirms migration `20261125020926_admin_account_lifecycle_compare_and_set` is present in the hosted migration ledger and the deployed RPC grants EXECUTE to `authenticated` while denying `anon` and `PUBLIC`, with SECURITY DEFINER and `search_path=pg_catalog`. Complete same-valid-JWT enforcement across all direct RPC, Storage, Edge Function, and legacy Laravel/Sanctum paths remains incomplete/unverified. No connected production account was suspended.

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
- Implement an internal Admin account-status transition RPC with AAL2/TOTP, a shared advisory lock coordinated with Admin role changes, actor/target row locking, self-suspension and Admin-target exclusion, idempotent transitions, and atomic immutable audit insertion. The RPC is not yet executable by client roles.

All existing profiles receive the default `active` state. Role, duty status, identity, incident assignments, and account status remain separate concepts.

## Remaining before suspension can be enabled

- Review every remaining read/idempotent RPC path and every callable public/private function; some less common operations may still have pre-write/idempotent response branches that need an explicit early active-state guard.
- Inventory every Storage bucket and prove upload/download denial for each account-owned bucket.
- Run isolated Supabase Edge Function tests for actor denial, suspended-recipient filtering, and zero FCM/Google OAuth calls on denied paths.
- Verify every Laravel/Sanctum bridge route and establish immutable Auth-user mapping before claiming account-state enforcement there.
- Complete remaining read/idempotent RPC and server-bridge coverage, then grant EXECUTE on the already-implemented Admin transition RPC only after the full same-valid-JWT release matrix passes.
- Add investigation-feed rendering for lifecycle audit events.
- Add disposable Supabase concurrency tests proving that operations either complete before the suspension lock wins or observe the suspended state afterward.
- Add the Admin directory state, confirmation/reason UI, and suspended-account UX only after backend coverage is complete.

## Lifecycle state contract

The implemented columns are:

- `account_status`: `active` or `suspended`, NOT NULL, default `active`.
- `suspended_at`: nullable timestamp; set for the current suspension and cleared on reactivation.
- `suspended_by`: nullable profile-ID snapshot of the administrator who applied the current suspension; cleared on reactivation.
- `suspension_reason`: nullable bounded text (10–500 characters); cleared on reactivation while the immutable audit ledger retains the historical reason.

Only the trusted Admin transition transaction may change lifecycle fields. Authenticated clients have no direct update privilege for them, and the transition RPC itself currently has no client EXECUTE grant.

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

Keep this PR draft until the remaining RPC, Storage, Edge Function, bridge, Admin transition, and concurrency boundaries are implemented and tested on one exact commit. Do not apply the migrations to a connected project or expose Admin controls before that gate is met.


## Current branch status correction — 2026-11-28

The earlier sections below describe the initial foundation-only stage and must not be read as the current UI/grant state. On the current Admin IPS branch:

- The Admin User Management page displays each ordinary account's Active/Suspended state and exposes reason-required Suspend/Reactivate actions; Admin accounts remain excluded from this workflow.
- The browser calls `api_admin_set_account_status` with the expected prior state. The final branch migration `20261125020926_admin_account_lifecycle_compare_and_set.sql` grants EXECUTE to `authenticated`, not PUBLIC/anon, and the function enforces active Admin + AAL2/TOTP, self/Admin-target exclusion, target row locking, compare-and-set, active-assignment protection, and atomic append-only lifecycle audit.
- Clean PostgreSQL/local Supabase migration replay, RPC privilege/runtime contracts, and the mocked Admin Playwright flow have passed on reviewed branch heads.
- This is not a production deployment claim. The hosted migration ledger has not been confirmed to include these changes. End-to-end suspension enforcement remains incomplete across all callable RPCs, Storage buckets, Edge Functions, and legacy Laravel/Sanctum identity paths. A status transition does not instantly revoke already-issued access-token JWTs.
- The Security page should describe this as **implemented in branch / hosted rollout unverified / complete enforcement incomplete**, not as an unavailable RPC or absent Admin workflow.
- No production account or connected Supabase/Render configuration was changed by this status correction.
