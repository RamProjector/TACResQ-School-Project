# Android screen review

Last updated: 2026-11-17

This register tracks visible UI findings from source review. A change is not considered device-verified until it has been exercised on supported Android layouts.

## Implemented on PR #334

### Requester
- The adaptive request/response history pane previously treated an empty list as “Nothing here yet” even when the ViewModel had retained no live records because refresh failed, or when only cached requester statuses were available. It now distinguishes loading, confirmed empty, and stale/unavailable states, warns in both list and detail panes when the last-loaded snapshot is not current, and provides an explicit refresh action from either pane.
- When live request refresh failed, the UI could still render “No requests yet” because the failed state intentionally clears live incidents and exposes cached statuses separately. The empty state and section heading now appear only after a successful live status check (or during initial loading); cached/unknown states no longer masquerade as a confirmed empty list.

### Requester emergency form
- Emergency-type options already communicate selection through the radio control and selected-card styling. Removed the additional “Selected” text label repeated on the active option; the selectable row continues to expose its selected state to accessibility services.

### Responder and dispatcher
- The adaptive history pane now distinguishes initial loading from a confirmed empty list and marks retained last-loaded records as potentially stale when refresh fails.
- Shared team-status guidance was repeated inside each active responder/dispatcher incident card. Moved it to the section heading.
- Arrival-versus-GPS-sharing guidance repeated in each en-route responder card. It now appears once when an en-route response exists.
- The no-available-unit message repeated on every pending dispatcher incident. It now appears once above the queue; assignment actions remain gated on available units.
- Dispatcher refresh failures retain the last loaded incident/unit snapshot. The warning now explicitly labels those lists as potentially out of date, and the empty active-queue message no longer claims there are currently no active incidents when sync has failed.

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
