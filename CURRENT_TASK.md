# Active handoff — 2026-10-28: In-app routing foundation

- **Scope:** safely add the provider-neutral route model, Valhalla polyline6 decoder, unit tests, and design/operations note to current main.
- **Explicit limit:** this is a foundation only. No live routing provider is configured, no public demo endpoint is hard-coded, no provider secret is added to the APK, and the app continues to use external turn-by-turn navigation.
- **Safety contract:** reject invalid coordinates, malformed/oversized encoded geometry, non-positive/non-finite metrics, future timestamps, and stale route results. Route estimates are guidance, not confirmation of passable roads, traffic conditions, flooding, or emergency-vehicle access.
- **Validation:** Android JVM tests and all required current-head CI jobs must pass. No emulator/manual UI review is required per user directive; device route behavior remains unverified because no provider is enabled.
- **Next action:** open PR against current main; merge only after required automated checks pass and the branch is mergeable.

---

# Active handoff — 2026-10-28: UI cleanup and non-blocking screenshot diffs

- **User directive:** Skip emulator/manual UI review as a merge gate, while retaining automated functional, build, backend, and security checks.
- **Branch:** `agent/ui-cleanup-report-only-regression`, based on current main after PRs #188 and #189 merged.
- **UI implementation:** apply the existing UI hierarchy/action-visibility CSS refinement from former PR #180. It improves contrast, typography, card density, button touch target, emergency-action visibility, map-hero scale, and bottom-navigation spacing in the HTML prototype.
- **Validation policy adjustment:** visual screenshot diffs are captured and uploaded but report-only, because intentional redesign differences were the sole failure in #180. This does not suppress script errors or replace the separate prototype navigation/function smoke test, multi-viewport checks, Android builds/JVM tests, migration replay, or Laravel/dashboard integration.
- **Safety:** do not update baselines automatically or hide screenshot results; the report and diff artifacts remain available. No live backend/data changes.
- **Next action:** fresh CI must pass on the exact PR head. Merge only after all required blocking checks pass; note screenshot diff review was waived per user directive.

---

# Active handoff — 2026-10-28: Android Supabase auth redirect contract

- **Purpose:** retain the Android email-confirmation redirect contract and a repeatable regression test after the consolidated emergency-workflow merge.
- **Implementation:** adds `docs/ANDROID_SUPABASE_AUTH_REDIRECTS.md`, `tools/test_android_auth_redirect_contract.py`, the CI step that runs the nine contract checks, and a README link.
- **Security boundary:** app callback input is untrusted and does not establish a session from arbitrary URL tokens. This documents expected QA configuration only; it does not modify live Supabase Auth settings, email templates, or credentials.
- **Validation:** source contract test must run in CI along with Android debug/release builds and JVM tests. Emulator/device callback behavior and live email delivery remain unverified and are not merge gates under the user's directive.
- **Next action:** open a PR against current `main`, inspect all required automated checks on the exact head SHA, and merge only if checks pass and GitHub reports the PR mergeable.

---

# Active handoff — 2026-10-28: Consolidated emergency workflow implementation

- **User directive:** Implement the major TACResQ feature work in one comprehensive PR; do not block on emulator/manual UI review, but keep automated CI and security checks.
- **Branch:** `agent/consolidated-emergency-workflows`, based on the validated notification/chat feature stack.
- **Included work:** persistent recipient-scoped in-app notifications and inbox; secure incident-scoped chat backend and Android conversation UI; responder assignment/duty controls that remain fail-closed until assignment verification succeeds; canonical TACResQ product-scope documentation and README link.
- **Source:** notification/chat stack from validated PRs #174–#178; responder safety from #185; product scope from #186. Consolidating related work avoids merging overlapping older PRs separately.
- **Safety:** no live migration, production data changes, credentials, account provisioning, deployment, or repository security-setting changes. Use synthetic incidents only. Do not describe TACResQ as operationally ready.
- **Validation:** source PR heads had successful TACResQ CI; #174/#175 also passed RPC grant and frontend-review workflows, and #185/#186 passed all three workflows. Consolidated branch CI is still required and must be checked on its exact head SHA. Emulator, physical-device, and manual UI review are skipped/not run per user directive.
- **Next action:** open the consolidated PR, inspect CI on its exact head SHA, fix any failures, and merge only after required automated checks pass and GitHub confirms mergeability. Close superseded PRs only after confirming the consolidated implementation is merged.

