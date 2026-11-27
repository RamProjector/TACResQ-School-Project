# Account suspension — callable definer entry-point crosswalk

**Branch:** `security/admin-account-suspension`  
**Review status:** source-traced crosswalk plus a staged lifecycle helper; not proof of runtime denial.  
**Application API baseline (live read-only catalog, 2026-11-26):** 38 authenticated-callable public API functions: 24 SECURITY DEFINER and 14 SECURITY INVOKER. There are 13 authenticated-callable private SECURITY DEFINER helpers. PR #370 adds a protected Admin Auth-session reader, so the branch target is now 36 authenticated-callable public API functions, comprising 25 authenticated-callable public SECURITY DEFINER functions and 11 authenticated-callable public SECURITY INVOKER functions, after the three legacy requester-create invoker grants are removed; the connected live count remains 38 until deployment. The connected project also has one public trigger-only function with default client EXECUTE grants; see the live-catalog reconciliation below. The older 75/24/51 aggregate was extension-schema-dependent and is not an application API count.

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
| 24 | `api_admin_set_account_status(bigint,text,text,text)` | Website Admin lifecycle | Authenticated-only SECURITY DEFINER RPC; requires active AAL2/TOTP Admin; rejects self-change and Admin targets; locks and compares target state; writes immutable audit event transactionally |\n| 25 | `api_admin_list_auth_sessions_page(bigint,integer,timestamptz,uuid)` | PR #370 — Admin session inventory | Shared active-Admin AAL2/TOTP helper; resolves Auth ownership from canonical profile mapping; bounded read-only projection; no direct Auth table grants or session-validity overclaim |

## Website Admin transition (client-callable)

The website invokes `public.api_admin_set_account_status(bigint,text,text,text)` through the Supabase authenticated client. The follow-up migration grants EXECUTE only to `authenticated`; the function requires AAL2 with TOTP and an active Admin profile, prevents self-change and Admin targets, locks the target, compares the expected prior status under that lock, and commits the status change with an immutable audit event. The same migration updates the shared Admin MFA helper to reject suspended Admins and adds `account_status` to the bounded website directory response. Anonymous and PUBLIC execution remain revoked.

`public.api_admin_set_account_status(bigint,text,text)` is a separate staged SECURITY DEFINER overload and is **not client-callable**: EXECUTE remains revoked from `PUBLIC`, `anon`, and `authenticated`. It is excluded from the authenticated-callable inventory. The four-argument website RPC above is the client-callable transition entry point.

## Private callable definer functions

The private-function inventory is a separate callable surface. The current source-contract inventory locks thirteen authenticated-callable private SECURITY DEFINER helpers, including `private.tacresq_auth_profile_id_for_token_cleanup()` and `private.tacresq_current_account_active()`, and rejects `anon`/`PUBLIC` EXECUTE on every classified callable definer. A private function with authenticated EXECUTE is directly callable even if it is also used by a public wrapper. The source reviews explicitly identify these examples:

- `private.tacresq_auth_profile_id()` and `private.tacresq_auth_can_access_incident_chat(bigint)` — Batch 1. The profile-ID helper now returns NULL for suspended callers; Storage policies that rely on it fail closed. The directly callable incident-assignee helper also checks active status and locks the caller profile. Notification list/detail and incident-chat history RPCs now use this helper directly; claimable-incident discovery requires an active profile under a share lock.
- `private.tacresq_assign_incident_team_guarded(bigint,bigint[])`, `private.tacresq_is_incident_assignee(bigint)`, `private.tacresq_record_location_v2(...)`, `private.tacresq_active_responder_locations()`, and `private.tacresq_dispatch_update_incident_status(bigint,text)` — Batch 3. The dispatcher progress implementation is now separately guarded by `20261124070000_account_lifecycle_dispatch_progress_guard.sql`: it requires an active dispatcher and holds a `FOR SHARE` profile lock for the transaction. Its public `api_update_incident_status(bigint,text)` wrapper remains SECURITY INVOKER; the private implementation is the privileged write boundary.
- `private.tacresq_require_admin_mfa()` is intentionally not directly executable by client roles; it is a trusted internal authorization helper used by Admin wrappers — Batch 2.
- `private.tacresq_current_account_active()` is directly executable by `authenticated`, reads and share-locks the caller's profile lifecycle state, and returns false for missing/suspended profiles. It is used by restrictive direct-table RLS policies; its direct-callable grant is included in the thirteen-function private-definer inventory.

