# Account suspension: clean-replay authorization snapshot

**Branch:** `security/admin-account-suspension`  
**Original replay source commit:** `4f16baf73b38c295baec6d72698233a73a74cac8`  
**Original replay baseline (historical):** 23 public and 12 authenticated-callable private SECURITY DEFINER functions, including the later incident-image reservation RPC. The live 2026-11-26 baseline is documented in the appended correction section.  
**Source:** CI job `Replay Supabase migrations on clean PostgreSQL` (run `45072734414`)  
**Purpose:** Record the effective catalog discovered after replaying the repository migrations. This is a point-in-time inventory, not a statement that any endpoint is suspension-safe.

## Snapshot totals

| Surface | Observed |
|---|---:|
| Authenticated-callable public functions | 74 |
| Public `SECURITY DEFINER` functions | 23 |
| Public `SECURITY INVOKER` functions | 51 |
| Public ordinary/partitioned tables inventoried | 20 |
| Authenticated Storage object policies | 2 |

The earlier preliminary review referenced 18 public authenticated-callable definer functions. The original replay snapshot reported 20; the incident-chat upload-reservation migration added `api_reserve_incident_image_upload(bigint,text,bigint,uuid)`, and the account-lifecycle foundation added the authenticated-callable `api_admin_list_account_lifecycle_events(integer,integer)` reader and the `api_assign_incident_team(bigint,bigint[])` SECURITY INVOKER wrapper delegating to the authenticated-callable private `tacresq_assign_incident_team_guarded(bigint,bigint[])` SECURITY DEFINER helper. The public callable baseline remains **74** functions (23 public definers and 51 public invokers). The `api_admin_set_account_status(bigint,text,text)` transition routine remains staged with client EXECUTE revoked and is intentionally excluded. The original replay's executable contract locked its 23 public definer signatures and twelve authenticated-callable private SECURITY DEFINER helpers. These historical counts are superseded by the current 24/14 application API register and 13 private helpers described in the appended correction section.

## Public authenticated-callable SECURITY DEFINER functions

The original replay reported 20 effective signatures. The current baseline adds the upload-reservation RPC and two Admin lifecycle RPCs; the current 23-signature exact list is enforced by `tools/test_account_suspension_definer_inventory.sql`.

1. `api_admin_analytics_summary(timestamptz,timestamptz)`
2. `api_admin_list_audit_logs(integer)`
3. `api_admin_list_audit_logs_page(integer,integer,text,text,timestamptz,timestamptz,bigint,bigint)`
4. `api_admin_list_investigation_events(integer,integer,text,text,text,timestamptz,timestamptz,bigint,bigint,bigint)`
5. `api_admin_list_users_page(integer,integer,text,text)`
6. `api_admin_set_user_role(bigint,text,text)`
7. `api_cancel_my_incident(bigint)`
8. `api_claim_incident(bigint)`
9. `api_claimable_incidents()`
10. `api_create_incident_v4(text,text,double precision,double precision,real,text,timestamptz,text,uuid)`
11. `api_mark_notification_read(bigint)`
12. `api_my_incident_messages(bigint,integer,bigint)`
13. `api_my_notification(bigint)`
14. `api_my_notifications(integer)`
15. `api_register_fcm_token(text)`
16. `api_reserve_incident_image_upload(bigint,text,bigint,uuid)`
17. `api_send_incident_image_message(bigint,text,text,text,bigint,uuid)`
18. `api_send_incident_message(bigint,text,uuid)`
19. `api_set_incident_claimable(bigint,boolean)`
20. `api_units()`
21. `api_update_my_incident(bigint,text,text,double precision,double precision,real,text,timestamptz,text,boolean)`
22. `api_admin_list_account_lifecycle_events(integer,integer)`
23. `api_unregister_fcm_token(text)`

## Private authenticated-callable SECURITY DEFINER functions

The clean replay separately inventories twelve private SECURITY DEFINER helpers with effective `authenticated` EXECUTE. The list includes `private.tacresq_current_account_active()` plus the ten helpers in [batch 23](admin-account-suspension-rpc-review-batch-23-private-callable-definer-source-trace.md). The SQL inventory contract rejects new callable private definers and any `anon`/`PUBLIC` EXECUTE grant. This is a callable-surface inventory, not proof that each helper is safe in every caller context.

Every callable definer entry point remains an independent review target. A wrapper's role check, MFA check, or ownership check does not automatically establish a current account-state check. The private implementation called by a wrapper must also be inspected, and direct authenticated grants to private functions must be treated as callable entry points.

## Public tables observed

The replay inventoried these 20 public ordinary/partitioned tables:

