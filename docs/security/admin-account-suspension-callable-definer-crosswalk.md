# Account suspension — callable definer entry-point crosswalk

**Branch:** `security/admin-account-suspension`  
**Review status:** source-traced crosswalk plus a staged lifecycle helper; not proof of runtime denial.  
**Catalog baseline:** 24 authenticated-callable public SECURITY DEFINER functions in the clean migration replay. The upload-reservation RPC is included in the current signature inventory; the referenced batches document source-level checks and remaining account-state gaps.

## Public SECURITY DEFINER entry points

| # | Exact callable signature | Review coverage | Required active-account boundary |
|---:|---|---|---|
| 1 | `api_admin_analytics_summary(timestamptz,timestamptz)` | Batch 2 — Admin/audit | Shared trusted Admin MFA helper; preserve AAL2 and TOTP |
| 2 | `api_admin_list_audit_logs(integer)` | Batch 2 — Admin/audit | Shared trusted Admin MFA helper; preserve direct-table restrictive AAL2 policy |
| 3 | `api_admin_list_audit_logs_page(integer,integer,text,text,timestamptz,timestamptz,bigint,bigint)` | Batch 2 — Admin/audit | Shared trusted Admin MFA helper |
| 4 | `api_admin_list_investigation_events(integer,integer,text,text,text,timestamptz,timestamptz,bigint,bigint,bigint)` | Batches 1 and 2 — Admin investigation | Shared trusted Admin MFA helper |
| 5 | `api_admin_list_users_page(integer,integer,text,text)` | Batch 2 — Admin/audit | Shared trusted Admin MFA helper |
| 6 | `api_admin_set_user_role(bigint,text,text)` | Batch 2 — Admin/audit | Shared trusted Admin MFA helper; independently validate target state and retain transactional audit |
| 7 | `api_cancel_my_incident(bigint)` | Batch 1 — requester lifecycle | Lock/resolve caller profile, then reject suspended actor before returning or mutating incident |
| 8 | `api_claim_incident(bigint)` | Batch 1 — field claim | Recheck current state under existing lock order before assignment mutation |
| 9 | `api_claimable_incidents()` | Batch 1 — field claim | Reject or return no protected queue data for suspended caller |
| 10 | `api_create_incident_v4(text,text,double precision,double precision,real,text,timestamptz,text,uuid)` | Batch 1 — requester lifecycle | Check current state before create or idempotent replay response |
| 11 | `api_mark_notification_read(bigint)` | Batch 4 — profile/notifications/FCM | Check active recipient before read-state mutation |
| 12 | `api_my_incident_messages(bigint,integer,bigint)` | Batch 1 — chat | Check active account before membership/history retrieval |
| 13 | `api_my_notification(bigint)` | Batch 4 — profile/notifications/FCM | Check active recipient before exposing notification data |
| 14 | `api_my_notifications(integer)` | Batch 4 — profile/notifications/FCM | Check active recipient before exposing notification list |
| 15 | `api_register_fcm_token(text)` | Batches 1 and 4 — FCM | Reject suspended caller before token registration/reassignment |
| 16 | `api_reserve_incident_image_upload(bigint,text,bigint,uuid)` | Batch 5 — chat image upload reservation | Reject suspended caller before creating or replaying a reservation; preserve chat membership and reservation validation |
| 17 | `api_send_incident_image_message(bigint,text,text,text,bigint,uuid)` | Batch 1 and Batch 5 — chat/Storage | Check active sender at finalization; independently guard Storage INSERT/SELECT |
| 18 | `api_send_incident_message(bigint,text,uuid)` | Batch 1 — chat | Check active sender before idempotency lookup and message insertion |
| 19 | `api_set_incident_claimable(bigint,boolean)` | Batch 3 — dispatch | Check active dispatcher after locked actor re-read, before mutation |
| 20 | `api_units()` | Batch 3 — dispatch | Check active dispatcher and exclude suspended field-unit targets from eligibility |
| 21 | `api_update_my_incident(bigint,text,text,double precision,double precision,real,text,timestamptz,text,boolean)` | Batch 1 and Batch 13 — requester edit/validation | Check active requester before protected reads/mutations; preserve validation, edit cooldown, and audit/notification atomicity |
| 22 | `api_admin_list_account_lifecycle_events(integer,integer)` | Batch 2 — lifecycle audit reader | Require active Admin with AAL2/TOTP before bounded history reads; keep direct ledger table grants revoked |
| 23 | `api_unregister_fcm_token(text)` | Batch 4 — profile/notifications/FCM | Authenticated-only SECURITY DEFINER cleanup RPC; pinned search path; delete only caller's own token; transaction-local exception is limited to public.fcm_tokens DELETE and cleared before return; direct table DELETE remains revoked |
| 24 | `api_admin_set_account_status(bigint,text,text,text)` | Website Admin lifecycle | Authenticated-only SECURITY DEFINER RPC; requires active AAL2/TOTP Admin; rejects self-change and Admin targets; locks and compares target state; writes immutable audit event transactionally |

