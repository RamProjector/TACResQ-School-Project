# Account suspension authorization review — batch 20: invoker entry points and private callable functions

**Evidence:** clean PostgreSQL migration replay job `137424953572`, which printed the effective authenticated-callable function catalog.  
**Status:** source/catalog crosswalk; not a runtime suspension test.  
**Important:** the catalog's 52 public SECURITY INVOKER entries include extension-provided crypto functions. This batch separates TACResQ application functions from those extension functions rather than treating every pgcrypto primitive as an application endpoint.

## Application SECURITY INVOKER functions

| Signature | Existing role/data boundary | Suspension review requirement |
|---|---|---|
| `api_active_responder_locations()` | Public invoker wrapper delegates to private dispatcher-only SECURITY DEFINER location reader. | Active dispatcher check inside private reader; direct wrapper must not return coordinates to suspended dispatcher. |
| `api_all_incidents()` | Dispatcher queue read; relies on role and RLS. | Require active dispatcher and retain direct-table RLS denial. |
| `api_assign_incident(bigint,bigint)` | Legacy one-member wrapper delegates to team assignment path. | Actor and target active-state checks in shared private assignment implementation; test legacy wrapper cannot bypass. |
| `api_assign_incident_team(bigint,bigint[])` | Invoker wrapper delegates to private SECURITY DEFINER team assignment implementation. | Check active dispatcher and every selected field unit inside private implementation. |
| `api_create_incident(text,text,double precision,double precision,real)` | Legacy requester create surface. | Determine whether it remains a usable direct path or delegates to the current v4 flow; reject suspended callers at the effective write boundary. |
| `api_create_incident_v2(text,text,double precision,double precision,real,text,timestamptz)` | Versioned requester create surface. | Determine direct grant and delegation status; any live write path must inherit active-requester enforcement. |
| `api_create_incident_v3(text,text,double precision,double precision,real,text,timestamptz,text)` | Versioned requester create surface. | Determine direct grant and delegation status; any live write path must inherit active-requester enforcement. |
| `api_dependency_health()` | Data-free dependency health result. | Explicitly classify as a public operational health exception or require active account; ensure it exposes no private user/incident data. |
| `api_me()` | Current-profile read through invoker semantics. | Define whether suspended accounts may read a minimal suspended-state profile; must not expose other protected data or be used as a bypass. |
| `api_my_incidents()` | Requester-owned/assigned incident read through RLS and assignment helper. | Require active account in both RPC behavior and direct table policy composition. |
| `api_record_location_v2(bigint,double precision,double precision,real,timestamptz)` | Invoker wrapper delegates to private SECURITY DEFINER GPS writer. | Active field-unit check in private writer before insert; retain assignment, freshness, accuracy, and rate-limit checks. |
| `api_release_incident_assignment(bigint,bigint)` | Authenticated field-unit release path; source and effective role checks must be preserved. | Reject suspended actor for normal release. Keep emergency/dispatch continuity handling separate; do not silently release on suspension. |
| `api_set_duty_status(text)` | Caller profile lock, field-unit role and active-assignment checks, duty-state update. | Require active account. Do not use duty status as a suspension flag or mutate it during suspend/reactivate. |
| `api_unregister_fcm_token(text)` | Self-owned token removal; currently an invoker path. | Evaluate as a narrow cleanup exception for suspended users. It must remain strictly self-owned and must not reveal/enumerate tokens. |
| `api_update_incident_status(bigint,text)` | Invoker wrapper delegates to private dispatcher status transition. | Active dispatcher check inside private implementation; preserve incident/team/duty synchronization transaction. |

### Legacy RPC exposure

The older `api_create_incident`, `api_create_incident_v2`, and `api_create_incident_v3` signatures remain authenticated-callable in the replay catalog. They must not be dismissed as obsolete based only on Android's current client version. Before implementation, inspect their effective bodies, direct grants, and call sites. Either:
- route them through the same guarded current implementation, or
- revoke client EXECUTE if repository compatibility checks prove no supported client still needs them.

Do not revoke them speculatively; test Android, web, Laravel, and any supported older client compatibility first.

### Data-free health RPC exception

`api_dependency_health()` is intentionally data-free by design. Decide explicitly whether it remains available to suspended authenticated users for operational diagnostics. If allowed, add a contract proving the response contains only dependency status and no profile, incident, location, or account data. Do not let this exception become a general bypass.

## Authenticated-callable private SECURITY DEFINER functions

The clean replay printed these callable private functions. Each is directly callable by authenticated clients and therefore must be reviewed independently of its public wrapper:

| Private signature | Existing responsibility | Suspension gate |
|---|---|---|
| `private.tacresq_active_responder_locations()` | Dispatcher-only latest responder coordinates. | Active dispatcher required. |
| `private.tacresq_actor_role()` | Resolves caller role from profile. | Decide whether this helper should return no role for suspended accounts; then ensure callers cannot bypass the state guard by relying on another role source. |
| `private.tacresq_assign_incident_team(bigint,bigint[])` | Dispatcher team assignment, target eligibility, locking, and writes. | Active dispatcher and active selected units, under the existing lock protocol. |
| `private.tacresq_auth_can_access_incident_chat(bigint)` | Determines caller chat membership/access. | Suspended caller must never receive a positive access result. |
| `private.tacresq_auth_profile_id()` | Resolves current profile identity. | Define safe semantics for suspended identity lookup; do not let a valid profile ID alone grant downstream access. |
| `private.tacresq_dispatch_update_incident_status(bigint,text)` | Dispatcher incident/assignment progress mutation, called by the SECURITY INVOKER `api_update_incident_status` wrapper. | **Guard added in migration `20261124070000`:** requires an active dispatcher and takes a `FOR SHARE` profile lock before incident mutation; the clean-replay SQL contract checks volatility, pinned search path, and authenticated-only EXECUTE. |
| `private.tacresq_is_incident_assignee(bigint)` | Caller assignment-membership predicate used by policies and RPCs. | Suspended caller must not pass the membership predicate as an authorization grant. |
| `private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamptz)` | Validated field GPS write. | Active assigned field unit required before insert. |
| `private.tacresq_release_incident_assignment(bigint,bigint)` | Field-unit assignment release. | Active actor required for normal release; preserve operational continuity. |

Other private SECURITY DEFINER functions appear in the complete inventory output but are not directly executable by authenticated. They still need transitive call-graph review where invoked by a callable definer, trigger, policy, or service-role path. In particular, the shared Admin MFA helper is an internal trusted helper and must gain the active-Admin check when account state is implemented; it should remain non-callable by client roles.

## Extension functions are not TACResQ business endpoints

The public authenticated-callable inventory also contains pgcrypto functions (for example `crypt`, `digest`, `encrypt`, `decrypt`, `hmac`, `gen_salt`, and PGP helpers). They are SECURITY INVOKER extension routines, not application authorization endpoints. Their presence explains why the aggregate count of 52 invoker functions is larger than the application-specific list above.

Still, do not assume extension exposure is harmless: keep the extension schema and function grants narrow, and ensure application tables/secrets are not passed to those functions through an unintended RPC or view.

## Required next steps

1. Source-trace each application invoker body and the effective definitions of legacy requester RPCs; record exact callers and whether each is still supported.
2. Trace the private callable functions into all nested private helpers and trigger side effects; record lock order and transaction boundaries.
3. Review the remaining non-callable private SECURITY DEFINER helpers for transitive use by client-callable definers, RLS policies, triggers, and Edge/service-role code.
4. Reconcile every function with direct Android/web/Laravel call sites and direct table/Storage operations.
5. Add executable same-still-valid-JWT negative tests for each protected surface and explicit positive tests for any intentionally retained data-free exception.

No account-state migration, UI, connected Supabase operation, or production data change is included in this batch.


## Follow-up implementation — dispatcher progress entry point

The direct private callable was re-reviewed after the public invoker/private definer call edge was traced. Its earlier implementation checked only the dispatcher role, which allowed a still-valid JWT belonging to a suspended dispatcher to reach incident progress writes if this private function remained executable. Migration `20261124070000_account_lifecycle_dispatch_progress_guard.sql` closes that specific gap by resolving the actor from `auth.uid()`, requiring `account_status = 'active'`, and holding a `FOR SHARE` lock on the profile row through the transaction. It preserves the existing incident transition validation, assignment-status synchronization, and duty-status updates.

The replay contract in `tools/test_account_lifecycle_foundation.sql` verifies the effective function is VOLATILE, SECURITY DEFINER, pinned to `search_path = pg_catalog`, checks active status and the share lock, and remains executable only by `authenticated`. The Python inventory contract ties those assertions to the migration and replay test. This is source/replay-contract coverage; it is not a claim that a disposable PostgreSQL replay or live project has been executed in this turn.


## Follow-up implementation — assignment and GPS write authorization

The callable review now includes the team-assignment, release, and per-responder GPS write call edges. The three original write implementations are preserved as private internal routines with client EXECUTE revoked. Their callable wrappers now acquire a `FOR SHARE` lock on the authenticated profile while requiring `account_status = 'active'`; assignment/release require the dispatcher role, while GPS recording requires responder/driver. The single-member legacy assignment alias routes through the guarded team-assignment wrapper.

Review boundary: the wrappers retain the existing operation-specific validation and mutation logic by delegation. The migration replay SQL checks the effective wrapper attributes and client grants and confirms that the preserved internal functions are inaccessible to client roles. It does not yet prove concurrency behavior in a disposable Supabase-compatible database; that replay remains a required validation step before PR #347 can leave draft.
