# Admin account suspension: security design and implementation gates

**Current branch status (28 Nov 2026):** The website Admin User Management page displays Active/Suspended state, exposes reason-required Suspend/Reactivate actions for ordinary non-Admin accounts, and shows recent lifecycle audit events. Migration `20261125020926_admin_account_lifecycle_compare_and_set.sql` grants the narrow transition RPC to `authenticated`; the function independently checks active Admin + AAL2/TOTP, expected-state compare-and-set, self/Admin-target exclusion, active-assignment protection, and atomic immutable audit insertion. Clean PostgreSQL/local Supabase replay, RPC-grant contracts, and mocked Admin browser coverage have passed on reviewed branch heads. This does **not** establish that the migration is deployed to hosted Supabase or that every direct RPC, Storage, Edge Function, and legacy Laravel/Sanctum path enforces current account state. Global session revocation and connected-project deployment are not part of this transition. No production account was suspended and no connected Supabase project or user data was changed.

## Goal

Give an authorized administrator a reversible way to suspend and reactivate a non-admin TACResQ account. Suspension must stop the account from using the Android app, direct Supabase APIs, Storage, and any Laravel bridge path. It must not be confused with responder/driver duty status.

The website implementation exposes only ordinary non-Admin suspend/reactivate through a narrow authenticated RPC. Administrator provisioning, promotion/demotion, account deletion, password changes, and arbitrary Auth metadata editing remain outside the portal. The RPC is executable by the authenticated role only because its own SECURITY DEFINER body enforces active Admin + AAL2/TOTP and the reviewed lifecycle constraints; that grant does not establish complete account-state enforcement on every application path or hosted deployment.

## Repository facts confirmed

- The Admin web directory returns bounded account identity, role, operational duty status, account_status, and creation time. It keeps account state separate from duty status and offers lifecycle actions only for ordinary non-Admin accounts.
- The existing role-change RPC is a SECURITY DEFINER function. It locks the target profile, blocks Admin targets, blocks role changes while a field unit has an active incident, writes an append-only admin_audit_logs record, and now shares an advisory serialization lock with the staged account lifecycle transition.
- private.tacresq_actor_role() and private.tacresq_auth_profile_id() now resolve only active profiles and acquire a profile share lock. Other callable RPCs still require explicit review.
- Some RLS policies use that helper, but several RPCs and policies query public.profiles directly. Therefore changing only the helper or adding a UI toggle would not establish complete suspension enforcement.
- Android and the web portal call Supabase directly for important operations. Laravel is not a universal API gateway and cannot be treated as the only enforcement point.
- The Web Admin initializes a browser Supabase client and directly calls Admin RPCs; its MFA gate is not the authorization boundary. Preserve the database-enforced AAL2/TOTP wrapper and restrictive audit-log policy when adding active-account checks.
- The repository also contains a separate legacy Laravel Sanctum authentication path backed by Laravel User records and personal-access tokens. Its identity mapping/support status relative to Supabase Auth users and public.profiles is not established by source review. Do not assume a Supabase suspension state automatically blocks those tokens, and do not join identities by email. Before claiming complete account suspension, explicitly decide whether the legacy Sanctum routes remain supported and, if so, define their own server-side account-state/revocation contract.
- The optional Render Supabase bridge validates the Supabase bearer token and forwards that same caller JWT to allowlisted PostgREST RPCs. It is useful transport defense, but does not itself resolve current profile account state; database enforcement remains mandatory.
- Incident-chat Storage policies now use the account-aware profile/chat helpers and have same-valid-JWT upload/download denial coverage. Other Storage buckets still require inventory and tests.
- The existing audit model supports role-change events, and the investigation feed has a fixed source allowlist. The foundation migration adds a private-to-clients, RLS-enabled, append-only `account_lifecycle_audit_logs` ledger. The trusted state-transition RPC is implemented but not exposed to clients; investigation-feed coverage is still outstanding.

## Security model

### 1. Separate account access from duty status

The foundation migration now adds a constrained account state to `public.profiles`, independent of `duty_status`:

- active
- suspended

New profiles default to active. A suspension record includes suspended_at, suspended_by, and a required reason. Reactivation records the actor, timestamp, and reason in the immutable administrative audit history. Do not reuse off_duty or change the person's role to simulate suspension.

Only non-admin profiles can be suspended or reactivated through the portal. The internal transition RPC rejects Admin targets entirely; administrator provisioning/recovery remains trusted-operator-only, consistent with the existing provisioning boundary.

### 2. Enforce at every authorization boundary

A status column is not an authorization control by itself. Before enabling the UI, enumerate and test all of these boundaries:

- RLS SELECT/INSERT/UPDATE/DELETE policies on every application table.
- Every authenticated-callable SECURITY DEFINER RPC, including incident creation, editing, assignment, status, chat, notifications, FCM token registration, and location writes.
- Supabase Storage read and upload policies for incident attachments.
- The Laravel Supabase bridge and other backend routes that accept a Supabase bearer token.
- Admin RPCs, including directory and investigation endpoints.

The foundation migration adds `private.tacresq_current_account_active()` with a fixed `pg_catalog` search path and an explicit authenticated-only EXECUTE grant. It reads and share-locks the current profile row rather than trusting JWT metadata; the Admin transition RPC and role-change wrapper use a shared advisory lock, and the transition locks the target profile `FOR UPDATE`. The helper is used by restrictive policies on public tables with direct authenticated privileges and by a row-level write guard on every public table, which rejects writes from suspended end-user JWTs even inside SECURITY DEFINER functions. The actor-role, chat profile-ID, incident-assignee, and Admin MFA helpers now fail closed for suspended callers; Admin MFA still requires AAL2/TOTP. Notification list/detail, incident-chat history, claimable-incident discovery, field-unit directory, SOS create/cancel idempotency, and incident-claim entry paths are guarded. Incident-chat Storage INSERT/SELECT policies fail closed through account-aware helpers, with same-valid-JWT upload/download/chat tests. The notification Edge Function rejects suspended actors and filters recipients to active profiles. Other Storage buckets, remaining idempotent/read RPCs, Edge Function runtime tests, and bridge routes remain under review. The Admin transition RPC is implemented but its EXECUTE grant is revoked from client roles until those gates are closed.