## Website Admin transition (client-callable)

The website invokes `public.api_admin_set_account_status(bigint,text,text,text)` through the Supabase authenticated client. The follow-up migration grants EXECUTE only to `authenticated`; the function requires AAL2 with TOTP and an active Admin profile, prevents self-change and Admin targets, locks the target, compares the expected prior status under that lock, and commits the status change with an immutable audit event. The same migration updates the shared Admin MFA helper to reject suspended Admins and adds `account_status` to the bounded website directory response. Anonymous and PUBLIC execution remain revoked.

`public.api_admin_set_account_status(bigint,text,text)` is a separate staged SECURITY DEFINER overload and is **not client-callable**: EXECUTE remains revoked from `PUBLIC`, `anon`, and `authenticated`. It is excluded from the authenticated-callable inventory. The four-argument website RPC above is the client-callable transition entry point.

## Private callable definer functions

The private-function inventory is a separate callable surface. The clean-replay contract locks twelve authenticated-callable private SECURITY DEFINER helpers, including `private.tacresq_auth_profile_id_for_token_cleanup()` and `private.tacresq_current_account_active()`, and rejects `anon`/`PUBLIC` EXECUTE on every classified callable definer. A private function with authenticated EXECUTE is directly callable even if it is also used by a public wrapper. The source reviews explicitly identify these examples:

- `private.tacresq_auth_profile_id()` and `private.tacresq_auth_can_access_incident_chat(bigint)` — Batch 1. The profile-ID helper now returns NULL for suspended callers; Storage policies that rely on it fail closed. The directly callable incident-assignee helper also checks active status and locks the caller profile. Notification list/detail and incident-chat history RPCs now use this helper directly; claimable-incident discovery requires an active profile under a share lock.
- `private.tacresq_assign_incident_team_guarded(bigint,bigint[])`, `private.tacresq_is_incident_assignee(bigint)`, `private.tacresq_record_location_v2(...)`, `private.tacresq_active_responder_locations()`, and `private.tacresq_dispatch_update_incident_status(bigint,text)` — Batch 3. The dispatcher progress implementation is now separately guarded by `20261124070000_account_lifecycle_dispatch_progress_guard.sql`: it requires an active dispatcher and holds a `FOR SHARE` profile lock for the transaction. Its public `api_update_incident_status(bigint,text)` wrapper remains SECURITY INVOKER; the private implementation is the privileged write boundary.
- `private.tacresq_require_admin_mfa()` is intentionally not directly executable by client roles; it is a trusted internal authorization helper used by Admin wrappers — Batch 2.
- `private.tacresq_current_account_active()` is directly executable by `authenticated`, reads and share-locks the caller's profile lifecycle state, and returns false for missing/suspended profiles. It is used by restrictive direct-table RLS policies; its direct-callable grant is included in the twelve-function private-definer inventory.

The full exact private signature list and function bodies are emitted by `tools/check_account_suspension_authorization_surface.sql` during clean replay. Do not treat this short list as the complete private catalog. Every function with client EXECUTE must be checked directly; every internal definer helper must be traced from its caller and checked for authorization assumptions.