The full exact private signature list and function bodies are emitted by `tools/check_account_suspension_authorization_surface.sql` during clean replay. Do not treat this short list as the complete private catalog. Every function with client EXECUTE must be checked directly; every internal definer helper must be traced from its caller and checked for authorization assumptions.

## Invoker functions are not exempt

The live connected project snapshot has 14 authenticated-callable application API SECURITY INVOKER functions. The branch-only forward migration removes three legacy incident-create invoker grants; the expected post-migration application inventory is 11 authenticated-callable invokers. Their access can still expose or mutate data through RLS, policies, and helper calls. The pre-migration 14 signatures are enumerated in the live-catalog reconciliation below. Extension-provided routines must be inventoried separately by extension schema.

A complete release matrix must include the pre-migration live baseline of 38 authenticated-callable public API functions (24 definers and 14 invokers), and the expected branch target of 36 (25 definers and 11 invokers) after the three legacy incident-create grants are revoked, plus the 13 authenticated-callable private definer helpers, the trigger-only functions, direct table privileges, RLS policy composition, views, Storage, Auth triggers, Edge Functions, Android, web, and Laravel.

## Chat and Storage shared authorization chain

[Batch 24: chat and Storage authentication chain](admin-account-suspension-rpc-review-batch-24-chat-storage-authentication-chain.md) traces the shared identity and membership helpers, Storage INSERT/SELECT policies, and the compatibility-mode reservation return. [Batch 23](admin-account-suspension-rpc-review-batch-23-private-callable-definer-source-trace.md) records the directly callable private-definer paths, including the active-account resolver.

## Current coverage and remaining gap

This crosswalk connects the 25 public definer signatures to source-review batches. It is not yet a full transitive call graph: the clean-replay SQL currently emits function source, but no automated parser proves every nested call edge or authorization predicate. Manual source tracing and executable negative tests remain required.

Before adding the account-state migration:
1. Finish exact coverage for all 14 pre-migration application API invokers (11 expected after the legacy requester-create grant reduction), all 13 authenticated-callable private definers, and every internal helper reached transitively.
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

These additions reduce known callable gaps but do not complete the full application API transitive call graph or runtime denial matrix. Before enabling account transitions, still require: a fresh complete catalog replay; explicit review of every newly discovered callable and nested helper; disposable PostgreSQL same-valid-JWT tests for every guarded path; isolated Edge Function tests proving denied paths make no OAuth/FCM calls; Laravel/Sanctum identity-boundary verification; Storage policy and object-operation tests; and concurrency tests for lifecycle transitions racing with assignment and location operations.

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


## Live catalog reconciliation — connected TACResQ Supabase, 2026-11-26

This section records read-only catalog results from the connected project named TACResQ-School-Project. No rows were changed and no migration was applied.

### Exact current callable counts

| Surface | Live count | Classification |
|---|---:|---|
| Authenticated-callable public API functions | 38 | 24 SECURITY DEFINER + 14 SECURITY INVOKER |
| Authenticated-callable private SECURITY DEFINER helpers | 13 | All observed with anon EXECUTE false |
| Additional public trigger-only function | 1 | SECURITY INVOKER; not an application RPC |
| Authenticated-callable public functions including trigger-only | 39 | 24 definers + 15 invokers |

The old raw public-schema aggregate of 75 functions (24 definers + 51 invokers) is not portable across extension installation schemas. The connected Supabase project installs pgcrypto, uuid-ossp, and pg_stat_statements in the extensions schema, not public. A clean PostgreSQL replay that installs extension routines into public can inflate a raw public-schema count with extension functions. The application API register must therefore be counted by explicit api_ signatures, with extension routines recorded separately. The source inventory contract was changed to enforce 38 application API callables and their exact 24/14 split rather than a schema-wide 75/24/51 count.

### Exact 14 application SECURITY INVOKER signatures

