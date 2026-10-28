# TACResQ Feature Gap Priorities

**Updated:** 2026-10-28  
**Purpose:** Prioritize features by emergency-service safety and operational value, not by how impressive they look in a demo.  
**Evidence boundary:** This list is based on the current Android client structure, existing repository/API methods, role model, map workflow, migrations, and PR #185. Items marked "verify" are not claimed to work end-to-end until the real Supabase API and multiple devices have been tested.

## Priority 0 — Prove the emergency loop works safely

These are release blockers, not optional polish.

### 0.1 Real-backend end-to-end verification
- Verify real Supabase login/session restoration and logout, with role authorization enforced server-side.
- Verify a requester can submit one emergency, see it after refresh/restart, edit/cancel only when allowed, and never accidentally create a duplicate after an ambiguous network failure.
- Verify dispatch can assign eligible units and the requester/responder/driver devices observe the same server state.
- Verify each assigned unit can advance only its own allowed status transitions; client-side button disabling is not authorization.
- Verify errors, timeouts, token expiry, and offline reconnects never present stale data as confirmed live data.
- Acceptance: test accounts on at least two independent devices/emulators; persisted state survives app restart; unauthorized RPC/RLS calls are rejected; record results without exposing real personal or emergency data.

### 0.2 Current GPS truth and location-sharing lifecycle
- Verify the map renders with real OpenStreetMap tiles and visible attribution, including the no-incident/no-unit state.
- Distinguish permission denied, device location disabled, no fix, stale fix, network failure, and a successful recent location.
- Show timestamps/freshness for responder/unit markers and never represent stale coordinates as live.
- Confirm location sharing starts only after clear user intent and a valid authorized assignment, and stops when the assignment/lifecycle rules require it.
- Acceptance: test approximate permission, revoked permission, location services off, no network, stale fixes, app background/foreground, logout, and invalid/missing incident coordinates on a device/emulator.

### 0.3 CI and release safety
- Make every pull request run Python source-contract tests plus Android debug compilation and the relevant unit/instrumentation tests.
- Confirm current-head checks actually ran; a missing workflow result is not a pass.
- Keep release signing secrets out of the repository and never claim a signed release build was verified if signing was skipped.

## Priority 1 — Role-specific task workflows

### 1.1 Driver workflow (in progress)
- Driver workspace uses trip-oriented labels and route guidance, retains the shared assignment status safeguards, and warns against interacting with the app while driving.
- GPS Map remains a top-level destination.
- Do not invent vehicle telemetry, automatic dispatch acceptance, route ETA, vehicle inventory, or driver-specific status permissions unless the backend supports and authorizes them.
- Next: confirm the real operating policy with the TACRU workflow, then implement only actions supported by the backend; keep high-attention tasks out of the driving flow.

### 1.2 Responder workflow
- Show the responder's own per-assignment status, not only the incident aggregate.
- Clearly separate current assignments, history, location sharing, and recovery actions.
- Keep duty changes and status transitions unavailable while assignment state is unverified.

### 1.3 Dispatcher workflow
- Distinguish active queue, available units, assigned teams, stale/missing unit locations, and failed refreshes.
- Confirm the target incident and team before assigning; prevent double assignment and stale-state actions server-side.
- Make per-unit progress visible when an incident has multiple responders.

### 1.4 Requester workflow
- Keep SOS entry easy to find without accidentally resubmitting.
- Show active request status, confirmed location/source/time, safe correction flow, and clear cancellation consequences.
- Preserve an unresolved submission's idempotency key across ambiguous network failures; don't encourage a replacement request until the prior one is resolved.

## Priority 2 — Complete GPS as a real operational feature
- Keep the map central and easy to reach, with a prominent recenter/location action and stable role-aware markers.
- Make map states understandable even when there are no incidents or units; an empty dataset must not hide the map.
- Provide a safe fallback when coordinates are absent or invalid: show landmark/instructions and tell users to confirm with dispatch instead of navigating to guessed coordinates.
- Validate marker refresh, stale data, map tile/network errors, attribution, accessibility labels, and lifecycle polling.
- Avoid adding route optimization or live traffic until data sources, licensing, and operational need are established.

## Priority 3 — Privacy, security, and accountability
- Verify Android session token storage encryption with Android Keystore and safe handling on logout/token expiry.
- Review Supabase RLS, grants, RPC security mode, role checks, assignment ownership, and migrations against hostile client requests.
- Add a documented location-data notice: purpose, viewers, foreground/background behavior, retention, contact, and data-subject request path.
- Define minimal retention for emergency requests, locations, and audit events with TACRU/privacy review before changing production data.
- Add server-side audit events for privileged dispatch actions and status changes if current backend logs are not sufficient; do not treat client logs as an authoritative audit trail.

## Priority 4 — Reliability features after the core loop is proven
- Push notifications for assignment changes and requester status updates, with in-app refresh as the source of truth.
- Reconnect-aware refresh and a visible stale/offline indicator; never queue safety-critical status changes silently.
- A debug-only, user-accessible diagnostic export for logs and environment/build metadata, redacting tokens, phone numbers, exact coordinates, and emergency descriptions by default.
- Accessibility pass for 48 dp targets, TalkBack semantics, readable contrast/type, and predictable navigation on small screens.

## Deliberately not prioritized yet
- AI dispatch, automatic donor/driver matching, route optimization, analytics dashboards, gamification, and elaborate animations.
- Vehicle telemetry or fleet maintenance modules without a confirmed TACRU operational requirement and real data model.
- A local mock backend as evidence that the real emergency service works.

## Current implementation note
PR #185 contains responder/requester/dispatcher workflow safeguards and the first role-aware driver presentation changes. The driver view now uses trip-oriented labels and route-safety guidance but still shares the responder ViewModel/API. The current PR head has not returned GitHub Actions runs or commit status checks, so Android compilation and regression checks remain unverified. Do not merge on the basis of static source edits alone.