## Invoker functions are not exempt

The clean replay also reports 51 authenticated-callable public SECURITY INVOKER functions. Their access can still expose or mutate data through RLS, views, or helper calls. Examples documented in Batch 3 include `api_assign_incident_team`, `api_assign_incident`, `api_update_incident_status`, `api_all_incidents`, `api_my_incidents`, `api_set_duty_status`, `api_record_location_v2`, and `api_active_responder_locations`.

A complete release matrix must therefore include all 75 public callable functions, not only the 24 public definers, and reconcile them with direct table privileges, RLS policy composition, views, Storage, Auth triggers, Edge Functions, Android, web, and Laravel.

## Chat and Storage shared authorization chain

[Batch 24: chat and Storage authentication chain](admin-account-suspension-rpc-review-batch-24-chat-storage-authentication-chain.md) traces the shared identity and membership helpers, Storage INSERT/SELECT policies, and the compatibility-mode reservation return. [Batch 23](admin-account-suspension-rpc-review-batch-23-private-callable-definer-source-trace.md) records the directly callable private-definer paths, including the active-account resolver.

## Current coverage and remaining gap

This crosswalk connects the 24 public definer signatures to source-review batches. It is not yet a full transitive call graph: the clean-replay SQL currently emits function source, but no automated parser proves every nested call edge or authorization predicate. Manual source tracing and executable negative tests remain required.

Before adding the account-state migration:
1. Finish exact coverage for all 51 invoker functions and all authenticated-callable private functions.
2. Trace each public and private entry point to nested helpers and trigger-mediated effects; document the actual lock order and transaction boundary.
3. Map every Android, web, and Laravel direct operation to its effective grant, RLS policy, view, RPC, or Storage enforcement point.
4. Keep the clean-replay inventory contract synchronized with exact callable signatures and effective `anon`/`PUBLIC` EXECUTE grants; treat active-account enforcement as a separate runtime-test requirement.
5. Add disposable PostgreSQL tests using the same still-valid JWT before and after suspension, including direct private RPC invocation, direct RLS, Storage, and concurrent incident operations.

No connected Supabase project was queried or modified. No account-state migration or UI is introduced by this review.


## Repeatable authorization review procedure

Run `tools/check_account_suspension_authorization_surface.sql` only against a disposable PostgreSQL replay of the complete migration set. Save its output with the exact branch commit SHA and PostgreSQL version; do not paste live object names, user data, or secrets into a public issue.

For each replay, reconcile these inventories independently:

1. **Public callables:** compare exact `regprocedure` signatures, execution mode, effective `authenticated` EXECUTE grant, function settings, and body. A changed overload is a new entry point even when the function name is unchanged.
2. **Private callables:** review both authenticated-callable functions and every private SECURITY DEFINER helper. Trace nested calls from each public wrapper; do not infer that a private helper is safe merely because it is not directly executable by `authenticated`.
3. **Relations and views:** compare owner, RLS enabled/forced flags, effective table and column privileges, policy command, role audience, USING and WITH CHECK expressions, and view definitions/options. Table ownership and BYPASSRLS membership are separate bypass questions.
4. **Triggers and Auth hooks:** compare enabled mode, target relation, trigger definition, function owner/settings, and function body. Treat trigger side effects as part of the initiating RPC's transaction and lock-order review.
5. **Other grants:** review sequences, schema USAGE/CREATE, role inheritance, and global/schema-scoped default ACLs. Default privileges matter because they can expose newly created objects after this snapshot.
6. **Storage:** run `tools/check_storage_bucket_inventory.sql` separately. A bucket or policy change requires an explicit access/retention contract and same-valid-JWT negative tests; matching a policy name alone is not sufficient.

A release reviewer should record additions, removals, overload changes, owner/grant changes, and predicate changes as explicit review items. Any unexplained new callable, view, trigger, Storage bucket, permissive policy, or inherited elevated role is a blocker—not an automatic approval. The catalog is discovery evidence; it does not parse a complete transitive call graph or prove runtime denial.