- public.api_active_responder_locations()
- public.api_all_incidents()
- public.api_assign_incident(bigint,bigint)
- public.api_assign_incident_team(bigint,bigint[])
- public.api_create_incident(text,text,double precision,double precision,real)
- public.api_create_incident_v2(text,text,double precision,double precision,real,text,timestamp with time zone)
- public.api_create_incident_v3(text,text,double precision,double precision,real,text,timestamp with time zone,text)
- public.api_dependency_health()
- public.api_me()
- public.api_my_incidents()
- public.api_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)
- public.api_release_incident_assignment(bigint,bigint)
- public.api_set_duty_status(text)
- public.api_update_incident_status(bigint,text)

The separate trigger-only function is public.tacresq_require_incident_location(). It returns trigger and is attached to incidents_require_location_before_write. The live ACL explicitly grants EXECUTE to PUBLIC, anon, authenticated, and service_role. Trigger invocation does not require client EXECUTE; this is unnecessary exposure, not proof of a direct RPC exploit. New migration 20261126100000_revoke_incident_trigger_function_client_execute.sql revokes those direct grants while preserving the trigger. A clean-replay assertion verifies the trigger remains attached and all client/service EXECUTE checks are false. This migration is pending and has not been applied to the connected project.

### Invoker dependency map from live function bodies

| Invoker | Direct dependencies / call edge | Authorization boundary |
|---|---|---|
| api_active_responder_locations | private.tacresq_active_responder_locations() | Private definer wrapper enforces active dispatcher and profile share lock; private internal query is not client-callable |
| api_all_incidents | public.incidents, public.profiles, public.incident_assignments; private.tacresq_actor_role() | SECURITY INVOKER; active dispatcher helper plus base-table RLS |
| api_assign_incident | public.api_assign_incident_team(...) | Alias only; guarded public team-assignment path |
| api_assign_incident_team | private.tacresq_assign_incident_team_guarded(...) | Private definer checks active dispatcher and locks profile; underlying assignment implementation is revoked from clients |
| api_create_incident / v2 / v3 | public.profiles, public.incidents; incident triggers | SECURITY INVOKER; requester role check, incident INSERT RLS and restrictive active-account policy/write trigger. Legacy v1 still has search_path=public, pg_temp; verify this remains safe under the no-client-CREATE schema grant and consider pinning consistently |
| api_dependency_health | No application table/helper reads; returns a fixed constant | Immutable, data-free diagnostic; anon EXECUTE is intentionally retained |
| api_me | public.profiles, public.incidents, public.incident_assignments | SECURITY INVOKER; self-profile RLS and active-account restrictive policy |
| api_my_incidents | public.incidents, public.profiles, public.incident_assignments; private.tacresq_is_incident_assignee(...) | SECURITY INVOKER; direct-table RLS plus active-aware assignee helper |
| api_record_location_v2 | private.tacresq_record_location_v2(...) | Private definer wrapper checks active responder/driver, assignment, validation and rate limit |
| api_release_incident_assignment | private.tacresq_release_incident_assignment(...) | Private definer wrapper checks active dispatcher and profile lock |
| api_set_duty_status | public.profiles, public.incidents | SECURITY INVOKER; active responder/driver role check under FOR UPDATE profile lock; active-incident restriction |
| api_update_incident_status | private.tacresq_dispatch_update_incident_status(...) | Private definer wrapper checks active dispatcher and profile lock |

### RLS, views, and direct table grants

The live read-only query found no public views or materialized views, so no view-owner or security_invoker bypass path was observed in public at this snapshot. The reviewed operational tables profiles, incidents, incident_assignments, location_logs, incident_status_logs, and fcm_tokens have RLS enabled. The selected incident, assignment, location and status-log tables expose SELECT to authenticated but not table-level INSERT/UPDATE/DELETE. Profiles has SELECT/INSERT/UPDATE table privileges with self/dispatcher SELECT policy, restricted self-update policy, and the restrictive active-account policy. fcm_tokens has SELECT but no direct table DML; its RLS policy includes the intentionally narrow suspended self-cleanup DELETE exception, but a table privilege is also required for direct DML.

RLS policy composition is material: permissive policies combine with OR, restrictive policies combine with AND. The live policies include the restrictive active-account predicate on the reviewed operational tables, while fcm_tokens intentionally uses operation-specific restrictive policies. This source/catlog review does not replace same-valid-JWT negative tests.

### Migration and verification boundaries