### 3. Treat already-issued JWTs correctly

Supabase access tokens are JWTs and remain usable until expiration unless the receiving API checks current server-side state. A ban or refresh-token revocation alone is not an immediate database/API authorization barrier. Therefore:

- The database must reject suspended callers on each protected request, including requests made with a still-valid token.
- A trusted server-side Auth/session revocation step may be added as defense in depth, but it must not replace database enforcement.
- Do not place a service-role key in Android, browser code, or a public runtime config.
- Do not claim that a user is instantly signed out on every device unless session/token behavior is actually verified.

Supabase references reviewed:
- User sessions and JWT/session behavior: https://supabase.com/docs/guides/auth/sessions
- User management and account-access caveats: https://supabase.com/docs/guides/auth/managing-user-data
- Supabase Auth sign-out and access-token expiry: https://supabase.com/docs/guides/auth/signout

### 4. Protect operational continuity

A responder or driver assigned to an active incident must not be suspended through the ordinary Admin flow. The RPC must lock the profile and inspect both incidents.assigned_to and incident_assignments for non-terminal incident states inside the same transaction. It must fail closed with a clear message. The operator must first complete or safely reassign the active incident using the existing Android dispatch workflow.

Do not automatically cancel incidents, remove assignments, alter responder status, or reassign emergency work as a side effect of suspension.

### 5. Audit requirements

Each successful state change records:
- actor profile and stable actor identity snapshot;
- target profile ID;
- action (account_suspended or account_reactivated);
- previous and new account state;
- required 10–500 character reason;
- server-generated timestamp;
- correlation/event identifier if consistent with the existing investigation ledger.

The audit write and state mutation must be in one database transaction. Clients cannot insert, update, or delete the audit event. Failed attempts must not be described as successful events. The investigation feed must expose only minimized metadata and must be protected by the existing Admin TOTP/AAL2 boundary.

## Required test matrix before UI rollout

1. Requester: active account can sign in and perform allowed actions; suspended account cannot perform any protected database/API action using a still-valid JWT.
2. Responder and driver: same active/suspended checks, including location, assignment, chat, and Storage paths.
3. Dispatcher: active/suspended checks on incident queue and assignment operations.
4. Admin: only an active Admin with TOTP/AAL2 can view the directory or change another non-admin account; suspended Admin is not an ordinary UI-manageable target.
5. Active incident: assigned field unit suspension is rejected atomically; no incident, assignment, or duty status is changed.
6. Concurrent requests: suspension racing with an operation has deterministic transaction/locking behavior; no action slips through after the state change commits.
7. Audit: state transition and audit event commit together; client writes are denied; reasons and target IDs are accurate; no sensitive profile/incident data leaks.
8. Storage: suspended caller cannot read or upload incident attachments using an unexpired access token.
9. Reactivation: active-state checks resume only after the reactivation transaction commits; prior audit history remains immutable.
10. Clean migration replay, RPC grant contract, RLS contract, Android JVM tests, web build, Laravel bridge tests, and a disposable PostgreSQL integration test.

## Rollout gates

- First produce an inventory of all public RPCs, direct table policies, Storage policies, and Laravel Supabase bridge calls, with each entry marked guarded/tested.
- The schema/audit foundation and internal transition RPC exist on this branch. Complete remaining enforcement and tests before granting RPC execution or adding UI controls.
- Run all checks on a clean disposable PostgreSQL database and use synthetic accounts only.
- Do not apply the migration to either connected Supabase project without explicit approval.
- Only after the server-side contract is complete, add directory status, confirmation dialog, required reason, stale-target revalidation, and audit feed labels.
- Keep PR draft until CodeRabbit/manual review and manual browser/device acceptance are complete.

## Explicit non-goals

- No account deletion or irreversible data removal.
- No Admin role promotion/demotion or self-suspension.
- No automatic incident cancellation, release, or reassignment.
- No new paid service, third-party auth product, or stack change.
- No fake "force sign-out all devices" success message.

### Regression gate — audit identity preflight

Added a source regression gate for the local audit-identity migration: it must take an ACCESS EXCLUSIVE lock before checking unattributed rows, fail with an explicit audit-preserving remediation message, and only then enforce NOT NULL. Rollback only relaxes nullability; it does not rewrite audit history.

### Regression gate — append only trigger

Added explicit regression assertions for the append-only audit trigger's UPDATE/DELETE and TRUNCATE coverage, SQLSTATE, and ordinary invoker execution. This checks source structure only; database-owner and privileged-role bypass behavior still needs a disposable PostgreSQL test.

### Regression gate — recorder input

Added recorder invariants ensuring audit appends require an open transaction, distinct positive actor/target identifiers, a bounded nonblank reason, and one of the two supported transitions. The recorder must not mutate account state or update/delete existing audit rows.

### Regression gate — admin reauth

Added source-order checks proving local Admin reauthentication locks and re-reads the account before checking role, suspension, password, and one-time TOTP consumption. The contract also pins the five-attempt throttle, 15-minute failure window, Retry-After, and no-store response.

### Regression gate — sanctum guard headers

Added a narrow middleware regression gate for early suspended-account denial, HTTP 403, no-store/private caching, and the intentionally retained self-service logout bypass. No new bypass route is introduced.