## Follow-up implementation — dispatcher assignment and GPS write entry points

The next call-graph review found three more direct SECURITY DEFINER boundaries whose original implementations checked role/assignment state but did not independently require an active profile:

- `public.api_assign_incident_team(bigint,bigint[])` is now a SECURITY INVOKER wrapper that delegates to `private.tacresq_assign_incident_team_guarded(bigint,bigint[])`. The private guard checks and share-locks an active dispatcher; the preserved `private.tacresq_assign_incident_team(bigint,bigint[])` implementation has client EXECUTE revoked. The legacy single-member alias routes through the guarded public entry point.
- `private.tacresq_release_incident_assignment(bigint,bigint)`: now checks and share-locks an active dispatcher before delegating to the preserved release transaction. The internal implementation is no longer client-callable.
- `private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamptz)`: now checks and share-locks an active responder/driver before delegating to the existing coordinate validation, assignment-state validation, and rate-limited insert. The internal writer is no longer client-callable.

These changes intentionally preserve the established assignment, release, location validation, rate-limit, and duty-status business logic rather than rewriting it. The clean-replay contract checks effective function security/volatility/search-path settings, active-state predicates, share locks, client grants, internal-function revocation, and the legacy assignment alias. The Python contract ties each migration to those SQL assertions.

The contracts are source/replay definitions only until the disposable PostgreSQL migration replay runs successfully. No live incident, assignment, location log, duty status, or connected Supabase project was changed.


## Follow-up implementation — dispatcher roster and queue reads

The next read-path review found that `public.api_units()` exposed the responder/driver directory through a SECURITY DEFINER function that checked the dispatcher role without requiring an active profile. Its existing roster query is now preserved as a private internal implementation with client EXECUTE revoked. The public wrapper requires an active dispatcher and takes a `FOR SHARE` profile lock before delegation.

The related `public.api_all_incidents()` queue function remains SECURITY INVOKER but now uses `private.tacresq_actor_role()`, the shared active-aware role resolver, instead of a raw profile-role lookup. This keeps both roster and queue reads aligned with the account lifecycle boundary.

The SQL replay contract verifies wrapper attributes, active-state locking, private implementation permissions, and the invoker queue helper. The Python source contract ties those assertions to the migration. These are not claims of live PostgreSQL replay or deployment validation.



## Follow-up implementation — guarded dispatcher team assignment

Migration `20261124080000_account_lifecycle_team_assignment_guard.sql` moves the previous public SECURITY DEFINER assignment implementation into a private internal routine, revokes client EXECUTE on that implementation, and recreates the public signature as an active-dispatcher wrapper. The wrapper takes a `FOR SHARE` lock on the caller's active profile before delegating, so the suspension transition's profile lock serializes against team assignment.

The legacy single-member `api_assign_incident(bigint,bigint)` alias now routes through the guarded team-assignment RPC. This prevents the alias from bypassing the new lifecycle boundary while preserving the existing assignment implementation. The migration's Python/source and PostgreSQL replay contracts check the wrapper, alias, active predicate, lock, and internal grant revocation.

This is migration/source-contract coverage only until the complete disposable PostgreSQL replay and same-valid-JWT denial matrix execute successfully.

## Follow-up implementation — guarded assignment release

Migration `20261124090000_account_lifecycle_assignment_release_guard.sql` preserves the prior assignment-release transaction as a private internal routine and revokes its direct client EXECUTE grant. The callable private wrapper now reads and share-locks the caller's profile, requires an active dispatcher, and only then delegates to the preserved implementation.

The guard prevents a suspended dispatcher with an unexpired JWT from releasing another responder/driver or changing assignment-derived duty availability through this private callable. Existing release behavior remains inside the preserved implementation rather than being reimplemented in the wrapper.

The source and replay contracts check the active-state predicate, lock, and internal grant revocation. They do not replace execution of the disposable PostgreSQL same-JWT matrix.

## Follow-up implementation — guarded responder GPS writer

