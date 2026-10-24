# TACResQ Supabase Security and Performance Audit

**Observed:** 2026-10-24  
**Target:** Supabase project `TACResQ-School-Project` (`kttguamhznoflzagfutr`)  
**Method:** Read-only Supabase advisors, migration inventory, table/schema inventory, and PostgreSQL function metadata/definitions.  
**Changes made to database:** None. This document records findings and recommended verification; it does not claim remediation or legal/security certification.

## Executive summary

The connected project reports `ACTIVE_HEALTHY`. Its migration history includes the core schema, RPC exposure hardening, RLS/index tuning, location/push, location writer isolation, anonymous RPC restrictions, durable SOS idempotency, one-active-request/cancellation rules, requester edit workflow, urgent location correction, dispatch team assignment, and per-responder assignment status.

The read-only security advisor returned:
- **2 INFO findings:** RLS enabled but no table policies on `public.incident_request_edit_logs` and `public.incident_submission_keys`.
- **5 WARN findings:** authenticated users can execute five `SECURITY DEFINER` RPCs.
- **1 WARN finding:** leaked-password protection is disabled.

The performance advisor returned:
- **2 INFO findings:** foreign keys without covering indexes.
- **5 WARN findings:** RLS policies re-evaluate auth/current-setting functions per row.
- **2 INFO findings:** indexes not used in the observed statistics window.

These are findings to review, not proof that exploitation has occurred. In particular, a `SECURITY DEFINER` RPC can be intentional when it is the narrow API boundary for a transaction; the important controls are explicit grants, fixed search path, authenticated identity checks, role and ownership checks, validated arguments, and bounded output.

## Priority 0 — Before widening the Render bridge

### 1. Reconcile the exact client/server RPC contract

Current `main` server allowlist has five functions:
- `api_cancel_my_incident`
- `api_create_incident_v4`
- `api_record_location_v2`
- `api_units`
- `api_update_my_incident`

PR #134 changes the Android release path to route all 14 application data RPCs through Render. The current live Render deployment is the startup-fix commit `77390b06491fd7038338e57f180b6bbc3bef6484`; deployment metadata alone does not prove the 14-RPC allowlist is deployed or authenticated calls work. Keep PR #134 blocked until the branch is reconciled against current `main`, the matching allowlist is deployed, and synthetic-account end-to-end tests pass.

For every bridged RPC, verify:
- exact SQL signature and response shape expected by the Android DTO;
- EXECUTE grants for `anon`, `authenticated`, and `PUBLIC`;
- `auth.uid()` and profile/role validation;
- record ownership/assignment constraints and allowed status transitions;
- fixed `search_path` (especially for `SECURITY DEFINER`);
- request-size/argument validation and bounded response data;
- timeout/retry/idempotency behavior and no silent write fallback;
- forbidden-role, expired-token, malformed-payload, and rate-limit behavior.

Use synthetic QA accounts only. Do not create test incidents against live operations.

## Priority 0 — Direct table privileges and row-level policies

A second read-only query compared `information_schema.role_table_grants` with the live `pg_policies` catalog. The table-level grant inventory returned direct grants to `anon` on `public.incident_assignments` for SELECT, INSERT, UPDATE, DELETE, and other table privileges. RLS is enabled and the only policy returned for that table is an authenticated-only SELECT policy; no `anon` policy was listed. This is not evidence that anonymous writes succeed—RLS should deny rows without a matching policy—but it is broader table privilege exposure than the documented anonymous-privilege hardening suggests. Revoke unnecessary anonymous grants through a reviewed migration and verify effective privileges afterward.