---

# Active handoff — 2026-10-28: Autonomous implementation, skip manual UI/emulator gates

- **User directive:** Rewrite agent instructions so manual UI review and emulator checks do not block progress, and eligible PRs are merged after automated checks pass.
- **Branch:** `agent/streamline-agent-validation-merge`.
- **Files changed:** `AGENTS.md` and `docs/AGENT_WORKFLOW.md`. This handoff entry is also being added.
- **Policy update:** Agents should autonomously inspect, implement, test, open PRs, and merge eligible PRs once required automated checks pass and the branch is mergeable. Manual UI sign-off, screenshots, emulator, and physical-device review are skipped/not-run when waived; they must never be reported as passed. Existing automated tests/builds and security/authorization checks still run. No force-merges, no merging with failed/unverified required checks, no deployment/live database/data changes without separate explicit authorization.
- **Validation status:** Documentation-only policy change. CI results for this branch are pending. No local checkout, emulator, physical device, or live backend was used.
- **Next action:** Confirm this PR's automated checks. Merge only if the required checks pass and GitHub reports the branch mergeable; then resume the requested consolidated feature implementation using the refreshed handoff and open-PR inventory.

---

## Active continuation — 2026-10-24: Repair bridge rate-limit cache dependency

- **Finding:** PR #131's new `throttle:120,1` middleware failed integration CI because Laravel's configured database cache store had no `cache` table. This is not just a test-only problem: the public Render bridge could error when rate limiting tries to access that missing table.
- **Fix proposed:** Add the standard Laravel `cache` and `cache_locks` tables in a new migration so database-backed rate limiting has the required persistence. Re-run the full Laravel feature suite and clean migration replay before merge. No live Render/Supabase database has been modified.
- **Branch:** `agent/fix-bridge-rate-limit-cache-table`; review PR pending.

## Active continuation — 2026-10-24: GPS map lifecycle polling

- **Finding:** `GpsMapScreen.kt` used unbounded `LaunchedEffect` loops for its 15-second freshness ticker and dispatcher responder-location polling. Those loops could continue while the app is backgrounded, unnecessarily waking work and making lifecycle behavior harder to reason about.
- **Change:** Both loops now run inside `lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)`. Dispatcher refresh happens immediately when entering STARTED and then every 15 seconds while started. `rememberUpdatedState` keeps the latest callback without restarting the polling effect on ordinary recomposition. Existing osmdroid resume/pause/detach handling remains intact.
- **Validation added:** `tools/test_gps_map_lifecycle_polling.py` checks lifecycle gating, immediate refresh ordering, current callback usage, and preservation of MapView lifecycle handling. CI now runs this contract test.
- **Branch:** `agent/tacresq-map-lifecycle-polling`; open a reviewable PR after tests pass. Do not merge on source checks alone; confirm Android builds and the existing map/navigation/visual checks.
- **Limitations:** The new source contract test is not an emulator lifecycle test. A device/emulator check should background and resume the app, switch roles/screens, and confirm polling pauses/resumes without duplicate refresh loops.

## Active continuation — 2026-10-24: Frontend reviewer

- **Objective:** Add a separate TACResQ frontend/UI reviewer alongside the automated code/security reviewer. Scope includes general HCI/accessibility/responsive/lifecycle/error-state heuristics and project-specific GPS/map, requester, responder, dispatcher, navigation, and real-backend checks.
- **Branch:** `agent/tacresq-frontend-reviewer`, created from `main`. Changes are in a reviewable PR; do not merge or deploy without explicit authorization.
- **Implementation:** Added a standard-library Python static reviewer that analyzes changed frontend files and writes findings plus a manual sign-off checklist to the GitHub Actions job summary. Added a pull-request workflow, unit tests, and a documented review standard. The workflow has read-only repository permissions and does not execute PR code with write permissions.
- **Files:** `.github/workflows/frontend-review.yml`, `tools/tacresq_frontend_reviewer.py`, `tools/test_tacresq_frontend_reviewer.py`, `docs/FRONTEND_REVIEW.md`, `CURRENT_TASK.md`.
- **Validation status:** Unit tests are included in the new workflow; results are pending until the PR workflow runs. No emulator, screenshot, accessibility scanner, physical-device, or live backend end-to-end test is claimed.
- **Limitations:** Static patterns can miss semantic UX defects and raise false positives. It cannot judge visual quality directly. Each report includes manual checks; GPS/device and backend integration require separate evidence.
- **Next actions:** Run and inspect the PR workflow, tune heuristics against real TACResQ UI diffs, and add screenshot/emulator evidence where feasible. Review PR #123 and its CI separately; it is a distinct code-security reviewer task.

