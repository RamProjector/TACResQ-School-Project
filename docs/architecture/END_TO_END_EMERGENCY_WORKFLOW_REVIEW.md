# End-to-end emergency workflow review

**Review date:** 2026-11-22  
**Branch:** `admin/remove-unimplemented-pages`  
**Scope:** Requester SOS submission through Android dispatch, field-unit assignment, GPS sharing, route guidance, closure, and notifications.  
**Method:** Read-only source inspection of the Android requester, dispatcher and responder ViewModels, repository/API transport, routing foundation, and notification/FCM documentation. No tests, builds, live calls, or production changes were performed.

## 1. Requester submits an SOS

**Source evidence**
- `RequesterViewModel.sendSos` blocks submission until current incident status is verified and any previous unresolved SOS submission is resolved.
- It rejects a second active incident, stale location fixes, and requests without either a location pin or landmark/access detail.
- It claims the sending state synchronously to close the double-tap window.
- `TacRepository.createIncident` persists an owner-bound pending request envelope and reuses its request UUID and exact payload for retry.
- The envelope is cleared only after confirmed server success. An ambiguous failure remains available for explicit retry; a replacement SOS is not silently created.
- A successful response is checked against the location pin and notes the requester approved. A mismatch warns not to resend and to contact dispatch.

**Assessment:** Strong source-level safeguards against duplicate SOS creation and accidental location substitution. Device-level persistence, process-death recovery, and real network ambiguity still require Android integration testing.

## 2. Dispatcher receives and coordinates

**Source evidence**
- `DispatcherViewModel.refresh` loads incidents, field-unit profiles, and active responder locations.
- Overlapping refreshes are coalesced into one follow-up refresh.
- Assignment is blocked unless incident and unit data are fresh; team size is constrained to 1–5 and duplicate rapid submissions are blocked.
- Assignment, release, and progress changes use repository RPCs; failures mark assignment data stale and require refresh before retry.
- The Android dispatcher screen owns these operations. The web portal remains Admin-only; no dispatcher web dashboard should be restored.

**Assessment:** Assignment operations are guarded against stale incident/unit lists and duplicate taps. The queue still uses separate sequential reads rather than one atomic queue snapshot. Server-side assignment RPC locking remains the final authority, so a stale screen cannot guarantee a successful assignment. The UI must continue to present server rejection clearly and refresh after conflicts.

## 3. Responder/driver accepts work and shares location

**Source evidence**
- The responder refresh verifies the current profile and own incidents before presenting claimable incidents.
- Claimable incidents are shown only when the profile is verified and duty status is available; query failures are not rendered as an empty successful queue.
- Claim is performed through a server RPC and followed by refresh.
- Starting GPS sharing re-reads the responder's incidents and identity, verifies the user's own assignment is `en_route`, and checks approximate-location permission.
- The foreground tracking service periodically rechecks identity and assignment; it stops when the assignment is no longer en-route or the session is no longer authorized.

**Assessment:** The system distinguishes aggregate incident status from each field unit's own assignment status, which is essential for teams. GPS sharing is explicitly user-started and must not be described as always-on tracking. Physical-device behavior across process death, OEM battery controls, permission revocation, and poor connectivity remains unverified.

## 4. Navigation and road guidance

**Source evidence**
- MapLibre renders the map and incident/device-location markers.
- Route preview is an optional static driving preview to the user's own assigned incident.
- The Android route-preview feature flag defaults to disabled.
- The backend derives the destination from caller-authorized incident data and limits the caller to their own assignment.
- The preview is not turn-by-turn navigation, live rerouting, traffic-aware guidance, road-closure/flood awareness, or proof that a road is passable.
- The explicit external Maps fallback remains available.

**Assessment:** Do not represent TACResQ as providing operational emergency-vehicle navigation. The current route preview is optional and disabled by default. The app must retain a clear fallback and must not imply that a static route is a safe or guaranteed route.

## 5. Progress and incident closure

**Source evidence**
- Dispatcher advances the shared incident through the authorized status progression.
- Field-unit own assignment statuses are separate from the shared incident status.
- The database lifecycle review documents assignment/release events and incident status history separately.
- Resolved/cancelled records are retained as closed history and must not re-enter the active pending queue.

**Assessment:** The status and assignment model is explicit, but an end-to-end integration test should verify that every member's own status, aggregate incident status, location service, and duty availability converge after every terminal transition. Source inspection is not a substitute for a transaction-level concurrency test.

## 6. Requester and team notifications

**Source evidence**
- The authenticated in-app inbox is the authoritative notification surface.
- Realtime events are refresh hints; Android reloads through caller-scoped inbox APIs and has a periodic fallback.
- FCM registration and server delivery queue code exist.
- The documented Render web-service startup does not run the delivery command continuously. The documented free-compatible Supabase Cron + pg_net trigger is not enabled in the connected project.
- A legacy `tacresq-notify` Edge Function has been identified as a separate sender path that bypasses the canonical delivery ledger; external invocation logs must be checked before retirement.

**Assessment:** Do not promise timely push delivery. Until the trigger is safely activated and verified with test devices, users may depend on opening the app/inbox and refreshing. Do not activate both sender paths for the same event family.

## Release-blocking end-to-end checks

These checks must be performed in a dedicated test environment with synthetic incidents and test accounts:

1. Submit an SOS, simulate a timeout after server commit, retry the exact saved request, and verify only one incident exists.
2. Confirm requester location and landmark details match exactly what dispatch sees; test manual pin, GPS fix, and no-pin landmark paths.
3. Have two dispatchers race to assign the same incident and verify the database permits only one valid team assignment.
4. Have multiple responders claim concurrently and verify team capacity and duplicate membership rules.
5. Advance shared incident status while team members have different individual assignment statuses; verify each responder's GPS sharing stops only when their own status ceases to be en-route.
6. Resolve and cancel incidents; verify assignment membership, unit availability, duty status, closed history, and requester status all converge.
7. Disconnect Realtime and FCM; verify the inbox fallback and clear stale-state messaging.
8. Test image upload/download with authorized team members and unrelated accounts; verify no cross-incident object access.
9. On physical Android devices, test location permission changes, notification permission denial, process death, battery restrictions, and app relaunch.
10. Verify routing-disabled behavior and external Maps fallback; if static preview is enabled in a test build, test invalid/stale route responses and ensure the UI never describes it as turn-by-turn navigation.

## Priority decisions

- **P0:** Do not claim production-grade push delivery until the free-compatible trigger and device receipt are verified.
- **P0:** Do not claim emergency navigation; static route preview is optional, disabled by default, and lacks live road-condition awareness.
- **P1:** Add/maintain deterministic end-to-end tests for SOS idempotency, concurrent assignment, terminal-state convergence, and per-member tracking stop.
- **P1:** Preserve explicit stale-data and retry states across all three operational Android roles.
- **P1:** Verify chat attachment membership and notification delivery in the same synthetic incident lifecycle.
- **P2:** Conduct physical-device usability/accessibility testing after backend and workflow invariants are validated.

## Boundaries

No dispatcher web UI was added. No paid service was introduced. No secrets, Render configuration, Supabase settings, Firebase credentials, production records, or live migrations were changed. No CI, test, build, or device validation was run during this review.
