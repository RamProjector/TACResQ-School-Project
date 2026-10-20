# Current task handoff

**Last updated:** 2026-10-20
**Repository:** `RamProjector/TACResQ-School-Project`
**Branch:** `main`
**Phase 2 implementation commit:** `2d72eca` (pushed to `origin/main`); this handoff-only update is the subsequent branch tip.
**Working tree:** clean and synchronized with `origin/main` after the handoff update

## Objective

Maintain TACResQ as a clean, publicly presentable project with a role-based Android client source pack, Supabase-backed workflows, TACRU dispatcher dashboard, documented GPS boundaries, and resumable work across chats.

## Completed

- Added the public-project README and product positioning.
- Added the MIT license.
- Added `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, and `SUPPORT.md`.
- Added issue templates, issue configuration, and a pull-request template.
- Added public GitHub description and topics.
- Added centered, role-aware GPS Map navigation to the Android source pack.
- Added the full-screen confirmed-coordinate GPS Map destination.
- Added wireframe and screenshot documentation for requester, responder, and dispatcher flows.
- Removed stale merged branches and local generated build artifacts.
- Removed the misleading dispatcher self-location route planner; the web dashboard keeps incident-coordinate links, while responder navigation remains in the assigned responder client.
- Added an explicit responder confirmation before advancing an assigned incident to `en_route`; the confirmation states that live responder GPS is not shared by the current build.
- Added the dormant Supabase migration `202610200002_responder_location_tracking.sql` with incident-bound location records, assigned-unit/en-route authorization, coordinate/timestamp validation, and server-side update throttling. It is not applied to the shared database yet.
- Added the typed Android `recordResponderLocation` repository/API boundary for the incident-bound RPC, with fake and Supabase adapters. No watcher calls it yet, so the client still does not collect or upload live location.
- Tightened the Phase 2 migration so location writes go through the validating RPC, and direct row reads are limited to assigned active en-route incidents. Added a dispatcher-only RPC returning the latest valid location for each active en-route incident.
- Added dispatcher-dashboard location freshness labels (fresh under 30 seconds, aging through 120 seconds, stale thereafter), last-reported coordinate links, explicit no-location/configuration states, and 8-second polling alongside incident/unit refresh. The dashboard continues to work when the not-yet-applied RPC is unavailable.
- Added `tools/test_location_contract.py` source-level SQL/dashboard guardrails and wired them into CI.
- Implemented the Android responder-side foreground tracking lifecycle. The responder explicitly opts in at response start, can start without sharing, requests approximate permission only from that action, and begins location collection only after the `en_route` transition succeeds. The update request uses coarse granularity, balanced-power priority, a 12-second interval, and a 20-meter movement threshold; each accepted fix goes through `recordResponderLocation` / `api_record_location_v2`.
- Added visible last-send time, reported accuracy, and location/API error state, plus explicit stop and resume controls. The callback is unregistered when collection is cancelled; sharing state stops on app background, responder-screen disposal, permission denial, non-en-route/reassigned status observed by refresh, and incident progression. Resume after foreground return requires an explicit user action.
- Updated README and GPS privacy notes to distinguish requester one-time precise GPS from approximate foreground responder sharing and to state the Android source/device validation limits.
- Wrote `plan.md` with the agreed implementation plan: dispatcher-only responder tracking while en route, explicit responder start/stop lifecycle, incident-bound backend authorization, freshness states, realtime fallback, notifications, offline behavior, audit history, and deferred requester-facing GPS.

## Current implementation boundaries

- Requester GPS is one-time, user-initiated sharing.
- The GPS Map shows confirmed incident coordinates and timestamps only.
- Android foreground responder sharing is implemented in source but not built or device-validated; it requests approximate permission and runs only while the responder screen is foregrounded. No background tracking is implemented.
- In-app map tiles, live fleet markers, FCM push notifications, and offline write synchronization are not implemented.
- The web route planner does not write browser coordinates to Supabase or present them as responder/unit tracking; it is a local navigation handoff only. Google Maps selects the available route and traffic may change.
- The dispatcher dashboard does not request or track dispatcher browser location.
- Responder navigation opens the confirmed incident coordinate locally; foreground fixes now use the existing incident-bound repository/RPC boundary.
- The `api_record_location_v2` write contract and dispatcher-only `api_active_responder_locations` read contract are source-defined but not applied to any shared database. Dashboard display and Android upload require that migration to be applied to a test/staging database first.
- The dashboard can display freshness and last-reported coordinates after the migration is applied and clients begin writing; it is not an embedded map and does not prove Android collection/delivery.
- Android consent, coarse permission request, cadence/distance limits, foreground lifecycle stop/resume, status-change stop, and send/error feedback are source-implemented. Realtime remains polling-only. App/device compilation and real permission/lifecycle behavior are not verified because this source pack has no Gradle wrapper, Kotlin compiler, or Android SDK in the current environment.
- The repository is an Android source pack without a checked-in Gradle wrapper.

## Validation

The last validation passed:

```text
python3 tools/check_android_source.py
python3 tools/test_workflow.py
python3 tools/test_prototype.py
python3 tools/test_location_contract.py  # 12 source-contract assertions
pnpm --dir backend/web build
git diff --check
```

All listed offline checks passed for this work session, including the dashboard TypeScript/production build and 12 location-contract assertions (including Android consent/lifecycle source assertions). Dashboard dependencies were installed with `pnpm install --frozen-lockfile`. Android was checked only through source-pack and static contract checks; no Gradle project/wrapper, Kotlin compiler, or Android SDK is available here, so compilation and device validation remain required. The SQL migration was not executed against Postgres and no Supabase database was modified. Implementation commit `2d72eca` is pushed to `origin/main` per the user request. This follow-up handoff-only change records that result; no Supabase database was modified.

## Resume checklist

1. Read `AGENTS.md` and this file.
2. Run `git status --short --branch` and `git log -5 --oneline --decorate`.
3. Fetch and inspect the latest `main` and pull requests before editing.
4. Confirm whether the user wants Android source, wireframes, backend, Supabase, dashboard, or public-repository work.
5. Build the Android project with the documented dependency set and validate the Kotlin/Compose implementation, then test on devices with approximate permission granted/denied/revoked, services disabled, app background/reopen, and movement/no-movement. The checked-in repository remains a source pack without a Gradle wrapper or full project.
6. Add executable Postgres tests for unauthorized roles, wrong incident/status, invalid and future coordinates/times, direct insert denial, and dispatcher access after applying the migration to an isolated test database.
7. Exercise end-to-end delivery against two test accounts/devices and verify stop-on-arrival, reassignment, sign-out, stale state, offline failure/no replay, and dispatcher-only visibility. Do not describe tracking as operational before these checks.

## Next recommended work

- Keep the README and public community files synchronized with the actual implementation.
- Add a real in-app map provider only after selecting and documenting the provider/API-key boundary.
- Finish Android build/device validation; implement no background continuation without an explicit future product/privacy review.
- Apply the location migration only to an isolated test/staging Supabase project after SQL permission tests exist; never apply it to a shared project without explicit authorization.
- Add a Gradle wrapper and complete Android build project when the Android project is ready to be checked in.
