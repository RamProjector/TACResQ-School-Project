# TACResQ mobile wireframe revision — design and behavior specification

## Purpose and boundary

This revision turns the previous four static happy-path screen illustrations into a clickable, role-aware design prototype. It demonstrates requester, responder/driver, and dispatcher navigation; SOS review and confirmation; assignment confirmation; status transitions; map entry; privacy and safety information; and simulated GPS/network states.

It is a **wireframe prototype, not an operational emergency system**. It has no authentication, database, phone GPS, Android permission prompts, map provider, navigation route, push notifications, emergency-number integration, or server. All names, incident data, timestamps, statuses, and plotted locations are invented. The map is a schematic drawing, not a Tacloban street map. A button that changes prototype state does not notify TACRU.

## Global navigation and visual rules

Every signed-in role has a five-position bottom navigation layout with two ordinary destinations on each side of a raised center **GPS Map** control. The map control uses a location target symbol and an explicit text label, stays centered between the surrounding destinations, has a larger high-contrast hit target, and shows a selected state while its destination is open. Its accessibility name is “Open full-screen GPS map.” The center map control is separate from the requester’s red SOS action.

| Requester | Responder / driver | Dispatcher |
|---|---|---|
| Home | Current call | Queue |
| My requests | History | Field units |
| **GPS Map** | **GPS Map** | **GPS Map** |
| Safety | Safety | Activity |
| Profile | Profile | Profile |

Navigation state is role-specific; switching the prototype role takes the user to that role’s default screen but retains the shared sample incident so the end-to-end flow can be demonstrated.

The prototype uses consistent status words, keeps one active requester incident at a time, uses persistent content labels in addition to color, keeps primary controls visible, and avoids pretending that a location is live when its source and freshness are not established. The “Network” and “GPS condition” controls in the design studio are scenario simulators, not runtime controls in the Android product.

## Role screen inventory

### Authentication

The sign-in preview identifies requester self-registration separately from privileged responder, driver, and dispatcher provisioning. It includes privacy/terms access and does not collect or transmit credentials. The demo “Continue” action simply enters the selected role workspace.

### Requester

**Home, no active request:** Emergency type; optional description; plain-language location sharing explanation; location-readiness state; GPS permission/accuracy help; a review step before the red SOS action; and an explicit note that this prototype does not contact dispatch. The SOS control is blocked if an active request or unsent request already exists.

**Location consent:** Before the first SOS confirmation, explain that the initial incident location is shared with dispatch and the assigned response team. Require an explicit checked acknowledgement. The screen also states what this preview does not do. In a real system, consent copy, access scope, retention, withdrawal, legal basis, and support contact require TACRU/privacy review.

**SOS review and confirmation:** Show incident type, optional description, destination/location summary, and any poor-accuracy warning. Sending is a separate deliberate action. Going back leaves the request unsent.

**Request sent / requests:** Show incident ID, status, incident timeline, initial fix accuracy, cancellation where allowed, and map entry. If the prototype network is offline at send time, show **Not sent** and keep an unsent retry card; do not say dispatch received it. Restoring network allows the user to retry through the same explicit confirmation.

**Cancel:** Ask for confirmation and a reason. Offline cancellation is not represented as successful; tell the user the cancellation was not transmitted. On cancellation, mark the sample request cancelled, move it to history, and remove it from active state.

**Requester map:** Display the incident pin as the requester's **one-time initial fix**, sharing status, accuracy, and the absence of ongoing updates. No moving requester marker is shown. Do not offer “stop live sharing” until live sharing has actually been implemented.

### Responder / driver

**Current call:** Show duty status, assigned incident, requester details as authorized, initial incident coordinates and accuracy, a full-screen map entry, external-navigation handoff explanation, and next valid status action. Keep “Cannot safely respond” available so unsafe conditions have an escalation path rather than pressuring the responder to continue.

**No assignment:** Explain that an assignment has not been made. Display pending incident state only if the demo has one. Do not invent a responder's active location or a route.

**Status progression:** `Unit assigned → Unit en route → Unit on scene → Transporting → Resolved`. Status changes require an explicit confirmation. “Resolved” has a stronger confirmation. After resolution, return the unit to available state and move the incident to history in the demo. Production status transitions must be accepted by the server and tied to the authorized assigned unit.

