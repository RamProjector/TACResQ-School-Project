# Account suspension — callable definer entry-point crosswalk

**Branch:** `security/admin-account-suspension`  
**Review status:** source-traced crosswalk plus a staged lifecycle helper; not proof of runtime denial.  
**Catalog baseline:** 20 authenticated-callable public SECURITY DEFINER functions in the clean migration replay. The exact signature list below is taken from that replay snapshot. The referenced batches document the source-level checks and missing account-state guards.

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
| 16 | `api_send_incident_image_message(bigint,text,text,text,bigint,uuid)` | Batch 1 and Batch 5 — chat/Storage | Check active sender at finalization; independently guard Storage INSERT/SELECT |
| 17 | `api_send_incident_message(bigint,text,uuid)` | Batch 1 — chat | Check active sender before idempotency lookup and message insertion |
| 18 | `api_set_incident_claimable(bigint,boolean)` | Batch 3 — dispatch | Check active dispatcher after locked actor re-read, before mutation |
| 19 | `api_units()` | Batch 3 — dispatch | Check active dispatcher and exclude suspended field-unit targets from eligibility |
| 20 | `api_update_my_incident(bigint,text,text,double precision,double precision,real,text,timestamptz,text,boolean)` | Batch 1 and Batch 13 — requester edit/validation | Check active requester before protected reads/mutations; preserve validation, edit cooldown, and audit/notification atomicity |

## Private callable definer functions

The private-function inventory is a separate callable surface. A private function with authenticated EXECUTE is directly callable even if it is also used by a public wrapper. The source reviews explicitly identify these examples:

- `private.tacresq_auth_profile_id()` and `private.tacresq_auth_can_access_incident_chat(bigint)` — Batch 1. The profile-ID helper now returns NULL for suspended callers; Storage policies that rely on it fail closed. The directly callable incident-assignee helper also checks active status and locks the caller profile. Notification list/detail and incident-chat history RPCs now use this helper directly; claimable-incident discovery requires an active profile under a share lock.
- `private.tacresq_assign_incident_team(bigint,bigint[])`, `private.tacresq_is_incident_assignee(bigint)`, `private.tacresq_record_location_v2(...)`, `private.tacresq_active_responder_locations()`, and `private.tacresq_dispatch_update_incident_status(bigint,text)` — Batch 3. The dispatcher progress implementation is now separately guarded by `20261124070000_account_lifecycle_dispatch_progress_guard.sql`: it requires an active dispatcher and holds a `FOR SHARE` profile lock for the transaction. Its public `api_update_incident_status(bigint,text)` wrapper remains SECURITY INVOKER; the private implementation is the privileged write boundary.
- `private.tacresq_require_admin_mfa()` is intentionally not directly executable by client roles; it is a trusted internal authorization helper used by Admin wrappers — Batch 2.\n- `private.tacresq_current_account_active()` is now directly executable by `authenticated`; it reads the current profile lifecycle state and is intended for later restrictive policies/RPC guards. It is called by the restrictive direct-table RLS policies, but not by the existing RPC bodies; it must be included in the private callable inventory.

The full exact private signature list and function bodies are emitted by `tools/check_account_suspension_authorization_surface.sql` during clean replay. Do not treat this short list as the complete private catalog. Every function with client EXECUTE must be checked directly; every internal definer helper must be traced from its caller and checked for authorization assumptions.

## Invoker functions are not exempt

The clean replay also reports 52 authenticated-callable public SECURITY INVOKER functions. Their access can still expose or mutate data through RLS, views, or helper calls. Examples documented in Batch 3 include `api_assign_incident_team`, `api_assign_incident`, `api_update_incident_status`, `api_all_incidents`, `api_my_incidents`, `api_set_duty_status`, `api_record_location_v2`, and `api_active_responder_locations`.

A complete release matrix must therefore include all 72 public callable functions, not only the 20 public definers, and reconcile them with direct table privileges, RLS policy composition, views, Storage, Auth triggers, Edge Functions, Android, web, and Laravel.

## Current coverage and remaining gap

This crosswalk connects the 20 public definer signatures to source-review batches. It is not yet a full transitive call graph: the clean-replay SQL currently emits function source, but no automated parser proves every nested call edge or authorization predicate. Manual source tracing and executable negative tests remain required.

Before adding the account-state migration:
1. Finish exact coverage for all 52 invoker functions and all authenticated-callable private functions.
2. Trace each public and private entry point to nested helpers and trigger-mediated effects; document the actual lock order and transaction boundary.
3. Map every Android, web, and Laravel direct operation to its effective grant, RLS policy, view, RPC, or Storage enforcement point.
4. Add a clean-replay contract that fails when a new authenticated-callable definer entry point is introduced without an explicit active-account guard or reviewed exemption.
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

- `public.api_assign_incident_team(bigint,bigint[])`: now checks and share-locks an active dispatcher before delegating to the preserved private implementation. The legacy single-member alias is recreated to route through the guarded public entry point. The moved internal implementation has client EXECUTE revoked.
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