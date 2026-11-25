# Account suspension authorization review — batch 3: dispatch, assignments, and GPS

**Branch:** `security/admin-account-suspension`  
**Status:** source review only; no enforcement migration or production change.

## Callable operations and current controls

| Entry point / surface | Existing checks observed | Account-state gap and required boundary |
|---|---|---|
| `api_assign_incident_team(bigint,bigint[])` | Final public endpoint is SECURITY INVOKER and delegates to `private.tacresq_assign_incident_team_guarded`, the authenticated-callable SECURITY DEFINER guard. The guard share-locks and validates an active dispatcher, then calls `private.tacresq_assign_incident_team`, the preserved SECURITY DEFINER internal implementation (with client EXECUTE revoked) that validates 1–5 distinct field-unit IDs, locks incident and selected profiles, checks available responder/driver roles and active assignments, and writes assignment rows/incident state transactionally. | Dispatcher actor and every selected target need active-account checks inside the private implementation. A suspended field unit must not be assignable merely because its duty status remains available. Preserve incident/profile lock order and capacity/active-incident checks. |
| `api_assign_incident(bigint,bigint)` | Final public endpoint is SECURITY INVOKER and delegates to `api_assign_incident_team` with a one-member array. | Must inherit the same actor and target checks from the private team-assignment implementation; add a regression test through the legacy wrapper so it cannot bypass them. |
| `api_update_incident_status(bigint,text)` | SECURITY INVOKER wrapper delegates to private `tacresq_dispatch_update_incident_status`. Private SECURITY DEFINER implementation checks dispatcher role, locks incident, validates allowed transition, updates incident, assignment progress, and field-unit duty statuses in one transaction. | Dispatcher account state must be checked inside the private implementation before mutation. Do not infer active state from dispatcher role. Preserve incident lifecycle and team progress synchronization. |
| `api_all_incidents()` | SECURITY INVOKER dispatcher queue function; checks dispatcher role, then reads incidents. Its underlying table access is subject to RLS. | Role check alone does not reject a suspended dispatcher. Require active account before queue data is returned and preserve RLS restrictions for direct table reads. |
| `api_my_incidents()` | SECURITY INVOKER function returning requester-owned or assigned incidents through RLS/assignment helper predicates. | It has no explicit account-state check. Add restrictive active-account policy coverage for direct table access and a defined denial/empty behavior for this RPC. Avoid leaking incident details to suspended accounts. |
| `api_units()` | SECURITY DEFINER dispatcher-only field-unit directory; checks current dispatcher role and returns unit identity/role/duty information. | No active-account check for dispatcher caller, and no filter excluding suspended field-unit targets. A suspended dispatcher must not read the directory; suspended responders/drivers should not be presented as eligible units. |
| `api_set_duty_status(text)` | SECURITY INVOKER; locks caller profile, requires responder/driver role, permits available/off_duty only, rejects active assignments, then updates duty status. Profile update trigger adds role/duty and active-incident guards. | Duty state is not suspension. Reject suspended callers without changing duty status; do not automatically toggle duty status during suspension or reactivation. |
| `api_record_location_v2(...)` | SECURITY INVOKER wrapper delegates to private SECURITY DEFINER writer. Writer validates coordinates, accuracy, capture-time freshness, caller role, active incident/assignment status, and an 8-second rate limit; it locks incident and assignment rows and serializes per-user writes. | Writer does not check account state. A suspended responder/driver could continue GPS writes using a valid JWT while still assigned and en_route. Add active-account check after identity/assignment resolution and before insert. |
| `api_active_responder_locations()` | SECURITY INVOKER wrapper delegates to private SECURITY DEFINER reader; private helper requires dispatcher role and returns latest fresh locations for active en-route assignments. | Reader does not check dispatcher account state. Suspended dispatcher must not retrieve responder coordinates. Keep freshness window, per-member visibility, and role boundary. |
| `private.tacresq_assign_incident_team_guarded(bigint,bigint[])` | SECURITY DEFINER guard with authenticated EXECUTE grant; checks the active dispatcher under `FOR SHARE` and delegates to the preserved invoker implementation. | Keep the guard as the only client-callable private assignment boundary; the underlying `private.tacresq_assign_incident_team(bigint,bigint[])` implementation has authenticated/anon EXECUTE revoked. |\n| `private.tacresq_is_incident_assignee(bigint)` | SECURITY DEFINER helper with authenticated EXECUTE grant; answers whether the caller belongs to an incident assignment. Used by RLS and invoker functions. | Direct authenticated invocation and every RLS use need to respect suspended account state. A boolean membership result must not make a suspended account pass a larger policy predicate. |
| `private.tacresq_record_location_v2(...)` | SECURITY DEFINER private writer with authenticated EXECUTE grant; narrow insert path after role/assignment/status/coordinate/rate validation. | Direct callable private entry point; must independently enforce active account, not rely on the public invoker wrapper. |
| `private.tacresq_active_responder_locations()` | SECURITY DEFINER private reader with authenticated EXECUTE grant; checks dispatcher role before returning coordinate rows. | Direct callable private entry point; must independently enforce active dispatcher state. |
| `private.tacresq_dispatch_update_incident_status(bigint,text)` | SECURITY DEFINER private mutation with authenticated EXECUTE grant; checks dispatcher role and validates incident transitions. | Direct callable private entry point; add active dispatcher check inside it. Preserve atomic updates across incident, assignments, and duty statuses. |

