# Incident Status Lifecycle and Team Availability Review

## Scope and evidence

This review follows the repository's migration source on the PR branch. It does not query the live Supabase catalog or production records, apply migrations, or execute tests.

Reviewed source:
- `20261015080806_tacresq_core_schema.sql`
- `20261022040223_fix_incident_status_audit_trigger.sql`
- `20261023081706_dispatch_team_assignment.sql`
- `20261023103737_per_responder_assignment_status.sql`
- `20261116042927_responder_incident_claiming.sql`
- `20261117234000_dispatch_managed_incident_progress.sql`
- `20261118010000_incident_assignment_event_history.sql`
- `20261118011000_dispatcher_release_incident_assignment.sql`
- `20261118090000_restrict_incident_assignment_table_privileges.sql`
- `20261122090000_admin_analytics_summary.sql`

## Current lifecycle

| Transition | Authorized actor / path | Notes |
|---|---|---|
| Create → pending | Authenticated requester creation RPC | Initial status row is recorded by the incident status trigger. |
| Pending → assigned | Dispatcher team-assignment RPC, or eligible on-duty responder/driver claim RPC when the incident is claimable | Team membership is represented in `incident_assignments`; the first claim advances the incident. |
| Assigned → en_route | Dispatcher progress RPC | Updates the shared incident and synchronizes all current team members. |
| En_route → on_scene | Dispatcher progress RPC | Same shared-team synchronization. |
| On_scene → transporting | Dispatcher progress RPC | Same shared-team synchronization. |
| On_scene / transporting → resolved | Dispatcher progress RPC | Resolves all current team assignments and returns units to available when they have no other active assignment. |
| Pending → cancelled | Requester cancellation path | Only the owning requester, while still pending and unassigned, can cancel. |

The older per-responder progress RPC is explicitly revoked in the dispatcher-managed progress migration. The individual `responder_status` field remains useful as a per-member state snapshot, but current shared progress is dispatcher-managed.

## History and reporting

### Incident status history

- `incident_status_logs` stores the incident ID, status, actor profile ID, and timestamp.
- The corrected audit trigger writes the initial status after the incident row exists, avoiding the earlier before-insert foreign-key problem.
- Subsequent status changes are recorded when the incident status changes.
- The history is suitable for reconstructing incident milestones, but it is not a complete security-event ledger. It does not capture every denied action, authentication event, or arbitrary administrative activity.

### Assignment membership history

- `incident_assignment_events` records team-member assignment, release, and incident closure.
- It is append-only through trigger-driven insertions in the reviewed migration.
- Dispatcher and Admin roles have read access under its RLS policy.
- Current membership remains in `incident_assignments`; the event table is the historical record.
- A resolved incident keeps its assignment membership rows; the closure event marks the terminal transition. Active-work queries exclude terminal incident statuses.

### Analytics

The Admin analytics RPC classifies:
- `pending`: requests still waiting for assignment.
- `active`: assigned, en_route, on_scene, or transporting.
- `resolved` and `cancelled`: separate terminal counts.
- Assignment time: first recorded `assigned` status timestamp minus incident creation time, where the assignment timestamp is not earlier than creation.

This is an incident-created-period report, not a count of all incidents that were active during any portion of the selected period. Assignment-time averages only include incidents with a usable first assigned status log. The UI/report should retain those definitions so the metrics are not misinterpreted.

## High-priority integrity finding: direct dispatcher status updates can bypass synchronization

The dispatcher-managed progress RPC performs a coordinated transaction:
1. Locks and validates the incident and requested transition.
2. Updates the incident status.
3. Updates every current `incident_assignments.responder_status` and `status_updated_at`.
4. Updates each assigned profile's duty status.
5. Preserves availability when a unit has another active assignment.

However, the migration history also retains:
- An authenticated UPDATE grant on incident status/assignment columns from the core schema.
- An RLS update policy that permits dispatchers to update incidents.
- A trigger guard branch that accepts valid dispatcher milestone changes directly on the incident row.

That means a dispatcher-authorized direct table update may satisfy the row-level policy and transition guard without invoking the RPC's synchronization steps. The incident status log and closure event triggers may still fire, but the team member status snapshots and profile duty states are not guaranteed to be synchronized by those triggers. The older status trigger updates only the legacy `assigned_to` profile, not every team member.

**Recommended correction:** make the coordinated progress RPC the only authenticated client write path for incident milestones. Before removing the direct table UPDATE path, inspect every Android, dispatcher-web, and Laravel caller to confirm all legitimate progress writes already use the RPC. Then revoke the relevant direct UPDATE grant (or narrow it to non-progress columns if any legitimate direct writes remain), retain the RLS read boundary, and add negative tests proving direct dispatcher status updates are denied while the RPC continues to synchronize all team members. Do not apply this change to a live database without an approved migration/release.

## Cancellation and availability

- Requester cancellation is restricted to pending incidents with no legacy assignment and no team membership. Therefore, normal requester cancellation does not need to release a response team.
- Resolved incidents are terminal. The dispatcher progress RPC sets each assigned member's responder status to resolved and updates duty status to available only if that member has no other active assignment.
- The assignment closure trigger records an `incident_closed` event for each current member. It does not itself delete membership rows or reset duty status; those effects are handled by the progress RPC and active-status filtering.
- The dispatcher release RPC is restricted to pre-arrival assigned/en_route incidents, prevents releasing the last member, records the release event, and returns the released member to available only when no other active incident reserves them.

## Follow-up work

Priority order:
1. **P0 — Close the direct status-update bypass** after confirming all client callers and adding regression coverage for RPC-only progress.
2. **P1 — Add transaction-level tests** for multi-member resolution, a member concurrently assigned to another incident, release of the primary member, and status changes racing with assignment/release.
3. **P1 — Validate status-history completeness** for creation, claim, dispatcher assignment, each milestone, requester cancellation, and legacy compatibility paths. Include actor identity and event ordering.
4. **P1 — Make metric definitions visible** in Analytics, especially the incident-created date scope and the first-assignment-time denominator.
5. **P2 — Define retention and export controls** for incident status and assignment histories; these histories reveal operational response patterns.

## Verification boundary

This is a source-level review only. No test suite, migration replay, live database catalog, production data, or deployment was accessed or changed.