The live migration ledger's latest observed entry is 20261125030354. It contains read_only_account_profile_rls and remove_legacy_fcm_active_account_policy under versions 20261125025825 and 20261125030010, while the current branch carries the same migration names/content under 20261125120000 and 20261125130000. This is a migration version-identity mismatch, not proof that those schema changes are absent. Reconcile canonical filenames/versions and history before merge so the logical migrations are not represented twice. The new 20261126100000 trigger-function EXECUTE revocation and 20261127010000 legacy incident-create RPC EXECUTE revocation are pending.

The live inventory is a catalog snapshot only. It does not prove behavior under real user JWTs, Storage HTTP, Edge Function provider calls, or concurrent suspension and operational writes. The trigger-function revoke migration and exact invoker contract changes must pass clean replay before merge; any production application requires the normal approved migration process.


**Extension-schema-independent application inventory:** the exact pre-migration live baseline is 38 authenticated-callable public API functions, including 24 authenticated-callable public SECURITY DEFINER functions and 14 authenticated-callable application API invoker functions. The branch-only legacy incident-create grant reduction targets 36 authenticated-callable public API functions after migration: 25 definers and 11 invokers. This is 36 authenticated-callable public API functions after the legacy requester-create EXECUTE revocation, with 11 authenticated-callable application API invoker functions after the legacy requester-create EXECUTE revocation. The 13 authenticated-callable private SECURITY DEFINER helpers are separate. The inventory contract is extension-schema-independent because it counts only explicit api_ application signatures; extension functions are tracked by their installed extension schema. The public incident-location trigger is tracked separately as trigger-only, not as an RPC.


## Private callable-helper dependency audit — live catalog, 2026-11-26

**Scope:** all 13 private SECURITY DEFINER functions with effective authenticated EXECUTE, their direct private helper calls, their RLS/Storage/trigger consumers, and relevant object privileges. This is a source/catalog review; it is not a same-JWT runtime test.

### Namespace and exposure boundary

- The live database grants USAGE on schema private to authenticated, but not anon. Authenticated has no CREATE on private; anon has neither USAGE nor CREATE.
- The 13 private functions grant EXECUTE to authenticated, but PUBLIC and anon do not have EXECUTE. The internal implementation functions inspected below have authenticated and anon EXECUTE false.
- Repository migrations explicitly describe private as outside the PostgREST-exposed Data API. Supabase documentation also describes private as inaccessible through the API. The live SQL role settings do not reveal the Dashboard's external API Exposed Schemas setting, so that setting still requires a read-only Dashboard verification before release. Do not equate schema USAGE with PostgREST exposure.
- Storage policies call selected private helpers directly. Their authenticated EXECUTE grants and private schema USAGE are therefore part of the Storage authorization path, not merely unused grants.

### Exact 13-helper register

