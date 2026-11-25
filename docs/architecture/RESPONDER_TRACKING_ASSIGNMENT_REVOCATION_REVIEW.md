# Responder assignment changes while location sharing is active

## Scope

This review follows the Android responder/driver location foreground service through dispatcher team-member release, per-responder assignment state, and the server-side location-write RPC. It is a source review of the PR branch, not a live-device or production test.

## Confirmed enforcement layers

### 1. Dispatcher release is constrained by the database

The dispatcher release RPC in `supabase/migrations/20261122050837_dispatcher_release_incident_assignment.sql`:

- Requires an authenticated profile with the dispatcher role.
- Locks the incident row before changing membership.
- Allows release only while the incident is `assigned` or `en_route`; release is rejected once the incident reaches the scene.
- Requires the selected user to be a current team member.
- Prevents release of the last remaining team member.
- Deletes the membership row and records the release through the assignment-event trigger.
- Reassigns the legacy primary-member pointer when the released member was primary.
- Returns the released member to `available` only if no other active assignment or legacy reservation still reserves them.

This means a dispatcher cannot silently remove a team member after arrival through this RPC, and cannot leave the incident with no assigned field unit through this path.

### 2. The Android service independently monitors membership

In `app/src/main/java/com/tacresq/app/location/ResponderLocationTrackingService.kt`:

- Starting a tracking session launches both the location-tracking coroutine and `monitorAssignment`.
- The monitor waits 30 seconds between checks, then reloads the authenticated user and their incident assignments.
- If the user's own assignment is no longer `en_route`, it stops the foreground service and clears the active sharing session.
- A 401/403 session failure stops sharing. A transient assignment-read failure displays a warning and retries; it does not treat an unverified response as proof that the assignment remains valid.
- The tracking coroutine also checks the current assignment before opening/reopening its location-update flow.

The screen-level `ResponderViewModel.refresh()` separately compares the active tracking incident with the current user's own assignment status and requests a local stop when that assignment is no longer en route.

### 3. The location-write RPC is the decisive authorization boundary

In `supabase/migrations/20261112215429_isolate_per_responder_location_writer.sql`, `private.tacresq_record_location_v2` derives the actor from `auth.uid()` and joins:

- the authenticated responder/driver profile,
- the incident row, and
- that same profile's row in `incident_assignments`.

It accepts a location write only when the incident status and that member's `responder_status` are both exactly `en_route`. It also validates coordinate ranges, accuracy, timestamp bounds, and an eight-second per-user write interval. Direct authenticated table INSERT is revoked so clients must use the validated RPC.

Consequently, even during the monitor's up-to-30-second interval, a released member's next location write is rejected by the database. The monitor is a local cleanup and user-feedback mechanism; it is not the authorization control.

## Remaining UX and operational considerations

- The foreground service may take up to one monitor interval to notice a membership change if it is otherwise healthy. During that interval, the backend still rejects writes after release.
- A transient read failure can delay the service's local stop message. This does not grant write access: the RPC checks current membership and status on every accepted point.
- The responder screen's displayed assignment and tracking state refresh asynchronously. A user may briefly see stale screen state after a dispatcher change; the screen must not present that state as proof that a new location was accepted.
- The service and backend use the individual assignment status, not only the incident's aggregate status. This is essential when multiple units have different assignment states.
- The current release policy deliberately prevents removing a member after the incident reaches `on_scene`. Any future handoff/replacement policy for on-scene or later work needs an explicit operational rule and a transaction-safe backend workflow; it should not be implemented as a UI-only override.

## Decision

No source change is justified by this review. The critical security property is already enforced server-side, and the service has a periodic cleanup monitor. Do not shorten the monitor interval or broaden release eligibility without measuring the request/load impact and defining the operational handoff policy.

## Recommended verification when testing is authorized

1. Start location sharing for a member whose own status is `en_route`.
2. Release that member from a multi-member team while the incident remains `en_route`.
3. Confirm the next location-write RPC is rejected and no new row is inserted for the released member.
4. Confirm the foreground service stops after its next successful assignment check and the UI reports that sharing stopped.
5. Simulate a temporary assignment-read failure and confirm the UI warns that status could not be refreshed while server-side writes remain authorization-checked.
6. Confirm a different remaining member can continue sending locations if their own assignment remains `en_route`.
7. Confirm release is rejected after the incident reaches `on_scene`, and release of the final member is rejected.

No tests, builds, migrations, or production operations were run as part of this review.
