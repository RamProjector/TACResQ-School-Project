# Android screen review

Last updated: 2026-11-17

This register tracks visible UI findings from source review. A change is not considered device-verified until it has been exercised on supported Android layouts.

## Implemented on PR #334

### Requester
- The adaptive request/response history pane previously treated an empty list as “Nothing here yet” even when the ViewModel had retained no live records because refresh failed, or when only cached requester statuses were available. It now distinguishes loading, confirmed empty, and stale/unavailable states, warns in both list and detail panes when the last-loaded snapshot is not current, and provides an explicit refresh action from either pane.
- When live request refresh failed, the UI could still render “No requests yet” because the failed state intentionally clears live incidents and exposes cached statuses separately. The empty state and section heading now appear only after a successful live status check (or during initial loading); cached/unknown states no longer masquerade as a confirmed empty list.

- Dispatcher summary metrics previously labelled the incident total only as “Total”, the available-unit count only as “Available”, and the panel “Live overview” even when the retained snapshot could be stale. Renamed these to “Incidents”, “Ready units”, and “Dispatch overview” so each count has a clear subject and the heading does not imply guaranteed live freshness.

- Pending dispatcher cards presented “Select response team” and the claimability toggle as two equally prominent outlined buttons, competing for attention. Kept team selection as the primary outlined action and demoted the reversible field-claiming toggle to a full-width text action; both remain gated by the same busy/freshness checks.

- Pending-queue waiting age was colored as an error after an undocumented 15-minute threshold. Removed that arbitrary escalation cue; elapsed time remains visible and the queue remains oldest-first. A red urgency threshold should be introduced only when an approved dispatch/SLA policy defines it.

### Responder active responses
- The same routing-provider and fallback explanation appeared inside every active incident card with valid coordinates. Consolidated it into one note beneath the active-response heading whenever at least one active incident has a valid pin; individual cards retain their route-preview action and missing-pin warning.

### Requester emergency form
- Emergency-type options already communicate selection through the radio control and selected-card styling. Removed the additional “Selected” text label repeated on the active option; the selectable row continues to expose its selected state to accessibility services.

### Responder and dispatcher
- The adaptive history pane now distinguishes initial loading from a confirmed empty list and marks retained last-loaded records as potentially stale when refresh fails.
- Shared team-status guidance was repeated inside each active responder/dispatcher incident card. Moved it to the section heading.
- Arrival-versus-GPS-sharing guidance repeated in each en-route responder card. It now appears once when an en-route response exists.
- The no-available-unit message repeated on every pending dispatcher incident. It now appears once above the queue; assignment actions remain gated on available units.
- Dispatcher refresh failures retain the last loaded incident/unit snapshot. The warning now explicitly labels those lists as potentially out of date, and the empty active-queue message no longer claims there are currently no active incidents when sync has failed. The separate “Assignment paused” card duplicated the shared sync warning, so its operational consequence is now appended to the same sync panel; the existing Refresh action remains the single recovery affordance.

### Authentication
- The password-reset UI claimed a reset link had been sent, but the repository method only validated the email and returned success; no auth recovery request existed. Replaced the fake send flow with a clear unavailable-state message and removed the unused success-returning stub.

### Profile
- Name, email, and role were repeated in both the identity header and the account-details list. Removed the duplicate rows while retaining the optional mobile number.
- Step-Up Security / OTP was a local-only switch with no verification backend or security effect. Removed it rather than implying that sensitive operations were protected.
- Account deletion confirmation claimed a request was submitted, but its callback only changed a local message. Replaced the false success path with an explicit information dialog stating that in-app submission is not connected.
- Location-sharing copy incorrectly implied sharing only occurred while the app was open. Updated it to reflect the responder screen's documented background-sharing behavior for an active en-route response with sharing enabled.

### Messages and notifications
- The chat header repeated its incident context in both title and subtitle and consumed additional space with a persistent reminder card. Kept the incident identifier and replaced the subtitle with the access-scope reminder; removed the extra card.
- Notification header copy was redundant with the push-optional explanation in the status card. The header now names the content of the screen, leaving the practical push/inbox guidance in one place.

### Incident chat
- Incoming messages were merged into the conversation during polling, but the list only scrolled to the bottom once on initial load. It now follows newly received messages only when the reader is already near the bottom; reading older messages no longer gets interrupted by incoming updates.
- The composer is disabled while a saved pending message is being restored, preventing an editable-looking input during the integrity/recovery check.
- The character counter is hidden during ordinary typing and appears near the 2,000-character limit to reduce persistent composer clutter.

### Notifications
- A forced inbox refresh could supersede an earlier request, but the earlier request's success/failure callbacks did not check the refresh generation. If cancellation was not observed promptly by the underlying call, an older response could overwrite the newer inbox snapshot. Success, failure, and exception publication now require the current generation.
- The inbox already exposes a Refresh action in its status panel; the empty-error card also showed a second Try again button for the same operation. Removed the duplicate action so there is one consistent refresh affordance.

