# Account suspension RPC review — batch 2: Admin and audit

**Branch:** `security/admin-account-suspension`  
**Status:** original source review with staged PR #347 implementation follow-up below; not proof of live deployment or runtime validation.

## Admin callable entry points

| Entry point | Existing authorization and behavior | Suspension conclusion |
|---|---|---|
| `api_admin_list_users_page(integer,integer,text,text)` | Public SECURITY DEFINER wrapper calls `private.tacresq_require_admin_mfa()` before delegating to the renamed internal implementation. Internal function's client grants are revoked. | The shared helper checks UID, AAL2, TOTP AMR, and current Admin role, but not current account state. Add active-account enforcement inside the shared helper; do not rely on the browser MFA gate. |
| `api_admin_set_user_role(bigint,text,text)` | Same protected wrapper pattern. Internal implementation validates role/reason, locks target profile, blocks Admin targets and active field assignments, changes role/duty state, and inserts an audit event in the same transaction. | Actor still needs active-account enforcement. Target eligibility must also reject suspended accounts or explicitly define whether role correction is allowed while suspended; never reactivate implicitly through role changes. Keep active-assignment protection and atomic audit. |
| `api_admin_list_audit_logs(integer)` | Protected wrapper delegates to a revoked internal implementation; direct audit table SELECT also has a separate restrictive AAL2/TOTP policy. | Add active-Admin check to shared helper. Preserve direct-table restrictive MFA policy and the separate permissive Admin-role policy. |
| `api_admin_analytics_summary(timestamptz,timestamptz)` | Protected wrapper calls the same helper before delegating to internal analytics implementation. | Add active-Admin check to shared helper; analytics must not remain accessible to a suspended Admin via an unexpired JWT. |
| `api_admin_list_audit_logs_page(...)` | Dedicated bounded/searchable page endpoint calls the shared helper directly; limits page size to 100, offset to 1,000,000, query length to 120, validates action/date/profile filters. | Same missing active-Admin check in shared helper. |
| `api_admin_list_investigation_events(...)` | Calls the shared helper before reading an allowlisted union of admin, Auth, incident, claimability, requester-edit, and assignment histories. It bounds pagination and filters; projects minimized details rather than raw Auth payload/IP fields. | Same missing active-Admin check. Do not duplicate divergent role/MFA logic in this RPC; strengthen the shared helper and test every wrapper. |
| Staged `api_admin_set_account_status(bigint,text,text)` | SECURITY DEFINER transition implementation calls the shared Admin MFA helper, locks the target profile, validates status/reason, blocks Admin targets/self-suspension, and inserts an immutable transition event in the same transaction. Its migration revokes client EXECUTE and grants no client role. | Keep it unavailable to client routes; preserve active-Admin AAL2/TOTP, target-state validation, audit atomicity, and profile-lock ordering. Prove transition-vs-role/duty concurrency before any explicit client grant decision. |
| `api_admin_list_account_lifecycle_events(integer,integer)` | New bounded reader calls the shared Admin MFA helper before reading the lifecycle audit ledger; direct client ledger access remains revoked. | Preserve page-size/offset bounds and ensure suspended Admins with still-valid JWTs are denied before any lifecycle history is returned. |
| `api_admin_list_users()` | Legacy unbounded list function remains in the schema but is explicitly revoked from PUBLIC, anon, and authenticated; current TOTP migration keeps it unavailable. | Keep revoked. Add a regression assertion so future migrations cannot accidentally re-grant it. |

## Shared Admin MFA helper

Current implementation: `private.tacresq_require_admin_mfa()` in `20261122050858_require_totp_for_admin_rpcs.sql`.

Observed checks:
- `auth.uid()` must be present.
- JWT `aal` must be `aal2`.
- JWT `amr` must contain a TOTP method, supporting object and string representations.
- The current profile resolved by `auth.uid()` must have role `admin`.
- The helper is SECURITY DEFINER with `search_path = pg_catalog`.
- EXECUTE is revoked from PUBLIC, anon, and authenticated; it is invoked from trusted SECURITY DEFINER wrappers.