The same inventory returned table-level INSERT/UPDATE/DELETE grants to `authenticated` on several operational tables. Current policies and triggers provide additional controls, so grants alone do not prove an exploit:
- `incident_assignments` has only a participant/dispatcher/requester SELECT policy in the inspected policy inventory; no INSERT/UPDATE/DELETE policies were listed for client roles.
- `incidents` has requester-only INSERT, participant/dispatcher SELECT, and dispatcher/assigned-unit UPDATE policies. Its trigger `tacresq_guard_incident_update` rejects field-unit edits to the original emergency report fields and validates status transitions; `tacresq_guard_single_active_incident` enforces one active incident per requester on insert. These safeguards reduce bypass risk but should be covered by direct-table and RPC integration tests.
- `profiles` has self/dispatcher SELECT and responder/driver self-UPDATE policies. Its `tacresq_guard_profile_update` trigger rejects mobile-client changes to identity/role fields and blocks duty changes while an active assignment exists.
- `location_logs` has participant/dispatcher SELECT and an assigned, en-route responder/driver INSERT policy. The grant inventory did not list authenticated INSERT for this table, and no client UPDATE/DELETE policies were listed.
- `incident_request_edit_logs` and `incident_submission_keys` had no policies; the grant inventory showed no direct `anon` or `authenticated` grants for these tables, consistent with a deny-by-default internal-table design.

**Recommendation:** decide explicitly whether the app should use RPCs as the only write interface. If yes, revoke direct table DML grants from `anon` and `authenticated` where not required, keeping only narrowly justified reads (if any). Do not run a blanket revoke until every Android screen, dashboard query, trigger, helper and RPC is checked for dependency. If direct table access is intentionally retained, document the rationale and add tests proving RLS plus triggers preserve the same business invariants as the RPCs—especially single-active-request, idempotency, request edit audit, dispatcher-only assignment, and per-responder status transitions.

The read-only catalog showed a trigger `tacresq_guard_profile_update` on profiles and `tacresq_guard_incident_update` plus `tacresq_guard_single_active_incident` on incidents. Their inspected definitions use a fixed `pg_catalog` search path. This is useful defense in depth, but it does not replace least-privilege table grants or authenticated end-to-end tests.

## Priority 1 — Review security advisor findings

### 2. `SECURITY DEFINER` RPCs are executable by authenticated users

The advisor identified these five functions:
- `public.api_cancel_my_incident(bigint)`
- `public.api_create_incident_v4(...)`
- `public.api_record_location_v2(...)`
- `public.api_units()`
- `public.api_update_my_incident(...)`

The read-only privilege check found `anon_can_execute = false` and `authenticated_can_execute = true` for each. That is not automatically a vulnerability: these are exposed application RPCs, and their current definitions include identity/role or assignment checks. However, because `SECURITY DEFINER` executes with the function owner's privileges, each must be reviewed as a privileged API boundary. Do not change them to `SECURITY INVOKER` or revoke authenticated execution blindly; either could break the intended workflow.

Observed protections in inspected definitions:
- create/cancel/update requester functions require an authenticated UID, look up the profile by `auth_user_id = auth.uid()`, enforce the requester role, and constrain the request to that requester;
- location recording validates coordinate range, accuracy, timestamp freshness, responder/driver role, assignment, and `en_route` status, and throttles updates;
- unit listing verifies the caller is a dispatcher;
- anonymous execution was false for the inspected functions.

Follow-up verification:
1. Review all called private helpers and any triggers for ownership, role, search-path and privilege safety.
2. Test wrong-role and wrong-owner calls using real authenticated synthetic identities, not source-code assertions alone.
3. Check for unintended PUBLIC/anon execution on every overload, not just the names.
4. Keep returned personal data minimal for each role; verify dispatcher-only access to contact details and responder coordinates.
5. Re-run Supabase security advisors after any reviewed migration.

Reference: [Supabase database linter — authenticated SECURITY DEFINER execution](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable).

### 3. RLS enabled with no table policies

The advisor reported no policies on:
- `public.incident_request_edit_logs`
- `public.incident_submission_keys`

These tables contain sensitive audit/change snapshots and idempotency request/response payloads. RLS enabled with no policies can be an intentional deny-by-default design if clients have no direct table grants and the app accesses them only through tightly scoped functions. Verify table privileges explicitly for `anon` and `authenticated`, including inherited/PUBLIC grants, and inspect every function that reads or writes these tables. Do not add broad user-facing policies merely to silence the advisor.