# Current task handoff

## Product intent and deployment boundary

- **Intended product:** TACResQ is being developed as a real emergency-response service requested by TACRU, not merely as a school-project demo. The engineering target is a polished, reliable Android client backed by Supabase and the TACRU dispatcher dashboard.
- **Do not misrepresent readiness:** A working build or passing CI does not establish operational readiness. Before real emergency use, TACRU must validate workflows and staffing, and the team must complete security/privacy review, field/device testing, reliability checks, deployment and incident-response procedures. Until those gates are verified, do not claim the service is production-ready or encourage real emergency reports during QA.
- **Testing discipline:** Use clearly labeled synthetic incidents and test accounts while validating. Never substitute mock behavior for live backend behavior without labeling it clearly.

## Active continuation — 2026-10-24: HCI screen-by-screen UI pass

- **Priority:** Improve the Android UI one screen at a time using human-computer interaction principles and Android activity/composition lifecycle behavior. Preserve the role-specific workflows, Supabase-backed behavior, and GPS map as a core destination. Prefer clear hierarchy, visible system status, accessible touch targets, meaningful empty/loading/error states, and lifecycle-bound work over decorative redesign.
- **Latest merged:** [PR #113](https://github.com/RamProjector/TACResQ-School-Project/pull/113) merged as `bc57e5cafbee45259fbfdcdb393a34f7da8f9009`. Responder assignment list now distinguishes initial loading from a genuine empty list and gives available/off-duty responders clear next-step guidance. All six required checks passed on commit `23718ef38db8f96d4ccca7d01d13e3bd658a9a0b`; no physical-device test was performed.
- **Current focused change:** Dispatcher queue loading and empty states. While the first queue fetch is in progress, show a compact progress card rather than implying the queue is empty. When the initial fetch fails and no cached incidents/units exist, show recovery guidance; show a true empty state only after a successful fetch. Assignment and polling behavior remain unchanged.
- **Current branch:** `agent/hci-dispatcher-loading-state`. CI required before merge; no emulator or physical-device test has been performed.
- **Verified base:** `main` at `1e955bb947156a8c829cad8c9758e258091c2355` when branch `agent/hci-requester-home` was created.
- **Current focused change:** Requester Home and request-draft safety, branch `agent/hci-requester-home`. Updated the Home header with a task-oriented subtitle and made My requests distinguish initial loading from a true empty state. Transient SOS/edit confirmation flags use non-saveable Compose state, and SOS confirmation is disabled while sending or capturing a location. Closing a request form now asks before clearing entered details, notes, step progress, or a selected location; users can keep editing or explicitly discard the draft. Empty forms close directly, and Close is disabled during location capture/submission. Existing `PollWhileStarted` refresh behavior and backend duplicate-submission guard remain unchanged.
- **Files changed:** `app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt`, this handoff.
- **Validation:** Before the latest draft-discard change, all six checks passed on commit `e99b8e52c6f5e0ffe657e4c6d475b778280d88d2`: clean PostgreSQL migration replay, Laravel/dashboard integration, RPC grant contract, Android debug/release builds, source checks, and prototype navigation/functions/visual checks. The new draft-discard change is commit `f849634720dff5b2015f03dd85ba6d4a6b4de513`; its checks must finish before merge. No emulator or physical-device test was performed.
- **Next screen-by-screen sequence:** review and improve requester request/review flow; then responder current call and consent/lifecycle states; then dispatcher queue and assignment flow; then GPS Map and profile/safety/auth screens. Keep each screen-focused change reviewable and verify lifecycle-bound polling, map disposal, permission return, process recreation, and system Back as relevant.
- **Operational boundary:** The intended destination is TACRU's real emergency-response service. This branch does not change Supabase data, accounts, migrations, or deployment. Operational use still requires TACRU acceptance and documented end-to-end/device validation.


## Active continuation — 2026-10-23

- **Priority:** TACResQ Android app for TACRU's intended real emergency-response service. Keep changes scoped, reviewable, and evidence-backed. Preserve the existing five-button navigation and GPS/map as a primary feature; do not redesign or replace it without an explicit request.
- **Current main:** `62104bc4bf99d4348a1fcae023e2ef52bdf39995` (PR #106 merged; handoff-only).
- **Recent merged work:**
  - [PR #102](https://github.com/RamProjector/TACResQ-School-Project/pull/102): independent per-responder status, server-side transition authorization, conservative incident-level status, and per-unit GPS eligibility.
  - [PR #103](https://github.com/RamProjector/TACResQ-School-Project/pull/103): oldest-first dispatcher queue and honest wait-age labels.
  - [PR #104](https://github.com/RamProjector/TACResQ-School-Project/pull/104): align the responder-status migration filename with live Supabase ledger version `20261023103737`; filename/test references only, no SQL behavior change.
  - [PR #105](https://github.com/RamProjector/TACResQ-School-Project/pull/105): refresh this handoff after PR #104. Documentation-only; no app or database changes.
- **Verification:** PR #104's pre-merge [CI run #514](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39940562411) passed source contracts, Android demo/Supabase debug and release builds, prototype navigation/function and visual checks across viewports, clean PostgreSQL migration replay, and Laravel/dashboard integration. The separate [RPC-grant contract run #261](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39940562380) passed. This is CI evidence, not physical-device or live end-to-end proof.
- **Latest handoff verification:** PR #105's [CI run #515](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39942121312) and separate RPC-grant contract run [#262](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39942121158) passed before merge. The PR was merged as `236bbc54acb21bc117a78a4397962785ec452bad`.
- **Stale PR cleanup:** outdated handoff-only PRs #43, #62, and #72 were closed unmerged because their notes were superseded by the current handoff. Draft design-research PR #68 remains open and unmerged; it does not change app behavior.
- **APK artifact:** [Download from CI run #514](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39940562411), artifact `tacresq-android-apks`. It contains `tacresq-demo-debug.apk`, `tacresq-supabase-debug.apk`, and `tacresq-release.apk`. Use the Supabase debug APK for real Auth/RPC testing. Release APK may be unsigned. The two debug APKs share an application ID, so installing one replaces the other.
- **Migration boundary:** PR #104's description states that the migration version and RPC grants were checked against live Supabase; this continuation did not independently repeat those live checks. Do not reapply the migration merely because its filename changed. Never claim a new live migration, data mutation, account seeding, or deployment unless independently performed and verified.
- **Still blocked on physical-device QA:** [Issue #24](https://github.com/RamProjector/TACResQ-School-Project/issues/24) tracks requester SOS confirmation/idempotent retry, permission denied/revoked/location services off, GPS fix and manual pin fallback, responder consent and foreground-only location, assignment-specific stop/resume, stale location state, dispatcher-only visibility, sign-out, and sanitized evidence. Use synthetic accounts/incidents and test locations only; TACResQ is an educational prototype, not an operational emergency service.
- **Latest focused improvement in progress:** branch `agent/auth-submit-double-tap` synchronously claims the auth loading state before launching its coroutine, preventing rapid repeated login/signup taps from starting duplicate requests; a source-contract assertion covers the ordering. This is a small race-condition fix, not a substitute for auth/device testing. PR will be opened after validation.
- **Next steps:** finish auth submit duplicate-tap fix CI, then install the Supabase-backed APK on a test phone; record app commit and Android version; run the issue #24 checklist; capture exact errors and sanitized screenshots. No physical device was available to this workflow, so these tests remain pending.

## Historical handoff notes

The following earlier entries are retained as project history; current repository state and the active continuation above take precedence.
**Last updated:** 2026-10-21  
**Repository:** `RamProjector/TACResQ-School-Project`  
**Base branch:** `main` at `a5b2aea5c6f2b2e3041ef28153682c678ec8613c`  
**Current task:** add a free independent breached-password check to live Android registration; physical-device QA remains pending.  
**Do not create a duplicate requester account:** the user's original verified requester profile remains present.

## Completed in the latest work

- [PR #58](https://github.com/RamProjector/TACResQ-School-Project/pull/58) merged; its anonymous table/sequence privilege migration was applied to Supabase project `kttguamhznoflzagfutr`.
- Live catalog checks verified `anon` has no SELECT/INSERT/UPDATE/DELETE privileges on `profiles`, `incidents`, `incident_status_logs`, `location_logs`, or `fcm_tokens`; no USAGE/SELECT/UPDATE on the five matching identity sequences; and no EXECUTE on the audited public application RPCs. Expected authenticated table access and active RPC grants remain.
- The live migration ledger records the migration as version `20261021113046`, name `revoke_anon_table_privileges` (the source migration is `supabase/migrations/20261021120000_revoke_anon_table_privileges.sql`; the remote migration tool assigned its own version timestamp).
- Four profile roles exist: one `requester`, one `dispatcher`, one `responder`, and one `driver`. Responder and driver are `off_duty`. The user's original verified requester account remains unchanged.
- [PR #59](https://github.com/RamProjector/TACResQ-School-Project/pull/59) refreshed the previous handoff documentation.
- [PR #60](https://github.com/RamProjector/TACResQ-School-Project/pull/60) expanded `tools/test_rpc_grants.py` to cover all active client RPCs across checked-in migrations, including unit lookup, v2 incident creation, and responder-location functions. It is merged as `1f114b5d9f047eba37d6946c32a387981481b14a`.
- CI run [39606428275](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39606428275) passed all four jobs: source checks (including the expanded RPC-grant contract), Android debug/release builds, prototype/navigation/visual checks, and Laravel/dashboard integration.
- Android APK artifacts are available from the successful [CI run](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39606428275), including `tacresq-demo-debug.apk`, `tacresq-supabase-debug.apk`, and `tacresq-release.apk`. The release APK is unsigned unless release-signing secrets are configured. The debug APKs share the same application ID, so installing one replaces the other.

## Password security implementation merged

- Added `app/src/main/java/com/tacresq/app/security/PasswordBreachChecker.kt`, which uses the free Have I Been Pwned Pwned Passwords range endpoint with k-anonymity: only the first five hexadecimal characters of the SHA-1 hash are sent; the password and full hash are not transmitted.
- Live Supabase-backed requester registration checks the password before submitting signup. Known-compromised passwords produce an inline password-field error. If the endpoint is unreachable, times out, or returns an unexpected response, live signup fails closed with a retry message; it does not claim the password passed.
- Fake/demo registration bypasses the external check so offline demos remain usable. Login behavior is unchanged.
- Added static source-contract assertions to `tools/check_android_source.py`. CI/build validation is pending; the API integration has not yet been physically device-tested.
- Proposed change is on branch `agent/free-breached-password-check`; do not merge until CI passes and the PR is reviewed.

## Current live security finding

Supabase Security Advisor still reports `auth_leaked_password_protection`: leaked-password protection is disabled. Enable it manually in the Supabase Dashboard under **Authentication → Settings → Password Security**. Available tools do not expose a safe direct toggle for this Auth setting. Recheck the advisor after enabling it.

## Remaining verification gaps

- Real sign-in and the full requester → dispatcher assignment → responder/driver status flow have not been tested on a physical phone against the live project.
- Physical GPS fix acquisition, OpenStreetMap tile loading on the user's network, current responder location reporting, and sign-out have not been device-verified.
- CI builds and offline contract tests do not prove live end-to-end behavior.
- This is an educational prototype, not an emergency-services provider. Use only clearly labeled synthetic incidents and test locations during QA; never use real emergency reports or another person's location/contact data.

## Next actions

1. Recheck Supabase Security Advisor. The native Supabase leaked-password feature remains unavailable on the current plan; the Android client now uses the independent free check, so do not describe native protection as enabled.
2. Install `tacresq-supabase-debug.apk` from the latest successful CI artifact.
3. Test with existing requester credentials and the separately provisioned QA role accounts. Do not request or store passwords in chat.
4. Create one clearly labeled synthetic incident; test dispatcher assignment and responder/driver status progression; verify updates across roles.
5. Test GPS permission denial, successful current fix, map tile loading, consent-gated foreground sharing, stale/unavailable states, and sign-out on a physical device.
6. Record observed results and any exact error messages before making further code changes.

## Safety and repository rules

- Never commit secrets, service-role keys, passwords, tokens, real emergency details, or precise personal location data.
- Keep privileged roles administrator-provisioned; public signup must only create requester profiles.
- Preserve RLS, least-privilege grants, role/status checks, explicit location consent, and truthful GPS states.
- Before further implementation, inspect the latest `main`, this handoff, and relevant code. Work on a task-scoped branch and validate changes before opening a focused PR. Do not merge or deploy by default unless the user explicitly authorizes it.


---

## Active continuation — 2026-10-28: Notification exception safety and GPS recovery

- **Notification PR:** #193, branch `fix/notification-refresh-exception-safety`, latest implementation commit `4cddc8e5aa2ee3fdb8f5d6285eed16665a2e33fc`.
- **Notification implementation:** refresh and mark-read now use a narrow `NotificationRepository` contract, catch unexpected exceptions with generic user-facing messages, preserve `CancellationException`, and clear loading/refreshing/busy state in `finally` so cancelled operations can recover.
- **Notification tests:** added JVM behavior tests for thrown exceptions, repository `Result.failure`, and cancellation on both refresh and mark-read; tests assert error messages, operation-state cleanup, and cancelled jobs. Added `kotlinx-coroutines-test` to Android unit-test dependencies.
- **Review response:** CodeRabbit flagged raw exception-message exposure and insufficient behavior coverage. Both are being addressed; its feedback is treated as actionable, not as a substitute for CI.
- **GPS PR:** #194, branch `fix/gps-location-exception-recovery`, merged by squash as `627a054bde374ced3f389e01acf18aa67cec6d19`. Unexpected location/settings API exceptions now preserve the incident map, show a retryable message, rethrow cancellation, and clear the in-progress guard in `finally`. Added `tools/test_gps_map_location_exception_recovery.py`.
- **GPS validation:** all three workflows passed on PR #194 head `4e7c0bd4edbcb2c8bbb18cb7978d133da2e69834`; emulator/physical-device checks were not run per the documented workflow.
- **Notification validation:** source review and RPC grant workflow passed on the current PR #193 head; TACResQ CI was still running at handoff update time. Recheck the exact latest head before merge.
- **Safety:** no credentials, live data, live migrations, production configuration, or Render deployment changes.
- **Next action:** finish PR #193 CI, resolve any compile/test failures, inspect the final CodeRabbit review, and merge only if the exact head passes required workflows and is mergeable. Continue with another high-impact source-backed improvement afterward.


## Handoff amendment — 2026-10-28: CodeRabbit findings and behavior-test follow-up

- **PR #193:** addressed CodeRabbit's finding that unexpected exception text could leak into the UI. Thrown exceptions now use fixed, safe fallback messages; repository-returned `Result.failure` messages remain visible through their existing path.
- **Cancellation cleanup:** refresh clears `loading` and `refreshing` in `finally`; mark-read clears the notification ID from `busyIds` in `finally`. Both still rethrow `CancellationException`.
- **Testability:** extracted `NotificationRepository` as a narrow interface implemented by `TacRepository`, allowing JVM tests to exercise `NotificationsViewModel` using a fake without networking or Android context.
- **Focused tests:** six JVM tests cover thrown exception, returned failure, and cancellation for both refresh and mark-read. They assert generic versus repository error messages, state cleanup, and cancelled-job propagation. Added `kotlinx-coroutines-test` to test dependencies.
- **CI evidence before this amendment:** source checks passed after correcting the interface contract assertion; Android JVM unit tests passed on run 969. Release build and final workflow status were still running when inspected. The amendment commit will trigger another run; use that new exact head for final merge decision.
- **Next action:** verify the new run's complete status, inspect the final diff for unintended changes, and merge PR #193 only when all required checks pass and GitHub confirms mergeability. Emulator/device review remains not run per repository policy.
