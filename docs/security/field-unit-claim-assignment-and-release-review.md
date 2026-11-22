# Field-unit claiming, team assignment, and release review

**Scope:** Source review of the field-claim RPC, dispatcher team assignment, assignment exclusivity, duty-state synchronization, claimability lifecycle, assignment release, and assignment event history. This is not a live-database verification.

## Field claiming

- Only authenticated responder/driver profiles can claim; the server derives the profile from `auth.uid()` and rechecks role after locking the profile.
- The claim RPC locks the incident first and then the field-unit profile. This serializes claims for one incident and prevents the same unit from claiming two different incidents concurrently.
- Claiming requires an explicitly available unit and an incident marked claimable in pending/assigned state. A unit already assigned to that incident receives an idempotent already-assigned result.
- It checks both normalized team membership and the legacy `incidents.assigned_to` reservation before allowing a second active assignment.
- Team capacity is limited to five. The claimable queue excludes incidents that are full and excludes field units who are already actively assigned.
- A successful field claim inserts membership and moves a pending incident to assigned. The legacy `assigned_to` field remains dispatcher-managed for this path.

## Dispatcher team assignment

- Dispatcher-only team assignment accepts 1–5 distinct profile IDs, locks the incident, then locks selected profiles in stable ID order.
- Every selected member must be a responder/driver, explicitly available, and free of active assignments in both the team-membership table and legacy primary-assignee field.
- Team expansion is limited to pending, assigned, or en-route incidents, with no more than five total members; expansion is blocked after arrival.
- Incident and profile locks serialize assignment against field claiming and competing dispatcher assignments. The repository includes a deterministic cross-path concurrency test script, but it was not run during this review.

## Duty state and release

- Login does not automatically put responders/drivers on duty. They must explicitly choose Available or Off duty.
- Active team membership and legacy assignment reservations prevent users from changing their duty status or role.
- Dispatcher progress synchronizes each assigned member's operational status to the shared incident milestone. The profile guard permits only this narrow dispatcher-driven mirror; it rejects arbitrary client-set progress states.
- Dispatchers can release a team member only while the incident is assigned or en route, and only if at least two members are currently assigned. This prevents an incident from being left without a response unit.
- Releasing the legacy primary member hands `assigned_to` to a remaining current team member. The trigger permits this only when the former primary is no longer a member and the replacement is.
- A released member is returned to Available only if no other active team or legacy assignment still reserves them. Existing unrelated duty state is preserved.
- Release is transactional with the membership deletion and event-history trigger. A release after arrival is rejected; after arrival, the team should remain attached until the incident closes or an explicitly designed handoff workflow exists.

## Claimability and assignment history

- Only dispatchers can toggle claimability. The hardened RPC locks the incident, rechecks the dispatcher's role under lock, and only enables claims for pending/assigned incidents.
- Status progression automatically clears claimability. Claimability changes and automatic clearing are written to a restricted claimability log.
- `incident_assignments` is a read-only client surface: authenticated users can SELECT rows allowed by RLS, while assignment mutations are restricted to reviewed RPCs. Table-wide and column-level client mutation grants are revoked.
- `incident_assignment_events` records assigned, released, and incident-closed events, including actor, source, status, and timestamp. Its SELECT policy is dispatcher/Admin-only; client roles receive no insert/update/delete grants.

## Remaining risks and product decisions

1. **Assignment is not a shift roster.** The current Available/Off duty status is an explicit operational toggle, not proof of scheduled shift, vehicle readiness, credentials, or dispatch acknowledgement. If shift scheduling is required, design it separately rather than overloading duty status.
2. **Release is intentionally narrow.** A dispatcher cannot release the last member or remove members once the incident reaches on-scene. Define a controlled reassignment/escalation process for breakdowns, wrong-team assignment, or personnel safety emergencies without creating an orphaned active incident.
3. **No explicit acknowledgement workflow is evident in these reviewed boundaries.** If the operational procedure requires responders to accept/decline a dispatcher assignment before it becomes active, add a distinct acknowledgement state and timeout policy; do not infer acknowledgement from membership.
4. **Stale availability and client refresh remain UX concerns.** The dispatcher UI reconciles selections against the latest successful availability snapshot, but should visibly show snapshot age and require refresh after a rejected assignment.
5. **Event-history completeness depends on database triggers.** Confirm all supported assignment writes go through the protected RPCs and that privileged service-role maintenance is separately controlled and logged.
6. **Concurrency tests need execution in a disposable test database.** Run field-claim vs dispatcher-assignment, two-dispatcher assignment, release vs team expansion, release vs incident progress, and concurrent duty-status changes before release.

## Evidence boundaries

Reviewed migration source for dispatch team assignment, per-responder status, field claiming and exclusivity, claimability lifecycle, dispatcher duty synchronization, assignment event history, dispatcher release, and assignment-table privileges, plus the corresponding source-level contract/concurrency tests. No test script was executed, no live records/catalog were queried, no migration was applied, and no production state was changed.