The idempotency table stores `request_payload` and `response_payload`; minimize sensitive fields in these payloads and define a retention/cleanup policy with the project owner. Edit logs store before/after values; access should be limited to the approved audit workflow.

Reference: [Supabase Data API security](https://supabase.com/docs/guides/api/securing-your-api).

### 4. Leaked-password protection disabled

Supabase Auth reported leaked-password protection disabled. Enable it after confirming the project's expected signup/password-reset UX and testing the setting in the appropriate environment. This audit did not change Auth settings.

Reference: [Supabase password security](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection).

## Priority 2 — Performance improvements after correctness/security review

### 5. RLS policies that re-evaluate auth functions per row

The advisor reported this on:
- `incident_assignments.incident_assignments_read_participants`
- `incidents.incidents_read_participant_or_dispatcher`
- `incidents.incidents_update_dispatcher_or_assigned_unit`
- `location_logs.location_logs_read_dispatcher_or_owner`
- `location_logs.location_logs_insert_owner`

Where semantics are unchanged, Supabase recommends wrapping stable auth calls such as `auth.uid()` in a scalar subquery, e.g. `(select auth.uid())`, to allow init-plan evaluation. Before editing, capture the existing policy SQL, prove the rewritten predicate is equivalent, and run role/ownership regression tests. This is a performance optimization, not permission to weaken policies.

Reference: [Supabase RLS performance guidance](https://supabase.com/docs/guides/database/postgres/row-level-security#call-functions-with-select).

### 6. Missing covering indexes for foreign keys

The advisor identified:
- `incident_assignments.assigned_by`
- `incident_request_edit_logs.requester_id`

Assess query plans and expected delete/update/retention operations before adding indexes. An index on each foreign-key column may help joins and referential actions, but measure first and use a reviewed migration. Avoid making index changes directly against production without a migration and validation plan.

Reference: [Supabase database linter — unindexed foreign keys](https://supabase.com/docs/guides/database/database-linter?lint=0001_unindexed_foreign_keys).

### 7. Unused indexes

The advisor reported `incident_status_logs_incident_idx` and `location_logs_incident_recorded_idx` as unused in the observed statistics. Do not drop them solely on this report: a low-traffic project may not have exercised the relevant queries, and location/audit queries may be important during an incident. Review query plans, workload and migration intent before deciding.

## Privacy and operational follow-ups

The schema includes location history, responder/device location, push tokens, request edit snapshots, and idempotency payloads. Before production claims, document and verify:
- approved purpose and role-based visibility for each data category;
- how location sharing starts/stops and which assignment states allow it;
- retention, deletion and audit access for coordinates and request snapshots;
- whether logs, crash reports, notifications or analytics can expose coordinates/contact data;
- incident escalation, outage handling, and who is authorized to administer staff accounts.

The application should distinguish requester/dispatcher/responder operational permissions from technical administration. Do not grant dispatchers infrastructure or database administration privileges by default.

## Verification record

- Supabase project status: `ACTIVE_HEALTHY` (read-only project metadata).
- Migrations listed through `20261023103737_per_responder_assignment_status`.
- All inspected public tables had RLS enabled in the table inventory.
- Read-only SQL inspected definitions and privileges for the 14 Android RPC names.
- No SQL writes, migrations, Auth settings changes, Render changes, or production records were created by this audit.

## Recommended next sequence

1. Finish PR #134's branch reconciliation and client/server allowlist review.
2. Verify table grants and helper functions for the two no-policy tables.
3. Enable leaked-password protection after staging/UX verification.
4. Add synthetic role/ownership integration tests for each RPC and for incident lifecycle concurrency/idempotency.
5. Make performance-only policy/index changes in separate migrations after capturing baseline query plans.
6. Have the project owner/privacy reviewer approve location retention and notice wording before any production claim.