Migration `20261124100000_account_lifecycle_location_writer_guard.sql` moves the existing private GPS writer implementation behind a new guarded wrapper. The wrapper requires an active responder or driver and holds a `FOR SHARE` profile lock before delegating to the existing coordinate validation, assignment-state checks, timestamp bounds, and rate limiter. The moved internal writer is no longer directly executable by client roles.

This retains the established GPS validation and throttling behavior while making account lifecycle state an explicit authorization prerequisite. The same-valid-JWT test matrix still needs to exercise both allowed active writes and denied suspended writes against disposable PostgreSQL; source assertions alone do not prove runtime behavior.

## Follow-up implementation — guarded dispatcher directory and queue

Migration `20261124110000_account_lifecycle_dispatcher_directory_guard.sql` moves the previous `api_units()` SECURITY DEFINER query into a private internal routine, revokes client EXECUTE on that implementation, and adds an active-dispatcher wrapper with a profile `FOR SHARE` lock. This protects the field-unit directory, including responder contact and assignment-availability data, from suspended dispatchers.

The same migration updates `api_all_incidents()`, which remains SECURITY INVOKER, to authorize through the shared active-aware `private.tacresq_actor_role()` helper rather than a raw profile-role lookup. The PostgreSQL replay contract checks the wrapper security mode, active-state lock, private grant, and queue helper.

The read guards are staged migration changes; the disposable migration replay and same-valid-JWT runtime checks remain required before lifecycle enforcement can be enabled.

## Updated release-gate status after the November 24 callable guards

The crosswalk now records active-state enforcement at the dispatcher team-assignment, assignment-release, GPS location-write, field-unit directory, and dispatcher incident-queue boundaries. The related migration sequence is:

- `20261124070000_account_lifecycle_dispatch_progress_guard.sql`
- `20261124080000_account_lifecycle_team_assignment_guard.sql`
- `20261124090000_account_lifecycle_assignment_release_guard.sql`
- `20261124100000_account_lifecycle_location_writer_guard.sql`
- `20261124110000_account_lifecycle_dispatcher_directory_guard.sql`

These additions reduce known callable gaps but do not complete the full 72-public-callable inventory or the private transitive call graph. Before enabling account transitions, still require: a fresh complete catalog replay; explicit review of every newly discovered callable and nested helper; disposable PostgreSQL same-valid-JWT tests for every guarded path; isolated Edge Function tests proving denied paths make no OAuth/FCM calls; Laravel/Sanctum identity-boundary verification; Storage policy and object-operation tests; and concurrency tests for lifecycle transitions racing with assignment and location operations.

No connected Supabase project was queried or modified, no live migration was applied, and no account transition RPC grant was enabled by these documentation commits.


## November 24 catalog-level replay assertions

The PostgreSQL foundation replay now has explicit catalog contracts for the dispatcher callable boundaries added in the November 24 sequence. These assertions inspect the installed routine signatures, SECURITY DEFINER/SECURITY INVOKER mode, volatility, pinned search path, active-profile predicate, FOR SHARE lock, and effective anon/authenticated EXECUTE grants for:

- Team assignment wrapper, legacy one-member alias, and preserved internal implementation.
- Assignment-release wrapper and preserved internal implementation.
- GPS location writer wrapper and preserved internal implementation.
- Field-unit directory wrapper, preserved internal implementation, and incident queue.
- Dispatcher incident-progress routine.

These are assertions added to the disposable PostgreSQL replay script, not a claim that the replay has been run. They validate installed catalog state and function source shape; they do not by themselves prove every runtime denial path or concurrency interleaving. The same-valid-JWT mutation/read matrix and transaction-race tests remain release gates.

## Callable replay addition — notification claimable read

Notification and claimable-incident reads: exact signatures, pinned VOLATILE definer execution, authenticated-only grants, account-aware identity resolution, and active-profile lock for claimable discovery. Runtime denial still requires same-valid-JWT replay.

## Callable replay addition — assignment helper

