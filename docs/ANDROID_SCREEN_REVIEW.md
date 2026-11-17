# Android screen review

Last updated: 2026-11-17

This register tracks visible UI findings from source review. A change is not considered device-verified until it has been exercised on supported Android layouts.

## Implemented on PR #334

### Requester
- When live request refresh failed, the UI could still render “No requests yet” because the failed state intentionally clears live incidents and exposes cached statuses separately. The empty state and section heading now appear only after a successful live status check (or during initial loading); cached/unknown states no longer masquerade as a confirmed empty list.

### Responder and dispatcher
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

## Follow-up review required
- Implement password recovery through a real Supabase Auth recovery endpoint, including redirect/deep-link handling and a confirmation/error state, before restoring an email-send action.
- Implement a real account-deletion request workflow only after the authorized operator, request destination, identity verification, audit requirements, retention exceptions, and server-side state transition are defined. Do not restore a success state without a confirmed backend response.
- Add step-up verification only when a real server-enforced challenge flow exists; a local preference is not a security control.
- Review privacy-notice statements about controller identity, retention, lawful basis, encryption, and contact channels with the responsible organization before treating the notice as legally approved.
- Verify Profile, chat, and notification layouts on compact phones, landscape, split-screen, and expanded windows.
- Manually validate chat participant visibility, message sending, image upload/download failure states, notification deep links, and account sign-out behavior.

## Validation boundary
- Changes are source-reviewed only. No Gradle build, unit tests, emulator, physical-device, backend, or legal review was performed for this screen pass.
