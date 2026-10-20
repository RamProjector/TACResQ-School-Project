# Current task handoff

**Last updated:** 2026-10-20
**Repository:** `RamProjector/TACResQ-School-Project`
**Base branch:** `main` at `320ddec6db46f7909c6ddf4c1493f4915f212702` when PR #22 was opened
**Active branch:** `agent/android-gradle-wrapper`
**Pull request:** [#22 — build(android): add Gradle project and wrapper](https://github.com/RamProjector/TACResQ-School-Project/pull/22), open and unmerged
**Previous workflow PR:** [#21 — review-first autonomous coding workflow](https://github.com/RamProjector/TACResQ-School-Project/pull/21), separate and still unmerged at last check

## Current objective and status

Make TACResQ a complete, buildable Android Gradle project, validate debug/release APK builds in CI, and diagnose the earlier Docker integration error. The Android Gradle and CI work is now validated by a successful GitHub Actions run. Keep PR #22 open for user review; do not merge or deploy.

## Completed in this task

- Added root `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, and `app/build.gradle.kts` for the Kotlin/Jetpack Compose Android app.
- Added `app/src/main/AndroidManifest.xml` with the application, launcher activity, internet permission, and the location-button-related permissions used by the existing flow. Preserved `app/src/debug/AndroidManifest.xml` for debug-only cleartext traffic.
- Added Gradle 9.3.1 wrapper scripts and JAR, checksum-pinned distribution URL, and wrapper-JAR checksum record. Gradle Actions confirmed the wrapper JAR is valid and `tools/check_android_source.py` verifies its SHA-256.
- Configured JDK 17, Android Gradle Plugin 9.1.1, Kotlin/Compose built-in Kotlin support, compile SDK API 37.0, minimum SDK 26, and CI SDK Build-Tools 36.0.0.
- Updated `.github/workflows/ci.yml` to validate the wrapper, install the Android SDK package named `platforms;android-37.0`, then actually run `./gradlew --no-daemon assembleDebug` and `assembleRelease` when the wrapper exists.
- Updated `tools/check_android_source.py`, `README.md`, `docs/QA_WORKFLOWS.md`, `PROJECT_ANALYSIS.md`, and `docs/SUPABASE_BACKEND.md` to describe the complete Gradle project and current validated source state.
- Fixed actual Kotlin errors uncovered after the first APK compilation: `FakeTacApi.recordLocation` now returns its synchronized result; `TacRepository.assignIncident` supplies the incident ID and request body; `AppRoot.kt` imports Compose `setValue`; and responder location timestamp rendering snapshots the delegated timestamp before null checking.
- Opened PR #22 and updated its description with final validation results. Latest fully successful run: [39440473771](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39440473771) on commit `5ed7297b2c9f185f7948d6e26d7ffa3b937bb18e`.

## CI validation

Run `39440473771` completed successfully:

- Android Gradle wrapper/checksum validation: passed.
- Android SDK API 37.0 and Build-Tools 36.0.0 provisioning: passed.
- `assembleDebug`: passed.
- `assembleRelease`: passed.
- Source/workflow/stress/location-contract checks and dashboard production build: passed.
- Prototype navigation and visual regression: passed.
- Laravel/Postgres and dashboard integration (Compose build/start, readiness, feature tests, static assets, runtime config): passed.

The earlier Laravel/Postgres integration job failed because Docker Hub returned HTTP 502 while retrieving the `node:26-alpine` image metadata. Rerunning the failed job in [run 39431224588](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39431224588), attempt 2, passed. No Dockerfile change was justified by that non-reproducible external registry error.

Several intermediate Android setup/build attempts failed while surfacing real setup mistakes and pre-existing compile errors; these were corrected before the successful run. Do not report any intermediate failed run as the final result.

## TACResQ architecture and safety context

- `app/`: Kotlin/Jetpack Compose Android app for requester, responder/driver, dispatcher, authentication, GPS Map, API clients, repository, and location provider.
- `supabase/migrations/`: schema, RLS policies, authorization RPCs, incident location and responder-location contracts.
- `backend/src/`: Laravel API adapter and integration-test target.
- `backend/web/`: React/TypeScript TACRU dispatcher dashboard served by Laravel at `/dispatch/`.
- `tools/`, `tests/`, `.github/workflows/`: source contract checks, workflow tests, browser/visual regression, security/stress review, and CI.

TACResQ is an educational prototype, not an emergency-services provider. Requester GPS is one-time and user-initiated. Responder sharing in source is consent-gated, approximate, incident-bound, and foreground-only. Background tracking, embedded live fleet maps, FCM push, and offline write synchronization are not implemented. Successful APK builds do not establish correct behavior on physical devices.

The responder-location migration `202610200002_responder_location_tracking.sql` was previously applied with explicit approval to the TACResQ Supabase project as migration version `20261020062532`. Do not reapply it to that project.

## Side effects and limitations

- No Render services, environment variables, deploys, or service settings were changed in this task.
- No Supabase settings, live data, or database migrations were changed.
- No deployment was triggered.
- This environment had no accessible local Android SDK/Gradle checkout; all successful Gradle build validation was performed by GitHub Actions.
- `main` was previously observed as unprotected. This PR remains review-first by process, but GitHub server-side branch protection is a separate repository-admin setting.

## Next actions

1. Review PR #22 and leave it open for approval; do not merge without the user's separate authorization.
2. Confirm CI on any later handoff/documentation-only commit if one is pushed.
3. Next engineering slice, as a separate PR: validate sign-in and location permission flows on emulator/physical devices, including permission denied/revoked, location services disabled, foreground stop/resume, incident reassignment, sign-out, and no upload outside the permitted en-route state.
4. Do not change Render or Supabase live resources unless a future task identifies a concrete issue and explicitly authorizes the relevant operation.