- `admin_audit_logs`
- `api_operation_logs`
- `device_challenges`
- `device_integrity_checks`
- `device_security_events`
- `device_sessions`
- `fcm_delivery_attempts`
- `fcm_tokens`
- `incident_assignment_events`
- `incident_assignments`
- `incident_claimability_logs`
- `incident_messages`
- `incident_request_edit_logs`
- `incident_status_logs`
- `incident_submission_keys`
- `incidents`
- `location_logs`
- `profiles`
- `user_devices`
- `user_notifications`

The SQL inventory prints each table's RLS flag, authenticated SELECT/INSERT/UPDATE/DELETE privileges, and policies. Do not infer that RLS alone blocks a suspended user: permissive policy composition, direct grants, SECURITY DEFINER routines, and Storage policies all need separate treatment.

## Storage policy snapshot

Two authenticated policies were present for `storage.objects`:

- `tacresq_incident_chat_attachment_insert` — INSERT; restricts the bucket, object-name pattern, profile folder, and incident-chat access.
- `tacresq_incident_chat_attachment_read` — SELECT; restricts the bucket and incident-chat access.

These policies currently have no independent account-state condition in the inspected definitions. The suspension implementation must add and test that condition without weakening existing path, membership, MIME/size, or reservation checks.

## Important policy-composition check

The effective `admin_audit_logs` policy inventory includes:

- `admin_audit_logs_read_admin` as the permissive Admin-role policy.
- `admin_audit_logs_require_aal2` as a **RESTRICTIVE** SELECT policy requiring AAL2 and TOTP.

PostgreSQL combines permissive policies with OR and restrictive policies with AND. Preserve the restrictive policy type when changing audit access; turning the MFA condition into a second permissive policy would allow the Admin-role policy to bypass it.

## Next implementation gates

1. Build an explicit per-signature review matrix for all 23 public definer functions and all twelve authenticated-callable private definer functions.
2. Trace each public wrapper into private functions and triggers; record the exact current authorization checks and whether they are sufficient for suspension.
3. Inventory all direct authenticated table operations in Android, web, and Laravel, mapping each to its effective RLS/grant enforcement.
4. Add account-state enforcement only after the matrix covers every direct and indirect path, including Storage.
5. Add disposable PostgreSQL tests proving that a suspended account with a still-valid JWT is denied on reads and writes, while active incident assignments are not silently cancelled or reassigned.
6. Add a regression assertion that the audit-log AAL2 policy remains restrictive.

No connected Supabase project was queried or modified to produce this snapshot. The snapshot came from the repository's isolated CI PostgreSQL replay. It is not evidence of the live LifeLink or TACResQ project catalog.


The staged `api_admin_set_account_status(bigint,text,text)` routine is SECURITY DEFINER but has no authenticated, anon, or PUBLIC EXECUTE grant. It is therefore not included in the callable totals above; it must remain unavailable to client routes until the complete access-surface and runtime validation gates are approved.


## Supersession notice — current branch source-contract baseline

This file is a historical replay snapshot. Its 74-public-callable / 23-public-definer figures and twelve-private-helper description are superseded by the current branch's executable inventory contracts:

- 75 authenticated-callable public functions: 24 SECURITY DEFINER and 51 SECURITY INVOKER.
- 13 authenticated-callable private SECURITY DEFINER helpers.

The exact current private list is enforced by tools/test_account_suspension_private_definer_inventory.sql; the public 24-signature list and 75/24/51 aggregate are enforced by tools/test_account_suspension_definer_inventory.sql. This notice does not rewrite the original replay's observed figures or pretend that a new replay has occurred. Preserve the original snapshot as historical evidence, and use the exact-head replay output to confirm the current catalog before release sign-off.

Also superseded: the older matrix's unresolved duty-status and image-reservation signatures. The exact signatures are public.api_set_duty_status(text) and public.api_reserve_incident_image_upload(bigint,text,bigint,uuid). The public team-assignment function is SECURITY INVOKER and delegates to the authenticated-callable private definer guard; do not count it as a public definer.


## Live catalog correction — 2026-11-26

The historical replay values in this file must not be used as the current application API count. A read-only catalog query against the connected TACResQ-School-Project Supabase database found 38 authenticated-callable public api_ functions (24 SECURITY DEFINER and 14 SECURITY INVOKER), 13 authenticated-callable private SECURITY DEFINER helpers, and one separate public trigger-only function. The raw 75/24/51 count depended on which schema held extension routines during replay; the connected Supabase project installs pgcrypto, uuid-ossp, and pg_stat_statements in extensions rather than public.

The current branch replaces the raw schema-wide aggregate assertion with an exact 24-definer / 14-invoker application API signature register. It also adds a migration to revoke unnecessary direct EXECUTE grants on the trigger-only incident-location function. These are pending branch changes until clean replay and merge; this file remains a historical snapshot and its original recorded counts are preserved.
