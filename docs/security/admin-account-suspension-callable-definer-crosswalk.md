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
- `private.tacresq_assign_incident_team(bigint,bigint[])`, `private.tacresq_is_incident_assignee(bigint)`, `private.tacresq_record_location_v2(...)`, `private.tacresq_active_responder_locations()`, and `private.tacresq_dispatch_update_incident_status(bigint,text)` — Batch 3.
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
