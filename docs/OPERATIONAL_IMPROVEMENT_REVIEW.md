# TACResQ Operational Improvement Review
**Review date:** 2026-11-18  
**Repository:** `RamProjector/TACResQ-School-Project`  
**Working branch:** `agent/client-ui-polish-2026-11-17`  
**Pull request:** [#334](https://github.com/RamProjector/TACResQ-School-Project/pull/334)

## Purpose and review boundary

This review identifies the next system improvements without restarting work already implemented in PR #334. It combines repository inspection, the PR's recorded CI output, and read-only inspection of the connected production Supabase schema and migration ledger.

No production SQL, migration, user data, or Supabase configuration was changed. No build, test, emulator, or device check was run as part of this continuation.

## 1. Existing work to preserve

PR #334 already includes substantial work across requester, dispatcher, responder/driver, chat, and GPS flows:

- Dispatcher-owned shared incident milestones, with assigned team members inheriting the same incident progress.
- Multi-person team assignment and checks against active assignments.
- Requester submission idempotency, active-request/cancellation safeguards, edit limits and edit-history records.
- GPS location provenance, freshness handling, MapLibre clustering and marker reconciliation, map lifecycle cleanup, route-preview boundaries and failure messaging.
- Encrypted pending chat-image retry metadata, stable client message IDs, content fingerprint checks, and bounded image reads.
- Notification delivery and chat attachment safeguards from earlier work.

Do not reintroduce per-responder incident milestone updates, treat GPS proximity as arrival, or add an in-app radio feature.

## 2. CI stabilization commits

The exact previously inspected PR head had two recorded CI failures:

1. Android Lint reported suspicious indentation in `IncidentChatScreen.kt` around the pending-image retry flag.
2. The GPS empty-state source contract expected old accuracy-label prefixes that no longer matched the current MapLibre screen.

Corrections committed to the PR branch:

- `b19cc2a653459f9e17a4ee622277b343f9d1e820` — correct the chat retry flag indentation.
- `eb57c49ad7c82c5d0b2e7dbec77f5521f238f37f` — align the GPS source-contract assertions with the current accuracy-label prefixes.
- `6fb1eb00dd2f4706929064aa58db13a2426eba06` — record the corrections and live-schema findings in `CURRENT_TASK.md`.

These commits are source corrections, not proof that CI now passes. Inspect the workflow results for the latest PR head before reporting the failures resolved.

## 3. Production Supabase baseline (read-only)

The connected production project is healthy and runs PostgreSQL 17. The live migration ledger currently ends at `20261116042927 responder_incident_claiming`. The two dispatcher-progress migrations introduced by PR #334 are not present in the production migration ledger.

The inspected production schema includes:

- `profiles.duty_status`, currently constrained to `off_duty`, `available`, `en_route`, `on_scene`, and `transporting`.
- `api_set_duty_status(p_duty_status)`, which lets a responder/driver set only `available` or `off_duty`, and rejects changes while the user has an active incident.
- `incident_assignments`, keyed by `(incident_id, user_id)`, with `assigned_at`, `responder_status`, and `status_updated_at`.
- `api_assign_incident_team`, which locks selected profile rows and checks role, `available` duty status, and existing active assignments before assigning a team.
- `incident_status_logs` and `incident_request_edit_logs` for parts of the operational history.

The live `profiles` table does not currently expose an organization identifier, a qualification/certification model, or a duty-roster/shift table. No `duty_sessions` table exists in the inspected public schema. The live database still has the legacy per-responder status RPC; PR #334 proposes retiring that path, but the PR migrations have not been deployed.

**Deployment rule:** do not apply PR #334 migrations to production as part of routine PR work. First reconcile the exact PR head, migration replay, backend compatibility, and deployment order.

## 4. Personnel availability: recommended direction

### Separate concepts

Do not collapse these concepts into one field:

- **Authenticated:** the user has a valid application session.
- **Online:** the client currently has a live connection.
- **Scheduled/on duty:** the person belongs to an organization-approved duty roster for the current period.
- **Assignable:** the person is rostered for the current period, is an eligible responder/driver, has no active assignment, and is not covered by an approved unavailability exception.
- **Assigned/deployed:** derived from the authoritative incident assignment and incident lifecycle.

Login alone must not grant duty eligibility outside an approved shift. Conversely, a transient app disconnect must not silently erase a duty roster or release an active assignment.

### Recommended target model

Prefer an organization-managed recurring or dated shift roster over individual repeated Available/Off Duty toggles:

1. An authorized dispatcher/administrator prepares the roster and assigns responder/driver members to shifts.
2. Eligibility is computed from the active roster interval, account/role eligibility, exceptions, and active assignment records.
3. The app can show online/offline as a separate informational signal. Supabase Realtime Presence is suitable for connected-client presence, but not as the authoritative duty or assignment database.
4. Dispatch retains assignment authority. Existing organizational communication procedures remain outside TACResQ.
5. An active incident assignment makes a person unavailable for another incident regardless of login or presence.
6. When the incident is closed and the assignment is released, the person becomes eligible again only if the roster interval is still active and no exception applies.

If the organization does not maintain a predictable shift schedule, evaluate a single check-in/check-out action per shift as a fallback. Do not replace multiple status buttons with an equally repetitive manual process.

### Migration strategy

- Keep `profiles.duty_status` during a compatibility period; do not drop it immediately.
- Add a roster model only after confirming whether the project is single-organization or needs multi-organization scoping. The current live schema has no organization key, so do not invent tenant boundaries in an isolated migration.
- Make one database function/view the source of assignability. Use server-side checks in the assignment transaction; never trust a client-computed availability list.
- Serialize assignment decisions by locking the relevant profile/roster rows and incident row in a stable order. Preserve the existing active-assignment checks.
- Add audit records for roster creation/changes, membership changes, exceptions, and dispatch assignment decisions.
- Migrate Android fake API, Android Supabase API, Render/Laravel prototype, dispatcher dashboard, and tests together.
- Retire manual `api_set_duty_status` and old client controls only after all clients and server paths use the new model.
- Do not automatically mark an account available just because it signs in.

### Edge cases required before implementation

- User signs in outside their scheduled shift.
- User remains signed in after the shift ends.
- User loses connectivity during a shift.
- User logs out while assigned to an active incident.
- Dispatcher attempts concurrent assignments to the same person.
- Shift roster is edited after an assignment is made.
- An assigned person is unavailable, ill, or replaced.
- Incident is cancelled, resolved, or reopened.
- Device clock is wrong or time zone changes.
- A person is rostered but has not been credentialed for the needed resource type.

## 5. Other confirmed review targets

### A. Notification event coverage

The inspected live `user_notifications.event_type` constraint currently allows `incident_assigned`, `incident_status_changed`, and `incident_message`. The requester edit-log table exists, but the notification event constraint does not currently include an explicit request-correction or cancellation event.

Review whether dispatch receives a sufficiently prominent notification when a requester changes critical details after assignment. If not, add narrowly defined event types and transactional notification creation; avoid duplicate pushes and avoid placing sensitive patient details in push payloads.


#### Request-change notification implementation

Implemented on the PR branch in migration `20261118003000_dispatch_request_edit_notifications.sql`:

- An `AFTER INSERT` trigger on `incident_request_edit_logs` creates the notification in the same transaction as the requester edit RPC. If the notification insert fails, the edit transaction rolls back.
- The current schema has no organization or dispatcher-team key. The migration therefore notifies all profiles whose role is `dispatcher`; it does not pretend to provide agency-scoped routing.
- The notification uses a generic title/body and carries only the incident reference, event type, and source edit-log identity. It does not copy the request narrative, contact details, or coordinates into the notification or push event.
- `source_event_id` references the durable edit-log row. A partial unique index on recipient + source event prevents duplicate inbox alerts for the same correction.
- Existing notification insert triggers continue to provide the user-scoped Realtime hint, and the existing FCM worker can discover the new unread event through the shared notification table. Push delivery remains separate from the edit transaction.
- The Android notification model already treats `event_type` as a string, and the generic notification action opens the incident by its ID; no client enum migration was required.

Regression contract added at `tools/test_requester_edit_dispatch_notifications.py`. It checks trigger source, dispatcher-only recipients, event constraint, deduplication, locked-down trigger execution, and absence of sensitive field copying. The script has not been executed in this continuation.

**Scope and remaining verification:** the existing requester edit RPC only permits edits while an incident is pending and unassigned, so this event currently covers accepted pre-assignment corrections—not edits to an assigned/in-progress incident. Production Supabase was inspected read-only; this migration has not been applied there. Before deployment, replay the complete migration chain on a disposable database, execute the new contract and database integration tests, and confirm the Render FCM worker does not filter event types to the previous allowlist.

### B. Resource release and reassignment

The current assignment model has assignment time and progress timestamps, but no explicit release/demobilization timestamp or assignment-event history in the inspected columns. Review whether incident closure and team replacement leave enough information to reconstruct who was assigned, when they were released, and why.

Do not add a parallel status field. If needed, use an append-only assignment event record and preserve the current assignment table as the active/current membership representation.

### C. Organization scope and multi-agency support

The inspected live profiles schema has no organization identifier. Do not build cross-agency sharing, tenant-scoped RLS, or mutual-aid routing until the product explicitly defines organization membership, data ownership, incident transfer, and authorization. Multi-agency support is a later architecture project, not a quick feature.

### D. Patient handover

No patient-care or handover table was present in the inspected public schema. Evaluate only if ambulance transport is in TACResQ's approved operational scope. Keep the feature minimal: incident-linked handover, receiving facility, handover time, authorized recipient, and acknowledgment. Do not turn TACResQ into a hospital information system or invent clinical decision support.

### E. Offline and notification recovery

Existing SOS idempotency, cached status handling, and chat image retry protections are valuable. Audit remaining workflows for truthful delivery semantics: a locally queued assignment/status action must never appear server-confirmed. Do not allow offline assignment or incident progression to become authoritative without a server transaction.

### F. Account lifecycle

The screen review records incomplete password recovery, account deletion request handling, and server-enforced step-up verification. Implement only real backend-backed workflows; do not restore local-only toggles or success messages that imply a server operation occurred.

## 6. Prioritized implementation sequence

| Order | Work | Acceptance condition |
|---|---|---|
| 0 | Inspect latest PR #334 CI and reviews | Exact-head checks and remaining findings are known |
| 1 | Complete dispatcher-owned incident migration rollout plan | Clean migration replay, explicit compatibility and rollback plan; production untouched until separately authorized |
| 2 | Duty roster and derived assignability design | No per-person repeated status toggles; login/presence separated from duty |
| 3 | Assignment transaction hardening | Concurrent assignments cannot double-book one person |
| 4 | Request correction/cancellation notification | Dispatch sees critical changes with actor/time and no duplicate event |
| 5 | Assignment release and reassignment audit | Team membership and release history can be reconstructed |
| 6 | Offline workflow audit | Pending versus server-confirmed actions are visibly distinct |
| 7 | Account recovery and deletion workflows | Actual server-side completion and auditable outcomes |
| 8 | Patient handover / multi-agency feasibility | Implement only after organization scope and operational authority are confirmed |
| 9 | Analytics and performance | Metrics derive from trustworthy events and are not used as simplistic personnel rankings |

## 7. Technical references

- FEMA NIMS resource tracking and check-in: https://emilms.fema.gov/is_0703b/groups/251.html
- FEMA resource management: https://www.usfa.fema.gov/a-z/nims/managing-resources.html
- Supabase Realtime Presence: https://supabase.com/docs/guides/realtime/presence
- Supabase RLS guidance: https://supabase.com/docs/guides/database/postgres/row-level-security
- PostgreSQL explicit locking: https://www.postgresql.org/docs/current/explicit-locking.html
- PostgreSQL transaction isolation: https://www.postgresql.org/docs/current/transaction-iso.html

FEMA's NIMS material distinguishes resource tracking and status accountability from a user's application login. Supabase documents Presence as online/offline state for connected clients, not as a persistent duty roster. PostgreSQL row locks support serialized assignment decisions when the application performs its eligibility checks inside one transaction.

## 8. Execution constraints

- Keep all changes on PR #334 unless a separately justified scope requires another PR.
- Preserve existing GPS, requester, dispatcher, responder/driver, chat, and notification behavior.
- No radio functionality.
- No paid services or new infrastructure without approval.
- No production migration, destructive SQL, or account/data mutation without explicit authorization.
- No builds, tests, emulator, or device checks after commits unless the user changes that instruction. GitHub Actions may run automatically; inspect its results but do not claim locally executed validation.
- Never claim a fix is validated solely because source was edited.

## 9. Verified duty-control gap — 2026-11-18

Repository inspection of PR #334 confirms that incident milestone ownership has moved to dispatch, but personnel duty availability is still manually controlled in the responder UI.

### Current implementation

- `ResponderScreen.kt` exposes an Available / Off Duty control through `setDuty`.
- `DispatcherScreen.kt` derives its available-unit list from profiles whose `duty_status` is `AVAILABLE`.
- `Models.kt` still models duty as a profile-level `DutyStatus`.
- The dispatcher-owned incident progression migration synchronizes assigned personnel duty values as incident milestones advance.
- The follow-up migration permits only the limited user-set duty values `available` and `off_duty`, while protecting personnel attached to active incidents.

### Assessment

This is a partial automation model, not a duty-session model. It correctly avoids asking each assigned responder to advance the shared incident milestone, but still requires a person to manually declare availability. Authentication or online presence must not be substituted for duty authorization.

### Recommended next design

1. Inspect the existing organization, profile, assignment, and roster schema before adding any table.
2. Define who is authorized to start and end a duty period (individual, supervisor, or organization roster), based on the actual operating procedure.
3. Represent duty periods with auditable start/end events and an explicit source/authorizer.
4. Derive assignment eligibility from active duty, role/qualification, organization membership, account eligibility, and active-assignment constraints.
5. Keep connectivity freshness as a separate signal. A disconnected deployed responder must not become assignable.
6. Make assignment and eligibility checks atomic in PostgreSQL to prevent concurrent dispatchers from double-booking personnel.
7. Preserve the current dispatcher-owned incident milestone flow; do not reintroduce per-responder incident status controls.
8. Migrate existing `available` / `off_duty` values with an explicit compatibility strategy. Do not infer historical duty periods from login sessions.
9. Remove the responder selector only after the replacement duty workflow is available end-to-end and authorized users can manage exceptions.

### Acceptance criteria

- Login alone never makes personnel assignable.
- A duty session is attributable to an authorized actor or approved roster process.
- Personnel with an active deployment cannot be assigned to a conflicting incident.
- Temporary network loss does not release an assignment or silently end duty.
- Dispatch sees whether eligibility is confirmed, stale, or unavailable.
- Ending duty is blocked or requires an explicit operational handoff when an active assignment exists.
- Every duty and assignment change is auditable.
- Existing incident progression, assignment locks, and role-based RLS remain intact.

No schema or application changes are made by this review entry. This is a source-verified implementation gap and a design plan, not a claim that duty sessions already exist.


## 10. Assignment event history — 2026-11-18

Implemented on the PR branch in migration `20261118010000_incident_assignment_event_history.sql`:

- Keeps `incident_assignments` as the current-membership table; it does not replace or duplicate active assignment state.
- Adds append-only `incident_assignment_events` records for team-member assignment, explicit membership removal, and incident closure/cancellation.
- Captures the assigned-by profile for assignment events, the authenticated actor for explicit removal and incident closure when available, the member, incident, prior unit status, incident status, source, and database timestamp.
- Uses database triggers so the event is written in the same transaction as the assignment or incident status change.
- Enables RLS and permits reads only to authenticated dispatcher/admin profiles. Client-side insert, update, and delete privileges are not granted.
- Does not add an organization identifier or imply agency-level isolation. Dispatcher/admin visibility remains global in the current single-organization schema.
- Does not invent a release reason or automatically delete assignments when an incident closes. Closure is recorded as a distinct event for each current team member; explicit removal is recorded separately.

**Remaining work:** inspect every assignment mutation path and UI consumer, then expose a readable incident team timeline in dispatch. Validate migration replay and RLS behavior on a disposable database before production deployment. This migration has only been committed to PR #334; production Supabase remains unchanged. No build or tests were run for this commit.


### Assignment event-history security review — 2026-11-18

Read-only review against the connected production schema confirms the migration's referenced assignment and incident columns and foreign-key targets. Existing production RLS policies allow dispatcher incident/profile reads and participant assignment reads. The proposed event-history policy is narrower: authenticated users must resolve to a dispatcher/admin profile through `profiles.auth_user_id = auth.uid()`. The migration grants authenticated clients SELECT only and revokes direct table mutation privileges; trigger functions are placed in the non-exposed `private` schema with a locked search path and explicit execute revocation.

This is a source/schema review only. The new table, policy, and trigger functions do not exist in production because the PR migration has not been applied. Trigger execution, policy behavior, and migration replay still require validation in a disposable database before deployment. Production remains unchanged.


### Assignment mutation-path review — 2026-11-18

Read-only inspection of the live assignment RPCs and table policies found:

- Team assignment is performed through `api_assign_incident_team`, backed by `private.tacresq_assign_incident_team`. It requires dispatcher role, locks the incident and selected profile rows, checks current duty/active assignments, and inserts current team membership rows.
- The legacy single-member assignment RPC delegates to the team RPC.
- No dedicated dispatcher release/remove-member RPC was found in the inspected public/private function catalog.
- `incident_assignments` has authenticated table-level DELETE privilege, but the inspected RLS policy set contains only a SELECT policy. Therefore that grant alone does not provide an authenticated dispatcher a permitted delete path.
- The proposed assignment-history DELETE trigger can record a release only when a trusted mutation path actually deletes a membership row. It does not create a user-facing release capability by itself.

**Implementation gap:** team replacement/release needs an explicit dispatcher-authorized transactional RPC with incident/team validation, actor attribution, and a defined effect on the incident's legacy `assigned_to` primary-member field. Do not expose direct client deletes or build a release UI until that server-side contract is implemented. Production was inspected read-only; no database changes were made.


### Dispatcher assignment release RPC — 2026-11-18

- Added `api_release_incident_assignment(p_id, p_user_id)` as an authenticated, invoker-safe public wrapper around a dispatcher-checked private implementation.
- The operation locks the incident row before checking the active state and target membership. This serializes release with the existing team-assignment and incident-progress RPCs that also lock the incident.
- Release is limited to `assigned` and `en_route` incidents. A team cannot be left empty: the final member cannot be released through this operation. A separate, explicit incident cancellation/unassignment workflow would be needed for that case.
- If the released member was the legacy `assigned_to` primary, the RPC selects the earliest remaining team member as the new primary. A narrowly scoped incident-update guard permits this handoff only after the former primary is no longer a team member and the replacement is a current member; incident content and shared status must remain unchanged.
- The existing assignment DELETE trigger records the release actor and timestamp in `incident_assignment_events` within the same transaction. The released profile returns to `available` only if no other active incident still reserves that person.
- Added a static source contract at `tools/test_dispatcher_release_assignment_contract.py`. It has not been executed. Migration replay, RLS/role behavior, trigger execution, and Android integration remain unverified.
- This migration is committed to PR #334 only. It has not been applied to production Supabase.