Incident-assignee RLS helper: pinned SECURITY DEFINER, current auth.uid(), active predicate, FOR SHARE lock, and no anon/PUBLIC EXECUTE. Disposable-database RLS tests remain required.

## Callable replay addition — chat idempotency

Chat idempotency/upload reservation: exact signatures, active-aware profile resolver, incident-chat authorization, pinned definer attributes, and authenticated-only grants. Runtime order/race checks remain release gates.

## Callable replay addition — sos write

Requester SOS create/retry and cancellation: exact signatures, pinned VOLATILE SECURITY DEFINER attributes, active requester predicate, FOR UPDATE lock, and authenticated-only grants. Field-claim runtime denial and transaction races remain separate gates.

## Callable replay addition — fcm token

FCM registration versus suspended sign-out cleanup: registration must lock an active profile; unregister remains SECURITY INVOKER through a narrow stable cleanup helper. Catalog grants are asserted; RLS and authenticated runtime behavior remain unexecuted.
## November 24 anonymous callable inventory expansion

The read-only authorization catalog now lists effective EXECUTE access for the anon role independently in both public and private schemas. Each row includes the exact routine signature, owner, SECURITY DEFINER/INVOKER mode, configured search path, and the corresponding authenticated-role EXECUTE result. PostgreSQL's has_function_privilege evaluates effective privileges, including privileges inherited through role membership and grants to PUBLIC; this is intentionally broader than inspecting explicit ACL text alone.

This closes an inventory visibility gap, not an authorization finding by itself. A routine appearing in the anon result must be classified by intended public behavior, schema exposure, function body, nested calls, and data returned or changed. An empty result also needs to be observed from a fresh replay; source inspection cannot establish the live catalog. The inventory remains read-only and does not revoke grants or change any connected project.

The source contract requires both anon sections and checks that owner, execution mode, procedure/function kind, and effective authenticated access remain visible. The Python contract is not a substitute for executing the catalog SQL after clean migration replay.
## Per-signature authorization matrix — batches 23–26

The detailed review register is now split into four role/surface slices, indexed by `admin-account-suspension-signature-matrix-index.md`: requester lifecycle, field/dispatcher operations, messaging/notifications, and Admin/legacy compatibility. Each callable is a separate review unit, and each row distinguishes documented source guard evidence from outstanding replay/runtime proof. Unknown signatures are explicitly left unresolved for fresh catalog discovery rather than guessed.

The matrix highlights unresolved review items including requester incident editing, active responder coordinate reads, the precise duty-status mutation signature, Admin active-state verification, legacy requester RPC compatibility, and the data-free health RPC decision. These are review blockers, not claims that each is currently exploitable. The Python source contract now requires the matrix slices and their key signature/boundary entries to remain present.

No runtime test result is implied by the matrix. The clean migration replay, effective-grant inventory, same-valid-JWT tests, isolated Edge provider-spy tests, HTTP Storage tests, and concurrency suite remain mandatory.
## Requester incident-edit callable guard — November 24

`public.api_update_my_incident(bigint,text,text,double precision,double precision,real,text,timestamptz,text,boolean)` is now included in the account-lifecycle guard set. Migration `20261124120000_account_lifecycle_requester_edit_guard.sql` moves the pre-existing SECURITY DEFINER implementation into the private schema, revokes direct client-role execution, and installs an authenticated wrapper that locks the caller's active requester profile before delegation.

The wrapper preserves the public RPC signature and the internal implementation retains existing ownership, assignment, input validation, cooldown, review-confirmation, and edit-history behavior. The profile lock is held through the nested call so it serializes with account suspension on the same profile row.

Replay contract: `active_requester_edit_entry_contract` in `tools/test_account_lifecycle_foundation.sql`, with source coverage in `tools/test_account_lifecycle_callable_inventory_contract.py`. These checks are committed but have not been executed in this environment. Disposable PostgreSQL replay, same-valid-JWT suspended edit denial, and edit-versus-suspension concurrency tests remain required before the guard is runtime-verified.
## Legacy requester incident-create compatibility — November 24