**Gap:** it does not check an account-state field. That is the correct shared enforcement point for active Admin access once a constrained account state exists. It must reject suspended Admins even when their JWT remains valid. Do not weaken AAL2/TOTP while adding this check.

## Direct table authorization

### `admin_audit_logs`

- Authenticated has SELECT only; clients have no INSERT/UPDATE/DELETE grants.
- `admin_audit_logs_read_admin` is PERMISSIVE and checks Admin role.
- `admin_audit_logs_require_aal2` is RESTRICTIVE and checks AAL2 plus TOTP AMR.
- PostgreSQL combines permissive policies with OR and restrictive policies with AND. Preserve the restrictive policy type.
- Suspension must add an active-account condition without converting the MFA restriction into a permissive policy.

### `incident_assignment_events`

- A separate RESTRICTIVE SELECT policy allows non-Admin access under its normal existing rules, but requires AAL2/TOTP for Admin reads.
- Suspension must not block legitimate active dispatcher reads or weaken the Admin MFA branch. Confirm the complete permissive policy set and current role policy during implementation; this restrictive condition alone does not establish account activity.

### `profiles`

- The existing profile trigger protects identity fields and restricts role changes; the Admin role-change RPC is the controlled path for ordinary role changes.
- The trigger's role/duty and active-assignment guards do not provide account-state enforcement.
- The Admin directory currently returns profile identifiers and limited identity/role/duty metadata. If suspension state is later exposed, return only the minimum fields needed by the Admin use case.

## Implementation implications

1. Add the active-account check to the shared trusted Admin authorization helper, then test all six protected public Admin endpoints with an active Admin, suspended Admin, non-Admin, missing UID, AAL1, AAL2 without TOTP, and AAL2 with TOTP.
2. Add an explicit test that a suspended Admin's still-valid JWT cannot read audit rows directly or call analytics, directory, role-change, paginated audit, or investigation RPCs.
3. Keep the legacy unbounded user-list RPC revoked.
4. Keep role changes and audit inserts transactional; suspension/reactivation needs its own action values and immutable audit fields rather than overloading role-change records.
5. Preserve the restrictive AAL2/TOTP policy on direct audit table access and add active-account enforcement without changing policy composition.
6. Decide and test target-account behavior: role changes must not silently reactivate a suspended target or bypass the ordinary suspension reactivation audit.

## Source files inspected

- `supabase/migrations/20261112215653_admin_role_audit_foundation.sql`
- `supabase/migrations/20261122050858_require_totp_for_admin_rpcs.sql`
- `supabase/migrations/20261125020814_admin_audit_server_pagination.sql`
- `supabase/migrations/20261122120710_admin_investigation_events.sql`
- `supabase/migrations/20261125020838_account_lifecycle_admin_transition_rpc.sql`
- `supabase/migrations/20261125020844_admin_account_lifecycle_audit_reader.sql`
- `tools/test_admin_totp_contract.py`
- `tools/test_supabase_migration_replay.sh`

No connected Supabase project was queried or modified. These are repository migration findings.


## PR #347 lifecycle implementation follow-up

Migration `20261125020838_account_lifecycle_admin_transition_rpc.sql` adds `api_admin_set_account_status(bigint,text,text)` as a public SECURITY DEFINER entry point. It delegates actor authorization to the shared Admin MFA helper, serializes against profile changes, validates the transition and reason, and writes the lifecycle audit row in the same transaction. Migration `20261125020844_admin_account_lifecycle_audit_reader.sql` adds the bounded `api_admin_list_account_lifecycle_events(integer,integer)` reader behind the same Admin MFA boundary. Both signatures are included in the clean-replay callable inventory; they remain staged and are not live-deployed.


**Callable inventory note:** `api_admin_set_account_status(bigint,text,text)` is intentionally not counted as an authenticated-callable public function because client EXECUTE remains revoked. The bounded `api_admin_list_account_lifecycle_events(integer,integer)` reader is client-callable after the shared Admin MFA gate and is counted in the 22-signature baseline.
