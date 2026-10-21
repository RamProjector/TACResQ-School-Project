# Current task handoff

**Last updated:** 2026-10-20
**Repository:** `RamProjector/TACResQ-School-Project`
**Base branch:** `main` at `320ddec6db46f7909c6ddf4c1493f4915f212702` (verified before this task)
**Active task branch:** `agent/setup-coding-agent-workflow`
**Pull request:** [#21 — docs: establish review-first autonomous coding workflow](https://github.com/RamProjector/TACResQ-School-Project/pull/21), open and unmerged
**Working-tree note:** Work was performed through the GitHub connector; no local checkout/status was available to inspect. Do not infer a local working-tree state from remote branch commits.

## Current objective

Set up TACResQ for autonomous, feature-by-feature development with validation and pull requests prepared for human review. Do not merge or deploy as part of the default workflow.

## Current session

- Inspected repository metadata, default branch, top-level directory structure, agent instructions, handoff, project analysis, plan, contributing guide, CI workflow, latest commits, branches, open PRs, and current GitHub Actions state.
- Verified `main` was at `320ddec6db46f7909c6ddf4c1493f4915f212702`; it was the only existing branch at inspection and is not protected according to GitHub's branch metadata.
- Latest main CI run checked: [run 39402253764](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39402253764), successful. Source checks, prototype/visual checks, and Laravel/dashboard integration passed. Android debug/release build steps were skipped because no Gradle wrapper is checked in.
- No open pull requests were present at inspection time.
- Created `agent/setup-coding-agent-workflow` from `main`.
- Updated `AGENTS.md` with review-first feature work, task-scoped branches, explicit non-merge/non-deploy defaults, validation honesty, and side-effect boundaries. Commit: `0cbc0e139efdbd25d65424de0bbce7a6ed2870f8`.
- Added `docs/AGENT_WORKFLOW.md` describing task intake, implementation loop, validation, PR requirements, handoff, and repository-level enforcement limits. Commit: `f895a8080eae5b0fb7d17f6b189983ee0848c5c3`.
- Updated `CONTRIBUTING.md` to link the workflow and align contribution/PR guidance. Commit: `03398157a18ad5c85d4f766598047686364c61a6`.
- Refreshed this handoff to remove the old note that dependency compatibility fixes were still uncommitted; those fixes were already on the verified main tip before this task.
- Opened [PR #21](https://github.com/RamProjector/TACResQ-School-Project/pull/21). It is open and not merged. It contains only documentation/process changes; application source, dependencies, migrations, Supabase settings, deployment, and repository security settings were not changed.

## Project structure

- `app/`: Kotlin/Jetpack Compose Android source pack with requester, responder/driver, dispatcher, auth, GPS Map, API, repository, and location code.
- `supabase/migrations/`: Postgres schema, RLS/RPC and incident/location changes.
- `backend/src/`: Laravel API adapter.
- `backend/web/`: React/TypeScript dispatcher dashboard, served by Laravel at `/dispatch/`.
- `tools/`, `tests/`, `.github/workflows/`: source checks, workflow/prototype tests, visual regression, security/stress helpers, and GitHub Actions.

## Implementation and safety boundaries

- The Android code is currently documented as a source pack, not a complete Gradle project; debug/release compilation and device behavior remain unverified.
- Requester GPS is one-time and user initiated.
- Responder location sharing is foreground-only, consent-gated, approximate, and implemented in source but not device-validated. No background tracking is implemented.
- Embedded map tiles/live fleet markers, FCM push notifications, and offline write synchronization are not implemented.
- The repository is an educational prototype, not an emergency-services provider.
- Preserve role authorization, explicit location consent, minimized location access, truthful stale/unavailable states, and no fabricated GPS. Never put secrets, personal information, or real emergency data in source, tests, screenshots, commits, or handoffs.
- The responder-location migration `202610200002_responder_location_tracking.sql` was previously applied to the explicitly approved Supabase project `TACResQ-School-Project` as migration version `20261020062532`. Do not reapply it there.

## Validation and evidence

- The latest observed main CI run above completed successfully for the checks described. This is evidence about the main commit only; the latest PR-head CI is still being checked.
- Android debug and release build steps were skipped, not passed.
- Prior repository notes record passing source/workflow/prototype/location-contract/stress/dashboard checks for earlier feature work. Those historical results do not substitute for checks on new changes.
- This session used GitHub connector reads and commits; no local shell, Android SDK, emulator, or test-device run occurred.
- Do not claim the workflow branch is protected or that PR review is enforced by GitHub: main branch protection was absent at inspection, and the available integration does not expose branch-protection write settings.

## Next concrete actions

1. Check the latest CI run(s) for PR #21 and review job-level outcomes, including any skipped Android build steps.
2. Resolve any documentation or CI problems within this focused PR, if found.
3. Report the outcome and leave the PR open for user review; do not merge.
4. After the workflow is accepted, the next recommended separately scoped engineering task is to make the Android source pack a complete buildable Gradle project, then validate debug/release builds and device behavior. Treat this as a separate task/PR.

## Agent handoff — current status after PR #21 merge

- PR #21 (review-first autonomous coding workflow) was merged on 2026-10-20 as commit `e8039caa598d284a47214636b773710c2ea01344`.
- PR #22 (Android Gradle project and wrapper) remains under validation on `agent/android-gradle-wrapper`. Its prior CI run passed Android debug/release builds, source/dashboard checks, prototype/visual checks, and Laravel/Postgres integration.
- The PR #22 branch is being synchronized with the updated `main` handoff so the task remains resumable; this update does not alter Android source or live services.
- PR #23 (bounded Docker Compose retries) remains open. It shares `.github/workflows/ci.yml` with PR #22; validate the combined workflow after the Android PR is integrated.
- No merge, deployment, or live Supabase/Render change is implied by this handoff. The user has now explicitly authorized merging PRs when all relevant checks pass.
- Physical-device/emulator validation of Android location permission and foreground lifecycle remains outstanding. Use synthetic test accounts/incidents only.


## Agent handoff — responder location resume safety (2026-10-21)

- Current base verified before task: `main` at `4dc878e90cb493cad24d39b03dbebc6d5ee491e0`.
- Created review branch `agent/responder-location-resume-safety` from that exact commit.
- Focused implementation: clear prior location-send time/accuracy when a responder explicitly resumes tracking, so the UI cannot present a previous session's send as fresh; stop tracking synchronously from the responder screen's sign-out action before invoking logout.
- Added source-contract assertions for both behaviors in `tools/test_location_contract.py`.
- No live Supabase data/schema, Render deployment, or real emergency data touched.
- Validation status: implementation committed on branch; CI must run before PR review. No local Android build, emulator, or device test was run from this connector-only workflow.
- Next action: run CI on the PR and inspect results. Keep PR open for review; no merge until checks pass.


## Agent handoff — incident sync health (2026-10-21)

- Latest verified task base: merged main commit `e4ced2d73ef773a40e083793db6a437d5b907bd7` (PR #25; all four CI jobs passed before merge).
- Active branch: `agent/incident-sync-health`.
- Objective: expose periodic refresh success/failure on requester, responder/driver, and dispatcher screens without representing cached data as current.
- Implementation: each role tracks last successful sync time separately from action errors; failed refreshes preserve cached data and do not advance the timestamp; shared SyncStatus renders the last success and current refresh failure.
- Added static contract assertions to `tools/check_android_source.py`.
- No live Supabase data/schema, Render deployment, or real emergency data touched.
- Validation: CI must run on the PR. No local shell, Android emulator, or device test was available through this connector-only workflow.
- Next action: inspect diffs, open PR, and review CI; merge only if checks pass.

## Agent handoff — uniform Android UI baseline (2026-10-21)

- **Objective:** Make the Android app visually consistent enough for practical testing without redesigning the product or changing backend behavior.
- **Base:** `main` at `d9d1871d272abcffd4c1b8f8ece01aafe9a62332` (after merged PR #31).
- **Branch:** `agent/uniform-android-ui`.
- **Implementation committed so far:**
  - `app/src/main/java/com/tacresq/app/ui/theme/Theme.kt`: shared rounded shape tokens for cards/fields/buttons.
  - `app/build.gradle.kts`: Compose Material core icons dependency.
  - `app/src/main/java/com/tacresq/app/ui/RoleShell.kt`: consistent five-item bottom navigation with icons, equal-width destinations, and a visually emphasized central GPS Map.
  - `app/src/main/java/com/tacresq/app/ui/AppRoot.kt`: shared header and consistent card-based content for secondary, safety, and profile/activity sections; empty states are explicit rather than pretending records/alerts exist.
  - `app/src/main/java/com/tacresq/app/ui/auth/AuthScreen.kt`: sign-in/registration use the same card, color, spacing, and button language as the authenticated UI.
- **Latest implementation fixes:** `f52f2720c3444aa5ea059dea6aea557b3a6200dc` passes the session user into all role-section screens; `cb001bbb55869a58244a0b846bfe0cdbe3876670` switches to a core Material location icon because the `Map` vector is not present in Material Icons Core.
- **Validation:** First PR CI run `39515767823` reported source checks and prototype/navigation/visual checks passed. Android Kotlin compilation caught the two issues noted above; both are fixed on the branch. Dashboard/backend integration was still running when inspected. A new CI run should be triggered by the fixes. No local checkout or physical-device/emulator run was available.
- **Safety/scope:** No backend, Supabase schema/data, secrets, Render deployment, or real emergency records changed. Existing request/response logic was intentionally left untouched.
- **Next actions:** Open a focused PR, inspect CI results, fix any compile/source-check failures, then leave the PR open for review. Do not merge unless the user separately asks.


## Agent handoff — GPS map coordinate-state consistency (2026-10-21)

- Objective: fix the Android map status panel claiming a confirmed pin whenever latitude and longitude were merely non-null, even when marker rendering correctly rejected invalid or out-of-range coordinates.
- Branch: `agent/gps-map-coordinate-state`, created from `main`.
- Changes: centralized geographic validation in `Incident.hasValidMapCoordinates()` and reused it for marker rendering and the map status; added source-contract assertions in `tools/check_android_source.py`.
- Files: `app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt`, `tools/check_android_source.py`, and this handoff.
- Safety: no location collection or permission behavior changes; no database, Supabase, deployment, credentials, or real emergency records touched.
- Validation: changes are committed on the remote branch; CI and PR review still required. No local Android device/emulator or real map tile test was available.
- Next: open PR, check all jobs, fix failures, and merge only after checks pass as the user has authorized. Keep real-device GPS and tile loading marked unverified.


## Agent handoff — debug demo accounts for role testing (2026-10-21)

- **Objective:** make the four documented demo accounts usable for quick role-based testing without inserting them into the live Supabase Auth project or changing production/release backend behavior.
- **Base:** `main` after PR #35, merge commit `d43de9155f74a2b1a97332877b4d23134e3d9c9c`.
- **Branch:** `agent/debug-demo-accounts`.
- **Changes in progress:** debug build defaults to the in-memory fake backend; release build always selects Supabase; `-PuseSupabase=true` opts a debug build into Supabase; debug sign-in shows requester/responder/driver/dispatcher autofill shortcuts; source-contract checks and QA/README docs explain the separation.
- **Safety:** no Auth users were created, no SQL/schema/data or Supabase project settings changed, no credentials or real incident data used. Fake incidents/users are local in-memory demo data.
- **Validation:** source-contract and Android build CI must run on the PR. No local Android build, emulator, or physical-device test was run via the GitHub connector.
- **Next:** inspect the diff and PR CI. Fix any failures before reporting. The user has authorized merging when checks pass; after a green run, merge and verify the merge.


## Agent handoff — GPS Map one-time device location preview (2026-10-21)

- **Previous completed task:** PR #36 merged as `5c9c2418e6a70960f92235e6e699f4abbf6f247b`; all four CI jobs passed. Debug uses fake role accounts by default, release uses Supabase, and fake sessions stay in memory.
- **Current objective:** let a signed-in user explicitly show their current device location on the centered GPS Map alongside confirmed incident pins, without uploading that fix or starting continuous tracking.
- **Base:** `main` at `5c9c2418e6a70960f92235e6e699f4abbf6f247b`.
- **Branch:** `agent/gps-map-current-location`.
- **Implementation in progress:** added a Locate me / Refresh my location action, approximate-permission request, one-time fresh fix via existing `LocationProvider.currentFix()`, map centering, device-only marker, and clear permission/services/timeout/invalid/unavailable states. Passed the existing location provider through `AppRoot`; documented the privacy boundary and added source-contract checks.
- **Safety:** map location is only collected after an explicit tap, is held in screen memory, is not sent to the API, and is not continuously refreshed. Existing requester SOS confirmation and responder consented foreground-sharing flows remain separate. No Supabase, Render, schema, credentials, or live emergency data changed.
- **Validation:** CI has not yet run for this branch; no local Android build, emulator, or physical-device test was available via the GitHub connector.
- **Next:** inspect the exact source diff, open a focused PR, check all CI jobs, fix failures, then merge only if all relevant checks pass. Device GPS and tile loading remain manual QA requirements.


## Agent handoff — GPS Map incident pin framing (2026-10-21)

- **Previous completed task:** PR #37 merged as `cb17da8b6f84d39714b7ffd02fd3179b344ebe55`. All four CI jobs passed, including Android debug/release builds, source checks, prototype/visual checks, and Laravel/dashboard integration. Release-signature verification was skipped because signing credentials are not configured.
- **Base:** `main` at `cb17da8b6f84d39714b7ffd02fd3179b344ebe55`.
- **Branch:** `agent/gps-map-fit-incident-pins`.
- **Objective:** make existing incident pins discoverable when their coordinates are outside the default Tacloban-centered viewport.
- **Implementation:** add **Show incident pins**. With one valid pin it centers and zooms in; with multiple pins it fits a bounding box around all valid incident points; with no valid coordinates it reports that honestly. It clears the temporary device-only location marker when switching to incident-pin framing. Updated README's GPS Map description and added source-contract assertions.
- **Safety:** no location is collected by this action; no API upload, backend/schema, Supabase, Render, credentials, or live incident data changed.
- **Validation:** PR CI pending. No local Android build, emulator, physical-device GPS, or live map-tile test was run through the GitHub connector.
- **Next:** inspect the diff, open PR, run CI, fix any failures, and merge only if all relevant checks pass. Continue to distinguish CI build success from actual device GPS/tile QA.


## Agent handoff — GPS Map lifecycle resume safety (2026-10-21)

- **Objective:** ensure the osmdroid MapView is resumed correctly when Compose enters the GPS Map after the host Activity is already resumed, and avoid duplicate pause/resume calls during lifecycle transitions.
- **Base:** `main` at `97e6836c1b26aafeca39ad3cc7c07aae2dc03a6e` (merged PR #38).
- **Branch:** `agent/gps-map-lifecycle-resume`.
- **Implementation:** synchronize MapView resume/pause state with the current Lifecycle, guard duplicate transitions, and ensure disposal pauses only when needed before detaching. Added source-contract assertions for the lifecycle state check and guarded transitions.
- **Safety:** no location collection, API/backend/schema, Supabase, Render, credentials, or live incident data changes.
- **Validation:** source checks and CI must run on the focused PR. No local build, emulator, physical device, or live map-tile test has been performed through the GitHub connector. This is a plausible lifecycle reliability fix, not confirmation of the user's device-specific tile issue.
- **Next:** open a focused PR, inspect all CI jobs, fix any failures, and merge only if all required checks pass. Physical-device tile loading remains manual QA.


## Agent handoff — automatic GPS Map incident framing (2026-10-21)

- **Base:** updated to include the GPS Map lifecycle fix merged in PR #39 (`0523c6d9d87082c6254b6cec50b8da831e42ad60`).
- **Branch:** `agent/gps-map-initial-incident-framing`; PR #40.
- **Objective:** remove the initial “empty map” surprise when valid incident coordinates are already loaded, while retaining **Show incident pins** for manual reframing.
- **Implementation:** schedule initial framing with `MapView.post` after map layout. One point is centered/zoomed; multiple points are fitted. The automatic framing does not override a device-only location preview and does not request permission or upload location.
- **Safety:** no backend/schema, Supabase/Render, credentials, or live emergency data changed.
- **Validation:** CI on PR #40 passed source checks, prototype/visual checks, Android debug/release builds, and Laravel/dashboard integration before conflict resolution. Re-run CI after this resolution. Physical-device GPS, map tiles, and framing remain unverified.


## Agent handoff — GPS Map framing merged and release-signing QA aligned (2026-10-21)

- **Current main:** `70121f82c37f915ff133958dbefeaa50ab8c112c`; PR #40 is merged.
- **GPS Map change:** automatically frames valid incident coordinates on first load after map layout; one point is centered and multiple points are fit into view. Manual **Show incident pins** remains available. The location preview remains explicit and device-only.
- **Validation:** PR #40's final CI run passed source checks, prototype/navigation/visual checks, Android debug/release builds, and Laravel/dashboard integration. This does not establish physical-device GPS, OpenStreetMap tile loading, or on-device marker framing.
- **Stale PR cleanup:** PR #28 was closed as superseded; current main already has the deep-link auth redirect and optional release-signing workflow. Current signing secret names and limitations are now documented in both README and the QA workflow guide.
- **Current branch:** `agent/docs-release-signing-qa-handoff`; documentation-only alignment in `docs/QA_WORKFLOWS.md` plus this handoff.
- **Safety:** no credentials/secrets, Supabase schema or live data, Render deployment, or real emergency records changed.
- **Next concrete action:** run CI on the documentation PR, resolve any failures, then merge only if all required checks pass. The remaining GPS/map behavior requires manual testing on an Android device with internet and location services enabled.


## Agent handoff — dispatcher GPS Map unit markers (2026-10-21)

- **Objective:** connect the existing dispatcher-only Supabase RPC `api_active_responder_locations` to the Android GPS Map so dispatchers can see fresh, consented foreground location reports from en-route units.
- **Base:** `main` at `bd6843fbb1ba395bf31d4571af0864ecd6d13730` (PR #41 merged; no open PRs at task start).
- **Branch:** `agent/dispatcher-live-unit-map-markers`.
- **Implementation:** add the responder-location model and API/repository contract; fetch locations alongside dispatcher incident/unit refresh; refresh while the dispatcher map is open; draw markers only for valid reports no older than two minutes; auto-frame fresh unit points alongside incidents; add a **Show active units** control; keep the fake backend empty rather than fabricating GPS. The location RPC remains dispatcher-only and existing consent-gated foreground collection is unchanged.
- **Files changed:** Android model/API/Supabase adapter/fake adapter/repository/dispatcher view model/GPS Map/AppRoot, source-contract checks, README, GPS location notes, and this handoff.
- **Safety:** no live Supabase migration was applied, no secrets changed, no real incidents or GPS records touched, and no deployment performed.
- **Validation status:** changes are being prepared through GitHub's remote file/commit API; local Android build/device tests were not available. CI must validate the PR before merge. Verify dispatcher-only denial, freshness filtering, Android compilation, and all required CI jobs. The Laravel adapter route for active responder locations is not implemented; this screen's location feed requires the Supabase adapter.
- **Next action:** inspect generated commit and PR diff; run CI, fix any failures, and leave the PR open for review unless the user authorizes merge after all required checks pass.