The older invoker RPCs `public.api_create_incident(text,text,double precision,double precision,real)` and `public.api_create_incident_v2(text,text,double precision,double precision,real,text,timestamptz)` remain available for supported Android clients. Both INSERT into `public.incidents` and predate the v4 explicit active-profile check.

The generic write guard from `20261123120000_account_lifecycle_write_guard_triggers.sql` is installed on every public table, including `public.incidents`. Its trigger function calls the current-account active helper for end-user JWT operations, so the legacy INSERT paths receive lifecycle enforcement at the table boundary. The requester replay contract now explicitly verifies the incidents trigger binding; the Python source contract ties the legacy function definitions to the all-public-table trigger migration.

This is defense-in-depth, not a reason to skip RPC-level role/ownership review. Disposable PostgreSQL replay and active-versus-suspended calls to each legacy signature remain unexecuted release checks.

## Dispatcher claimability and responder-location read guards — November 24

Migration `20261124130000_account_lifecycle_claimability_and_responder_read_guard.sql` closes two dispatcher callable gaps found during the next signature review:

- `public.api_set_incident_claimable(bigint,boolean)` now includes `account_status = 'active'` in the locked dispatcher profile decision. It preserves the incident-then-profile lock order, lifecycle allowlist, no-op semantics, and same-transaction claimability audit.
- `private.tacresq_active_responder_locations()` is now an active-dispatcher wrapper with a profile `FOR SHARE` lock. The existing latest-fresh-location query is retained as `private.tacresq_active_responder_locations_internal()`, which is no longer client-callable.

The PostgreSQL replay contract checks exact signatures, wrapper attributes, active-state predicates, effective grants, and preserved audit/query boundaries. The Python source contract binds the migration to those replay assertions. Neither source inspection nor committed replay SQL is runtime proof: clean disposable PostgreSQL replay, same-valid-JWT suspended denial, and suspension-versus-read/mutation concurrency checks remain pending.


## Field-unit duty-status mutation guard — November 24

Migration `20261124140000_account_lifecycle_duty_status_guard.sql` updates `public.api_set_duty_status(text)` to require an active responder/driver in the locked profile read. The profile `FOR UPDATE` lock is held through the update, serializing duty changes with account suspension. Existing available/off-duty input limits and active-incident protection remain in place; suspension does not automatically alter duty status.

The PostgreSQL replay contract verifies invoker mode, volatility, search path, active-state and role predicates, profile locking, active-incident protection, and effective grants. The source contract ties the migration to the replay. Runtime same-valid-JWT denial and duty-change-versus-suspension concurrency remain unexecuted release checks.


The website Admin lifecycle transition is exposed through `public.api_admin_set_account_status(bigint, text, text, text)` to authenticated AAL2/TOTP Admins only. It rejects self-change and Admin targets, locks the target profile, and records the lifecycle audit event transactionally.


## Current inventory contract reconciliation

The current executable private callable inventory contains **13**, not twelve, authenticated-callable private SECURITY DEFINER helpers. The exact signatures are enforced by tools/test_account_suspension_private_definer_inventory.sql. The current public contract separately enforces 75 authenticated-callable public functions: 24 SECURITY DEFINER and 51 SECURITY INVOKER. The older clean-replay snapshot and older matrix counts are historical and superseded; this does not claim a new replay has just run.

The 13 private signatures are: tacresq_active_responder_locations(), tacresq_actor_role(), tacresq_assign_incident_team_guarded(bigint,bigint[]), tacresq_auth_can_access_incident_chat(bigint), tacresq_auth_profile_id(), tacresq_auth_profile_id_for_token_cleanup(), tacresq_current_account_active(), tacresq_current_account_active_read(), tacresq_dispatch_update_incident_status(bigint,text), tacresq_is_incident_assignee(bigint), tacresq_record_location_v2(bigint,double precision,double precision,real,timestamptz), tacresq_release_incident_assignment(bigint,bigint), and tacresq_upload_reservation_allows_insert(text,text,bigint).

Use the canonical master-plan Section 29 for the corrected matrix baseline and full invoker/RLS/view review gate.