## Operational invariants to preserve

- Dispatcher assignment is a coordinated database operation; suspension must not silently release, cancel, or reassign a live incident.
- Active assignment eligibility must inspect both legacy `incidents.assigned_to` and normalized `incident_assignments`.
- Team membership limit remains five; a selected unit must be an active, eligible responder/driver and not already busy.
- A field unit's duty status is operational state, not account access state.
- GPS writer authorization depends on incident membership and synchronized `en_route` status; add account state without weakening coordinate validation, timestamp freshness, or server-side rate limiting.
- Dispatcher GPS reads are sensitive location data and need active-account checks on the private definer reader, not only the public wrapper.
- Invoker RPCs and direct PostgREST table reads still need restrictive RLS coverage; a function-level role check does not cover other routes.

## Implementation test cases

1. Suspended dispatcher with a still-valid JWT cannot read queue/units/GPS or assign/release/update an incident.
2. Active dispatcher cannot assign a suspended responder/driver, even if duty status is `available`.
3. Suspended responder/driver cannot claim, record GPS, or mutate any assignment; an existing active assignment remains intact.
4. Active responder/driver retains current claim and GPS behavior.
5. Direct authenticated invocation of each granted private helper is denied or returns no sensitive data when suspended.
6. Direct table reads through RLS are denied for suspended callers, including `incidents`, `incident_assignments`, and `location_logs`.
7. Suspension racing with assignment, claim, status progression, or GPS write is deterministic and cannot leave partially updated operational state.
8. Reactivation restores normal eligibility only after its state/audit transaction commits; duty status and incident assignments remain unchanged.

## Source files inspected

- `supabase/migrations/20261023081706_dispatch_team_assignment.sql`
- `supabase/migrations/20261023082526_harden_dispatch_team_assignment.sql` (confirm final definitions against the replay after each migration change)
- `supabase/migrations/20261116180000_harden_field_claim_exclusivity.sql`
- `supabase/migrations/20261116200000_harden_dispatch_claimability_lifecycle.sql`
- `supabase/migrations/20261117234000_dispatch_managed_incident_progress.sql`
- `supabase/migrations/20261112215429_isolate_per_responder_location_writer.sql`
- `supabase/migrations/20261112215554_isolate_multi_responder_location_reader.sql`
- `tools/test_dispatch_claimability_lifecycle.py`
- `tools/test_location_rpc_search_path.py`
- `tools/test_per_responder_assignment_status.py`

No connected Supabase project was queried or modified. These findings are based on repository source.


## Account-lifecycle implementation follow-up — November 24

The source-review gaps for dispatcher claimability and responder-coordinate reads have since received forward-only guards in migration `20261124130000_account_lifecycle_claimability_and_responder_read_guard.sql`:

- `public.api_set_incident_claimable(bigint,boolean)`: the locked dispatcher profile re-read now requires `account_status = 'active'`. Existing incident → profile lock order, claimability state allowlist, no-op semantics, and transactional audit insertion are preserved.
- `private.tacresq_active_responder_locations()`: now checks and share-locks an active dispatcher before delegating to the preserved latest-fresh-location query. The preserved query is renamed `private.tacresq_active_responder_locations_internal()` and client EXECUTE is revoked.

These are implemented in the PR branch, not production-verified. Clean disposable PostgreSQL replay, same-valid-JWT suspended denial, and concurrency tests remain pending. No live migration was applied.


## Duty-status lifecycle implementation follow-up — November 24

The earlier `api_set_duty_status(text)` gap now has a staged implementation in migration `20261124140000_account_lifecycle_duty_status_guard.sql`. The RPC locks the caller's profile and requires both a responder/driver role and `account_status = 'active'` before updating operational duty state. It retains the active-incident restriction and does not automatically change duty status when an account is suspended or reactivated.

This implementation is source-reviewed and has a committed PostgreSQL replay contract. It is not yet runtime-verified: execute clean migration replay, same-valid-JWT suspended denial, and duty-change-versus-suspension concurrency tests before treating it as complete.
