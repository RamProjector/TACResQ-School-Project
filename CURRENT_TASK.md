# Current task handoff

**Last updated:** 2026-10-20
**Repository:** `RamProjector/TACResQ-School-Project`
**Branch:** `main`
**Phase 2 implementation commit:** `2d72eca` (pushed to `origin/main`); this handoff-only update is the subsequent branch tip.
**Working tree:** clean and synchronized with `origin/main` after this handoff update is pushed

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
- Added and, after explicit user approval, applied `supabase/migrations/202610200002_responder_location_tracking.sql` to Supabase project `TACResQ-School-Project` (`kttguamhznoflzagfutr`, ap-southeast-1) as migration version `20261020062532` / `responder_location_tracking`.
- Added the typed Android `recordResponderLocation` repository/API boundary for the incident-bound RPC, with fake and Supabase adapters. The foreground responder flow now calls it after explicit consent and a successful `en_route` transition.
- Tightened the Phase 2 migration so location writes go through the validating RPC, and direct row reads are limited to assigned active en-route incidents. Added a dispatcher-only RPC returning the latest valid location for each active en-route incident.
- Added dispatcher-dashboard location freshness labels (fresh under 30 seconds, aging through 120 seconds, stale thereafter), last-reported coordinate links, explicit no-location/configuration states, and 8-second polling alongside incident/unit refresh. The incident queue continues to work if the location RPC is unavailable in an environment not yet migrated.
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
- The `api_record_location_v2` write contract and dispatcher-only `api_active_responder_locations` read contract are applied to `TACResQ-School-Project` as migration version `20261020062532`. No other Supabase project was changed. Dashboard display and Android upload still require location writes from a validated client.
- The dashboard can display freshness and last-reported coordinates when authorized clients write; it is not an embedded map and does not prove Android collection/delivery. No location was uploaded as part of the migration.
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

All listed offline checks passed for this work session, including the dashboard TypeScript/production build and 12 location-contract assertions (including Android consent/lifecycle source assertions). Dashboard dependencies were installed with `pnpm install --frozen-lockfile`. Android was checked only through source-pack and static contract checks; no Gradle project/wrapper, Kotlin compiler, or Android SDK is available here, so compilation and device validation remain required. Supabase migration `20261020062532` is recorded as applied to the explicitly approved project. Read-only Postgres verification confirmed the new column and RPCs, `SECURITY DEFINER` on the write RPC, authenticated/anon RPC grants, denied direct authenticated inserts, enabled RLS, installed policies, dispatcher guard, en-route guard, advisory-lock throttle, and revoked authenticated access to the legacy write RPC. No role-based SQL integration tests or device tests were run, and the migration did not upload location rows. The feature implementation commit `2d72eca` is pushed to `origin/main`; this handoff-only update is being committed and pushed now, leaving the branch clean and synchronized.

## Resume checklist

1. Read `AGENTS.md` and this file.
2. Run `git status --short --branch` and `git log -5 --oneline --decorate`.
3. Fetch and inspect the latest `main` and pull requests before editing.
4. Confirm whether the user wants Android source, wireframes, backend, Supabase, dashboard, or public-repository work.
5. Build the Android project with the documented dependency set and validate the Kotlin/Compose implementation, then test on devices with approximate permission granted/denied/revoked, services disabled, app background/reopen, and movement/no-movement. The checked-in repository remains a source pack without a Gradle wrapper or full project.
6. Add executable Postgres tests in an isolated test database for unauthorized roles, wrong incident/status, invalid/future/old coordinates and timestamps, direct insert denial, and dispatcher-only reads. The approved migration is already applied to `TACResQ-School-Project`; do not reapply it there.
7. Exercise end-to-end delivery against two test accounts/devices and verify stop-on-arrival, reassignment, sign-out, stale state, offline failure/no replay, and dispatcher-only visibility. Do not describe tracking as operational before these checks.

## Next recommended work

- Keep the README and public community files synchronized with the actual implementation.
- Add a real in-app map provider only after selecting and documenting the provider/API-key boundary.
- Finish Android build/device validation; implement no background continuation without an explicit future product/privacy review.
- For any future migration changes, use an isolated test/staging Supabase project and role-based tests first; the present migration was applied only to the explicitly approved `TACResQ-School-Project` project.
- Add a Gradle wrapper and complete Android build project when the Android project is ready to be checked in.
