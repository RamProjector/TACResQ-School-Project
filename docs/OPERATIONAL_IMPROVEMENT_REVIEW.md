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