## Follow-up review required
- Implement password recovery through a real Supabase Auth recovery endpoint, including redirect/deep-link handling and a confirmation/error state, before restoring an email-send action.
- Implement a real account-deletion request workflow only after the authorized operator, request destination, identity verification, audit requirements, retention exceptions, and server-side state transition are defined. Do not restore a success state without a confirmed backend response.
- Add step-up verification only when a real server-enforced challenge flow exists; a local preference is not a security control.
- Review privacy-notice statements about controller identity, retention, lawful basis, encryption, and contact channels with the responsible organization before treating the notice as legally approved.
- Verify Profile, chat, and notification layouts on compact phones, landscape, split-screen, and expanded windows.
- Manually validate chat participant visibility, message sending, image upload/download failure states, notification deep links, and account sign-out behavior.

## Validation boundary
- Changes are source-reviewed only. No Gradle build, unit tests, emulator, physical-device, backend, or legal review was performed for this screen pass.


## Dispatcher field-unit scan order — 2026-11-17

- **Finding:** The field-unit section was labelled “Current availability” but rendered units in repository/API order. Dispatchers had to scan the entire list to find available responders/drivers, even though available units are the immediately actionable group.
- **Change:** Stable-sort the displayed field-unit list so units with `AVAILABLE` duty status appear first. All other units remain visible in their existing relative order; unknown/off-duty/assigned/in-progress states are not collapsed or reclassified.
- **Safety boundary:** This is presentation-only. The assignment dialog and its fresh-data guard continue to derive eligibility from the existing `availableUnits` filter; the display sort does not grant eligibility or alter backend ordering/state.
- **Validation:** No build, tests, emulator, or device checks run.


## Responder requester-contact hierarchy — 2026-11-17

- **Finding:** Active-response cards concatenated the requester's name and phone number into one comma-separated string prefixed by “Requester:”. This made two different pieces of operational contact information harder to scan and gave no clear field hierarchy.
- **Change:** Present a “Requester contact” label, then the available name and phone number on separate lines. Missing name/phone values are omitted independently; the remaining contact detail stays visible.
- **Privacy/operations boundary:** Did not hide or remove the phone number. It remains directly available to the assigned responder because contact may be operationally necessary. No dialer, clipboard, authorization, data-fetch, or backend behavior was added or changed. Existing assigned-incident scope remains the access boundary.
- **Validation:** No build, tests, emulator, or device checks run.


## Responder duty-status controls — 2026-11-17

- **Finding:** The selected duty-status FilterChip remained enabled. Tapping the already-selected “Available” or “Off duty” option could submit the same status again, creating an unnecessary repository request and refresh with no visible state change.
- **Change:** Disable each chip when it already represents the current status, while retaining the existing busy guard. The alternative status remains selectable only when the screen's existing assignment/freshness checks allow duty changes.
- **Safety:** No duty-state rules, active-assignment restrictions, backend behavior, or status mapping changed. The UI only avoids submitting a no-op selection.
- **Validation:** Source review only. No build, tests, emulator, or device checks run.


## Responder location-sharing status clarity — 2026-11-17

- **Finding:** The tracking controller publishes informational messages as well as failures (including “Starting location sharing…” and the normal “Sharing approximate location…” message). The screen treated every non-null message as “Needs attention”, even while a session was starting successfully. It also applied one tracking message to every en-route incident card, including cards unrelated to the active sharing session.
- **Change:** Track the controller's explicit starting state, show “Starting” until the first confirmed location send, reserve “Needs attention” for messages after a confirmed send, and scope the card's message/attention styling to the incident whose ID is actually being tracked. Direct start/resume verification failures are also surfaced through the existing screen error banner so they are not silently lost when no session is active.
- **Safety:** No location collection, permission, service, assignment verification, or sharing lifecycle behavior changed. The change only corrects status presentation and surfaces existing failure results.
- **Validation:** Source review only. No build, tests, emulator, or device checks run.


## Dispatcher active-team progress density — 2026-11-17

- **Finding:** The active-team summary showed both “N of M responders have started their response” and “K still marked Assigned”. The second line repeated the exact complement of the first, while “started” was less precise than the actual rule being counted (any individual status other than ASSIGNED).
- **Change:** Use one concise, source-accurate line: “N of M responders moved beyond Assigned”. Remove the redundant complement line. Expanded member details and individual status pills remain available.
- **Safety:** Counts still derive from the same assigned-user status list and the same status-not-ASSIGNED predicate. No team assignment, aggregate incident status, status transition, or backend behavior changed.
- **Validation:** Source review only. No build, tests, emulator, or device checks run.