| Callable private helper | Current behavior and transitive path | Boundary / finding |
|---|---|---|
| private.tacresq_active_responder_locations() | Resolves auth.uid() to an active dispatcher profile under FOR SHARE; calls private.tacresq_active_responder_locations_internal(). The internal reader returns only fresh (two-minute) locations for en-route incident/team members. | Active dispatcher guard is at callable edge; internal reader is not directly executable by authenticated/anon. |
| private.tacresq_actor_role() | Returns the caller's role only when the matching profile is active. Used by role-sensitive RLS and selected internal functions. | Active-aware role predicate; no role is returned for suspended/missing profile. |
| private.tacresq_assign_incident_team_guarded(bigint,bigint[]) | Requires active dispatcher and holds profile FOR SHARE; calls private.tacresq_assign_incident_team(...). Internal implementation validates incident state, team size (1–5), unique targets, available responder/driver status, existing active assignments, and locks incident/profile rows. | Internal implementation's initial role check does not independently check account_status, but it is not client-executable and the only live function-body caller is the guarded wrapper. Keep that call graph closed and same-transaction share lock. |
| private.tacresq_auth_can_access_incident_chat(bigint) | Calls active private.tacresq_auth_profile_id() then private.tacresq_can_access_incident_chat(incident, profile). | Missing/suspended profile fails closed. Shared access helper allows dispatcher, incident requester, or assigned responder/driver; unknown roles including Admin fail closed. |
| private.tacresq_auth_profile_id_for_token_cleanup() | Returns only the caller's own profile ID, without requiring active status. Used by the FCM self-unregister path and suspended-account cleanup policy. | Intentional suspended-account exception: it returns no profile fields and does not itself mutate data. Preserve only the narrow own-token cleanup path; do not reuse as general identity resolution. |
| private.tacresq_auth_profile_id() | Resolves the caller's profile ID only for an active profile and takes FOR SHARE. Used by chat RPCs and Storage authorization helpers. | Active-aware identity resolver; serialized with suspension transitions. |
| private.tacresq_current_account_active_read() | STABLE, non-locking boolean check of the current profile's account_status. Used by restrictive read/write RLS predicates and operation-specific FCM policies. | Correctly separated from locking mutation helper so SELECT/USING policies remain safe in read-only transactions. Same-valid-JWT and statement-snapshot tests remain required. |
| private.tacresq_current_account_active() | VOLATILE active check that takes profile FOR SHARE. Used by write-entry guards and mutation boundaries. | Serializes protected writes with account suspension/reactivation; do not call from read-only RLS predicates. |
| private.tacresq_dispatch_update_incident_status(bigint,text) | Requires active dispatcher under FOR SHARE; locks incident; validates allowed progress transition; updates incident, all team assignment statuses, and field-unit duty states atomically. | Preserve active dispatcher lock, transition matrix, and assignment/profile trigger guards. |
| private.tacresq_is_incident_assignee(bigint) | Resolves active profile under FOR SHARE, then checks normalized incident assignment membership. | Active-aware membership predicate used by incident and assignment RLS. |
| private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamptz) | Requires active responder/driver under FOR SHARE; calls internal writer. Internal writer validates coordinates, accuracy, capture time, active assignment, incident and assignment en-route states, locks incident/assignment, and applies an eight-second per-actor advisory throttle. | Internal writer has no client EXECUTE and is only called by guarded wrapper. Live body confirms it does not independently test account_status; wrapper lock is essential. |
| private.tacresq_release_incident_assignment(bigint,bigint) | Requires active dispatcher under FOR SHARE; calls internal release implementation. Internal implementation checks dispatcher role, incident/member state, refuses release of last member, maintains legacy primary assignment and duty status, and writes through assignment triggers. | Internal implementation's role check is not itself account-aware; it has no client EXECUTE and the only live function-body caller is the guarded wrapper. Preserve wrapper and profile-lock serialization. |
| private.tacresq_upload_reservation_allows_insert(text,text,bigint) | Resolves active profile; reads private upload policy. If strict reservation enforcement is disabled, returns true after identity check. If enabled, requires a matching unconsumed/unexpired reservation and chat membership. | This is one predicate in the Storage INSERT policy, not a standalone upload authorization. Path/profile folder, MIME, size, bucket, and chat membership checks remain conjunctive. The strict-enforcement switch is still a separate rollout gate. |

### Internal helper call graph and grants

The live function-body scan found the following internal-only edges:

- active responder locations -> tacresq_active_responder_locations_internal
- guarded team assignment -> tacresq_assign_incident_team
- chat access wrapper -> tacresq_auth_profile_id -> tacresq_can_access_incident_chat
- location wrapper -> tacresq_record_location_v2_internal
- release wrapper -> tacresq_release_incident_assignment_internal
- upload reservation predicate -> tacresq_auth_profile_id and tacresq_can_access_incident_chat

The five privileged implementation helpers listed above returned authenticated EXECUTE false and anon EXECUTE false. The shared chat predicate also returned authenticated/anon EXECUTE false. This is an important boundary: their legacy internal bodies may perform privileged work without repeating every active-account predicate because the live callers establish the trusted identity and lock first. Keep them non-callable and audit every new caller.

### RLS and Storage consumers

