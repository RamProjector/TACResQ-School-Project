# Admin account suspension: direct RLS and table-privilege review (batch 8)

## Scope and evidence

Reviewed the repository's migration-defined direct table policies and grants for profiles, incidents, incident assignments, status history, location logs, FCM tokens, and Admin audit/investigation data. This is a source review of migration history, not a fresh live-project catalog snapshot.

## 1. Profiles

The current policy lineage defines:

- `profiles_read_self_or_dispatcher`: authenticated users can read their own profile; dispatchers can read profiles for unit/operational views.
- `profiles_update_self`: authenticated responders/drivers can update their own profile row, subject to the profile trigger's field and duty-status restrictions.
- The profile update trigger protects identity/role fields and active-incident role/duty constraints, but it does not represent an account-suspension guard.

**Suspension gap:** a suspended account can still satisfy the profile self-read predicate, and potentially the permitted self-update predicate, while its Supabase JWT remains valid. Dispatcher profile reads also need to exclude suspended profiles from operational unit listings without hiding historical actor identity in audit records. Separate "active operational unit" selection from immutable/historical identity display.

## 2. Incidents and assignments

The direct RLS policies permit:

- Incident SELECT for the requester, legacy assigned unit, normalized team assignee, or dispatcher.
- Incident INSERT for a requester profile with a pending, unassigned incident.
- Incident UPDATE for dispatchers or assigned units/team members.
- Assignment-history SELECT for requesters, assigned team members, or dispatchers.

The role/ownership/assignment checks establish *which relationship* a caller has to an incident. They do not establish that the caller's account is currently active. The normalized assignment helper `private.tacresq_is_incident_assignee(incident_id)` resolves membership by `auth.uid()`, but does not itself check account state.

**Suspension gap:** every direct RLS path must include the same active-account predicate. This is especially important for SELECT: a suspended participant must not retain incident details, team membership, status history, or assignment history through a still-valid JWT. Do not solve this by deleting assignment rows or changing incident status.

## 3. Location logs

The latest migration lineage narrows location-log SELECT to an en-route assignment relationship and allows authenticated INSERT only for a responder/driver who is assigned and en route. Later migrations revoke direct authenticated INSERT so location writes must pass through the validating/rate-limited RPC.

This is good least-privilege structure, but the remaining SELECT predicate and the write RPC/private writer need a current account-state guard. A suspended assigned responder must not continue streaming or reading sensitive coordinates merely because the incident remains en route. Keep historical location records intact; suspension is not a retention/deletion instruction.

## 4. FCM token table

The original FCM table migration grants authenticated SELECT/INSERT/UPDATE/DELETE and defines self SELECT, self INSERT, and self DELETE policies. The registration RPC is SECURITY INVOKER and uses the caller's profile mapping. Batch 4 already identified that these predicates do not include active account state.

Recommended policy split:
- Deny token enumeration/registration/updates for suspended accounts.
- Preserve only the narrowly scoped self-delete/unregister cleanup path if needed to remove a device token after suspension.
- Keep service-side delivery filtering separate: do not send to tokens belonging to suspended recipients.
- Do not delete all tokens or notifications as a side effect of suspension; retain operational evidence and allow explicit reactivation policy to decide token lifecycle.

## 5. Admin audit and investigation sources

The Admin audit table's permissive Admin SELECT policy is paired with a restrictive AAL2/TOTP policy. Assignment-event history has its own restrictive Admin AAL2/TOTP policy while preserving the existing dispatcher/participant read branch. The investigation RPC calls the shared Admin MFA helper.

The active-account guard must be additive and must not weaken these restrictive MFA policies or widen the existing dispatcher/participant branches. Suspended Admins with valid AAL2/TOTP claims must be denied. Preserve historical actor/target names and event details for authorized active investigators; don't erase audit rows when suspending an account.

## Enforcement design requirements

1. Create one fail-closed account-state predicate that resolves the caller through `auth.uid()` → immutable `profiles.auth_user_id`; do not trust user metadata, role, duty status, email matching, or JWT age.
2. Add the predicate as a restrictive authenticated policy where appropriate, or integrate it into each policy/helper while proving the final permissive/restrictive composition. A permissive policy alone cannot override an existing permissive policy; use a RESTRICTIVE policy for a universal authenticated guard when that is the intended policy composition.
3. For write policies, guard both `USING` and `WITH CHECK` as applicable. Review INSERT and UPDATE separately; do not assume a SELECT guard protects mutations.
4. Keep narrow cleanup exceptions explicit (e.g. self FCM-token delete) and test that they cannot be used to read, register, update, or reassign tokens.
5. Preserve historical rows, incident assignments, status transitions, location history, and audit evidence. Suspension must not cancel, release, reassign, or rewrite ongoing emergency operations.
6. Test each policy with an active user and a suspended user whose JWT remains valid, across requester, responder, driver, dispatcher, and Admin roles. Include requester/assigned/other-user/dispatcher relationship permutations and verify that Admin MFA remains independently enforced.

## Policy surface summary

| Table | Direct client surface | Account-state coverage in reviewed policy source |
|---|---|---|
| `profiles` | Self SELECT; dispatcher SELECT; restricted field-unit self UPDATE | No explicit suspension condition |
| `incidents` | Participant/dispatcher SELECT; requester INSERT; dispatcher/assigned-unit UPDATE | Relationship checks only |
| `incident_assignments` | Participant/dispatcher SELECT | Assignment/role relationship only |
| `incident_status_logs` | Participant/dispatcher SELECT | Incident relationship only |
| `location_logs` | Restricted SELECT; direct INSERT later revoked in favor of RPC | En-route/assignment checks only |
| `fcm_tokens` | Self SELECT/INSERT/DELETE; authenticated table grants include UPDATE | Self ownership only; consider narrow delete exception |
| `admin_audit_logs` | Admin SELECT | Admin role + restrictive AAL2/TOTP; no account-state check |
| `incident_assignment_events` | Participant/dispatcher/Admin SELECT | Relationship checks + restrictive Admin AAL2/TOTP; no account-state check |

## Verification boundary

No connected Supabase catalog was queried in this batch. The policy definitions above are from repository migrations; live policy drift must be checked read-only before designing the final migration. No policies, grants, rows, assignments, tokens, or incidents were changed.