**Offline status update:** Do not claim that dispatch has been informed. Display a queued/unsynced state and synchronize only when connectivity returns. The prototype demonstrates one queued status update; a production app still needs durable storage, idempotency, ordering, retry limits, conflict handling, and user-visible sync history.

**Responder map:** Show only the assigned incident’s initial coordinate. No unit marker, live route, distance, or ETA is shown, because the current app does not upload responder GPS. External navigation is clearly a handoff to another app, not in-app turn-by-turn guidance.

### Dispatcher

**Queue:** Summary metrics, incident state, request details, source/accuracy of incident location, connectivity/freshness state, and available-unit choices. Assignment is disabled offline or when there is no available unit. Selecting an available unit opens a final confirmation. The demo never automatically assigns the incident.

**Assignment:** The confirmation identifies the incident and the selected unit. It warns the operator to check availability and suitability. On success, mark the incident assigned and update the shared responder view. If offline, blocked, or stale, do not show assignment success.

**Field units:** Show duty state independently from GPS state. “Available” must not imply a live position. Show that the live-unit feed is absent; use an explicit no-GPS label rather than a blank marker or fabricated point.

**Dispatcher map:** Show sample incident locations only. Clearly label schematic map and missing live responder coordinates. Unit GPS must be shown only if the server provides a timestamped position; stale/offline positions need an age label and warning. Do not infer ETA or proximity until routing/location data are integrated and its accuracy is validated.

**Activity:** Show a chronological demo event list. A production audit trail must be server-generated and resistant to client alteration. Distinguish local queued events from confirmed server events.

## GPS, permission, connectivity, and error-state coverage

The studio can simulate the following conditions:

- **Permission not granted:** Explain why location is needed and give a non-coercive “Not now” path. In this HTML preview the control only changes simulated state; Android runtime permission must be implemented in the mobile app.
- **Low accuracy:** Show the estimated uncertainty (sample ±180 m), suggest retrying in a safer/open place, and require an explicit acknowledgement before the requester chooses to proceed with the approximate fix.
- **Stale fix:** Block using the old point as current. Offer a retry rather than silently reusing it.
- **GPS unavailable:** Explain the limitation and retry. Do not silently send guessed coordinates.
- **Weak or absent network:** Show whether an action is unsent, locally queued, or server-confirmed. Never present a queued SOS, cancellation, assignment, or status update as delivered.
- **Duplicate SOS:** Block a second active or unsent requester report. Let the user inspect the current report instead.
- **No available unit:** Keep the pending incident visible and show that assignment cannot proceed; do not imply successful dispatch.
- **Cannot safely respond:** Tell the responder to contact dispatch through TACRU's approved channel; do not silently resolve or reassign.
- **Sign out:** Confirm the local demo transition. Production must preserve active incident visibility and handle session expiry without losing incident context.

The on-screen “Retry GPS” and scenario controls are illustrative. Actual permission denial, GPS provider errors, stale-coordinate thresholds, network changes, and offline persistence need Android/backend tests on devices.

## Test-driving the prototype

Open [`gallery.html`](gallery.html). In the left control panel, choose a role and optionally change the network or GPS condition. The same sample incident is shared across role views.

A primary walkthrough:

1. As Requester, choose an incident type and review the location disclosure. Check the acknowledgement, then confirm the SOS.
2. Tap the centered **GPS Map** destination. Confirm the screen identifies the pin as an initial sample fix, not a live position.
3. Switch to Dispatcher, review the queue, assign the incident to the available sample unit, and confirm.
4. Switch to Responder / Driver, review the assignment and advance each status through confirmation.
5. Return as Requester to see status changes; return as Dispatcher to inspect units/activity.
6. Reset the demo from the right-side panel to restart.

Try failure paths by changing Network to Offline and GPS condition to Permission not granted, Low accuracy, Stale, or GPS unavailable before sending or updating. The browser preview does not exercise a real device, backend, or real emergency workflow.

## Remaining work before production or a formal field evaluation

This design is intentionally not a “full-proof” or operational release. The following remain beyond wireframes: buildable Android project integration, real map provider and attribution, live requester/responder location policy and collection service, real authorization/consent storage, actual dispatch APIs and push delivery, offline database/sync, server-side role checks and audit records, approved emergency/support contacts, privacy policy and data-retention process, abuse/duplicate safeguards, operational procedures, accessibility testing, device/GPS/network testing, security review, and evaluation with TACRU and representative requesters. Define acceptance criteria and scope with TACRU before treating any simulated UI state as an operational capability.