- private.tacresq_actor_role() is used by policies on admin_audit_logs, incident_assignment_events, incident_assignments, incident_status_logs, incidents, location_logs, and profiles.
- private.tacresq_is_incident_assignee() is used by incident_assignments, incidents, and related participant-read/update predicates.
- private.tacresq_current_account_active_read() is the restrictive active-account predicate on the reviewed public operational tables, including profiles, incidents, incident_assignments, incident_status_logs, location_logs, admin_audit_logs, and incident_assignment_events; it also gates FCM SELECT/INSERT/UPDATE.
- private.tacresq_auth_profile_id_for_token_cleanup() appears in the narrow FCM DELETE policies. The live catalog contains both legacy fcm_tokens_delete_self and restrictive tacresq_fcm_delete_self_cleanup with the same own-profile predicate. Direct authenticated DELETE on fcm_tokens is false, so the intended supported suspended cleanup path is the guarded api_unregister_fcm_token(text) RPC, not direct table DML.
- Storage objects has RLS enabled. The incident-chat-attachments INSERT policy requires the exact bucket, constrained incident/profile/UUID file path, caller profile folder, allowed image MIME, numeric size metadata, reservation predicate, and chat membership. SELECT requires the bucket and chat membership. The reviewed policies target authenticated, not anon.
- The private upload policy and reservation tables have RLS enabled, no client policies, and no anon/authenticated table privileges. They remain server/helper-only.

### Important operational interpretation

1. The 13 authenticated EXECUTE grants do not mean 13 additional public PostgREST endpoints. The private schema is intentionally not in the Data API surface; verify the project's actual Exposed Schemas setting before release.
2. Do not revoke helper EXECUTE indiscriminately: Storage policies and SECURITY INVOKER wrappers need the caller role to execute these predicates. If exposure must be reduced, first move the policy execution boundary to a supported role/function design and test Storage HTTP behavior.
3. tacresq_auth_profile_id_for_token_cleanup() is the only reviewed helper that deliberately resolves a suspended profile. It exists to permit self-token cleanup, not to restore application access.
4. The guarded assignment, release, and location wrappers are critical because their internal implementation functions do not all repeat account_status checks. Internal functions must remain non-client-executable and must not gain a second caller without a fresh review.
5. The SQL catalog and policy definitions do not prove actual Storage HTTP authorization, token behavior, race outcomes, or that the Dashboard's API schema configuration matches repository intent.

### Supabase Advisor snapshot — 2026-11-26

- Security advisor: one WARN because leaked-password protection is disabled. Supabase documentation says this HaveIBeenPwned-backed option is available on Pro and above. Do not upgrade or introduce a paid dependency as part of this work. Confirm the project's plan and Auth settings; if the feature is unavailable on the current plan, record the limitation and use the password-strength controls available without claiming leaked-password screening.
- Security advisor: 16 RLS-enabled tables have no policies. A read-only effective-privilege query verified all 16 (the 14 listed public audit/device/notification/incident-support tables plus private.incident_chat_upload_policy and private.incident_chat_upload_reservations) have no anon/authenticated table-level SELECT/INSERT/UPDATE/DELETE or column-level SELECT/INSERT/UPDATE grants. The no-policy state is therefore deny-by-default for direct client access at this snapshot. Keep grants and policies reviewed together; do not add permissive policies just to silence the advisor.
- Security advisor: 24 authenticated-callable public SECURITY DEFINER functions are reported as WARN. This is the intended public RPC surface, not an automatic vulnerability verdict; each function still requires exact body, grants, and caller review.
- Performance advisor also reports 10 unindexed foreign keys and one RLS initplan warning on incident_assignment_events_dispatch_read; unused-index findings are not grounds for automatic deletion. These belong in a separate performance pass.

### Remaining release tests

- [ ] Verify private is absent from Supabase Dashboard → API → Exposed Schemas (read-only check); preserve private schema USAGE needed by invoker/Storage paths.
- [ ] With the same still-valid JWT, test all 13 helper paths before and after suspension; distinguish read-only helpers from mutation/Storage predicates.
- [ ] Verify suspended own-token unregister succeeds only for the caller's exact token; registration, enumeration, rotation, and other-user deletion remain denied.
- [ ] Exercise Storage HTTP upload/download for requester, assigned field unit, dispatcher, unrelated profile, removed member, and suspended caller; test reservation enforcement both disabled and enabled.
- [ ] Test guarded assignment, release, and GPS operations concurrently with account suspension and role changes.
- [ ] Assert the five internal implementation helpers and shared chat predicate remain non-client-executable after clean replay.
- [ ] Keep Auth leaked-password configuration plan-aware and do not claim the feature is enabled from advisor output alone.
