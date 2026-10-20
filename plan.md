# TACResQ functionality plan

**Status:** Planned, not yet implemented  
**Scope:** Android responder flow, dispatcher web dashboard, Supabase authorization/data lifecycle, and requester status visibility  
**Product boundary:** TACResQ remains a school prototype and must not imply that it is an operational emergency-services system.

## Product decision

TACResQ will implement **dispatcher-only responder tracking during an active en-route response**. The dispatcher assigns a responder to an incident; the responder receives the incident destination and starts response explicitly. The responder's device location may then be sent to authorized dispatchers for that assigned incident only. Tracking stops when the responder reaches the scene or the incident ends.

The requester will initially see incident status and timestamps, but not the responder's exact live GPS. The dispatcher's own browser location is not part of assignment or tracking and must not be uploaded or shared.

## Target workflow

```text
Requester submits incident with optional one-time GPS fix
        ↓
Dispatcher reviews incident details and confirmed destination
        ↓
Dispatcher assigns an available responder/driver
        ↓
Responder receives the assignment and reviews destination metadata
        ↓
Responder explicitly starts response
        ↓
Server changes status to en_route and enables incident-bound tracking
        ↓
Responder app sends validated foreground location updates
        ↓
Authorized dispatcher map shows the responder's latest fresh location
        ↓
Responder marks on_scene; tracking stops automatically
        ↓
Status continues through transporting/resolved as applicable
        ↓
Completed response no longer exposes a live responder marker
```

## Required state and authorization rules

The server remains the source of truth. The initial transition matrix is:

```text
pending → assigned
assigned → en_route
 en_route → on_scene
 on_scene → transporting | resolved
 transporting → resolved
```

Cancellation and reassignment must be explicitly defined per status before implementation. A responder cannot become `en_route` without an active assignment. Only the assigned responder/driver may advance an assigned incident. Only a dispatcher may assign or reassign. UI checks are convenience only; every rule must be enforced in Supabase functions/triggers/RLS.

A responder location update is accepted only when all of the following are true:

- The authenticated profile is a responder or driver.
- The profile is the incident's `assigned_to` user.
- The incident is currently `en_route`.
- The coordinate pair and accuracy are valid.
- The recorded timestamp is reasonable and not from the future.
- The request is within the rate limit.

Dispatcher location is never stored by this feature. Requesters cannot read responder location logs. Dispatchers can read only locations associated with incidents they are authorized to operate.

## Phase 1 — Correct dispatch fundamentals

### Responder Android client

- Replace any implied automatic travel state with an explicit **Start response** action.
- Show assigned incident type, description, confirmed coordinate, accuracy, capture timestamp, and landmark note before navigation.
- Add **Navigate to incident** using the responder's device as the local route origin; this does not share the responder location by itself.
- Show assignment, en-route, on-scene, transporting, and resolved states clearly.
- Preserve the current one-time requester GPS consent flow and no-fabricated-coordinate rules.

### Dispatcher web dashboard

- Remove or rename the current-device dispatcher route planner; it is not part of normal assignment.
- Add a dedicated incident detail view or expandable detail surface.
- Show confirmed GPS, accuracy, capture time, landmark note, and explicit missing-location states.
- Show assigned unit, status, and status history.
- Keep assignment confirmation, assignment errors, and conflict messages visible.
- Provide an external incident-location map link as an interim navigation capability.

### Supabase/backend

- Add or revise server-side transitions for explicit responder start response.
- Define cancellation and reassignment transitions.
- Add assignment conflict protection and idempotent assignment behavior.
- Preserve role authorization and existing RLS boundaries.

**Completion evidence:** A responder cannot be marked en route by assignment alone; the assigned responder can navigate to a confirmed incident destination; dispatcher and requester views show accurate status and location metadata.

## Phase 2 — Dispatcher-only responder tracking

### Data model

Extend location records to bind each update to an incident:

```text
location_logs
- id
- user_id
- incident_id
- latitude
- longitude
- accuracy_m
- recorded_at
- created_at
```

Add indexes for `(incident_id, recorded_at desc)` and `(user_id, recorded_at desc)`. Decide and document retention before production-like data is used. Keep the latest active location available for dispatch while limiting historical movement data.

### Location ingestion

Implement a narrow Supabase RPC or authenticated API for responder location updates. It must:

- Validate role, assignment, active status, coordinate range, accuracy, and timestamp.
- Apply a server-side rate limit.
- Reject updates after `on_scene`, cancellation, resolution, reassignment, or sign-out.
- Use an idempotency key or equivalent duplicate protection if updates can be retried.
- Avoid accepting client input that changes incident status.

Recommended initial client policy:

- Foreground-only tracking.
- Update approximately every 10–15 seconds while moving.
- Prefer a minimum movement threshold of approximately 15–25 meters.
- Send no location when permission is denied or GPS is unavailable.
- Do not replay expired queued points after the incident lifecycle ends.

### Android tracking lifecycle

- Before starting response, show a clear notice: location will be shared with authorized dispatch while responding to this assigned incident.
- Request location permission only in response to the relevant user action.
- Start tracking only after assignment, explicit start response, and successful transition to `en_route`.
- Show tracking state, last sent time, accuracy, network state, and error state.
- Automatically stop at `on_scene`, `transporting`, `resolved`, `cancelled`, reassignment, sign-out, or permission withdrawal.
- Never track off duty, on app launch, or for unrelated incidents.

### Dispatcher map

Initially, the dispatcher map may use an approved embedded provider or an interim map surface, but it must show:

- Incident destination marker.
- Assigned responder marker only when a valid recent update exists.
- Last update time and accuracy.
- Explicit freshness state.
- No marker for missing, invalid, unauthorized, or ended tracking.

Freshness thresholds:

```text
Fresh:       under 30 seconds
Aging:       30–120 seconds
Stale:       over 120 seconds
Unavailable: no valid location received
```

A stale marker must be visually distinct and must not be labeled live.

**Completion evidence:** An authorized dispatcher can see the assigned responder's fresh location only during `en_route`; an unauthorized user, unrelated incident, off-duty responder, or completed incident cannot expose a live marker.

## Phase 3 — Realtime and notification reliability

### Realtime

- Add Supabase Realtime for incident assignment/status and active location updates.
- Keep the existing polling refresh as a fallback.
- Display connection state: realtime connected, reconnecting, fallback sync, or unavailable.
- Reconcile realtime events with server reads so reconnects do not duplicate or lose state.
- Never treat a disconnected channel as proof that the last displayed location is current.

### Push notifications

Add FCM only after the assignment lifecycle is stable:

- New assignment.
- Assignment cancellation or reassignment.
- Important incident update.
- Response started.
- Responder arrived.
- Incident resolved.

Use privacy-safe lock-screen text. Do not expose medical details, full coordinates, or requester phone numbers in notification previews. Support token registration, revocation, invalid-token cleanup, and delivery failure handling.

**Completion evidence:** A responder is notified of an assignment without requiring constant app polling, and dispatch remains understandable during realtime or push failure.

## Phase 4 — Offline and audit behavior

### Offline rules

- Show offline state explicitly in the Android client and dashboard.
- Do not show a status as saved until the server confirms it.
- Retry status changes safely with idempotency protection.
- Prevent duplicate requester incidents on retry.
- Queue location updates only while the active tracking lifecycle is valid.
- Expire queued location points when the incident leaves `en_route`.
- Resolve conflicts from server state rather than local assumptions.

### Audit and history

Use `incident_status_logs` in the UI to show:

- Previous and new status.
- Actor.
- Timestamp.
- Assignment at the time.
- Cancellation/reassignment reason where applicable.
- Last valid responder location timestamp.

Do not add route playback or unrestricted location history by default.

**Completion evidence:** Users can tell whether an action is pending, confirmed, rejected, offline, stale, or completed, and an authorized user can reconstruct the incident state transitions.

## Provider decision

Do not choose an embedded map provider before the dispatch/location contract is stable. For the interim implementation, use confirmed-coordinate display and external navigation links. When an embedded map is needed, document one provider and its:

- Android package and web-domain restrictions.
- Publishable API-key configuration.
- Quotas and billing boundaries.
- Development versus production keys.
- Provider outage and no-map fallback.
- Terms and attribution requirements.

No service-role or private Supabase key may enter the browser or mobile client.

## Requester-facing scope

Initial requester experience:

- Incident submitted.
- Dispatcher reviewing.
- Responder assigned.
- Responder en route.
- Responder on scene.
- Incident resolved.
- Status timestamp and clear stale/sync state.

Do not expose exact responder GPS in the initial implementation. If requester-facing tracking is later proposed, treat it as a separate feature requiring responder consent, requester disclosure, approximate-location policy, automatic stop on arrival, stale handling, retention limits, and a privacy review.

## Validation strategy

Add focused tests before marking each phase complete:

- Valid and invalid assignment transitions.
- Assignment conflict and duplicate assignment handling.
- Explicit start-response behavior.
- Location permission denied, GPS timeout, and unavailable GPS.
- Invalid coordinate pairs, negative accuracy, future timestamps, and impossible jumps.
- Location update rejected for wrong user, wrong incident, wrong status, or ended incident.
- Fresh, aging, stale, and unavailable marker rendering.
- Realtime disconnect/reconnect and polling fallback.
- Duplicate offline status/location retries.
- Unauthorized responder-location reads.
- Route link generation for valid and missing coordinates.
- Notification privacy and invalid-token handling.

Use existing repository checks on every meaningful implementation step:

```text
pnpm --dir backend/web build
python3 tools/check_android_source.py
python3 tools/test_workflow.py
python3 tools/test_prototype.py
```

Add browser-level tests only when the project has a browser test harness; do not treat screenshots as a substitute for authorization and state-machine tests.

## Delivery order

1. Correct assignment/status lifecycle and remove the misleading dispatcher-location flow.
2. Add responder assignment detail and local navigation.
3. Add incident-bound backend location ingestion and authorization.
4. Add foreground responder tracking with explicit notice and automatic stop rules.
5. Add dispatcher map markers with freshness/accuracy states.
6. Add realtime updates with polling fallback.
7. Add notifications.
8. Add offline-safe retries and full audit history.
9. Reassess embedded map provider and optional requester-facing location only after the preceding behavior is stable.

## Design principles

- **Operational clarity:** each screen answers who is assigned, what the current state is, and when the last update happened.
- **Consent and least privilege:** location is collected only for a specific active response and shared only with authorized dispatch.
- **No false certainty:** stale, missing, inaccurate, or unavailable data is visibly labeled.
- **Server-enforced safety:** the client never gets to bypass assignment, role, or lifecycle rules.

The product voice should be direct and calm: “Responder location shared with dispatch while en route,” not “You are being tracked.” The interface should use clear status labels and avoid promising a traffic-free route, guaranteed ETA, or emergency-service reliability.
