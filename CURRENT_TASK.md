## Authenticated profile INSERT grant — 2026-11-27

- **Exact-head CI:** At `db0958bc357b172cc0612c4fce16bcf7483430be`, main CI, QA, Security, Frontend Review, and read-only profile verification passed. The RPC grant contract failed at `Verify core table destructive privilege boundaries`.
- **Failure evidence:** The clean PostgreSQL replay reported `authenticated must not directly INSERT into public.profiles`. The test already expects no direct INSERT on either core table. The profile Auth trigger is SECURITY DEFINER and remains the supported profile-creation path; Admin role/account operations update existing rows through guarded RPCs.
- **Root cause from source trace:** The core migration grants SELECT and limited duty-status UPDATE on profiles, but the later hardening migration `20261122050843_restrict_core_table_destructive_privileges.sql` revoked only DELETE/TRUNCATE, not INSERT. No explicit source grant was found; the effective clean-replay privilege is therefore being neutralized with an explicit forward revoke rather than assuming the source's historical ACL baseline.
- **Fix committed:** Added `supabase/migrations/20261126190000_restrict_profile_insert_privilege.sql` in commit `3baaab6c8ac8bf3429a639ef67cbf0d0b616fe0f`. It revokes table-level INSERT from PUBLIC/anon/authenticated and dynamically revokes any column-level INSERT ACLs. It does not change SELECT, duty-status UPDATE, Auth trigger behavior, or hosted Supabase.
- **Validation boundary:** The migration is committed to the PR branch. A new exact-head CI/replay is required; no hosted migration was applied and no account or production data was changed.
- **Next:** Confirm the profile INSERT assertion passes, then inspect the remaining RPC grant contract stages for backend-only device-registry and GPS RPC boundaries.

---

## PostgreSQL 17 privilege replay harness correction — 2026-11-27

- **Exact-head finding:** The PR #370 exact-head runs at `7437a07f96c5d1d7f36c863a5cedde228fe64c73` passed Source checks, Android debug/release builds, Laravel/dashboard integration, advisory prototype checks, QA, Frontend Review, Security, and read-only profile verification. The main migration replay and dedicated RPC grant contract both failed at the same SQL test-harness parse error.
- **Root cause:** The new `FOREACH privilege_name IN ARRAY ARRAY['USAGE', 'SELECT']` loop in `tools/test_supabase_migration_replay.sh` used `privilege_name` without declaring it in the enclosing PL/pgSQL `DO` block. PostgreSQL reported: `loop variable of FOREACH must be a known variable or list of variables`. The failure occurred in the replay assertion, not in application migrations or a hosted database.
- **Correction:** Added `privilege_name text;` to the block's DECLARE section.
- **Commit:** `4b3dcd59b65dbe19e17cbbdb9308c09e374ae44e` on `docs/admin-ips-ui-master-plan`.
- **Validation boundary:** The failing run logs were inspected and the exact source declaration was re-fetched before the correction. The corrected replay and RPC grant contract still require a new exact-head run. No hosted Supabase migration, grant change, production data change, Render change, or deployment occurred.
- **Next:** Verify the new exact-head CI; if replay proceeds, inspect the next substantive PostgreSQL 17 privilege result. Keep the PG16 main replay and PG17 dedicated grant contract distinct.

---

## Admin IPS account governance and lifecycle UX research — 2026-11-26

- Added Section 41 to `docs/TACRESQ_UI_FUNCTIONALITY_MASTER_PLAN.md`: the Admin portal should retain its single `admin` privilege role; requester/responder/driver/dispatcher remain operational application roles, not Admin sub-roles.
- Audited the current Admin source in `backend/web/src/pages/AdminConsole.tsx` and confirmed the directory's server-side search/role filters, separate role/account/duty states, operator-managed Admin accounts, reasoned role and lifecycle changes, expected-state revalidation, stale-result clearing, and bounded account-activity preview.
- Added explicit UX and security acceptance requirements for safe account transitions, concurrent changes, provisioning/bootstrap boundaries, privacy-safe directory details, and truthful activity-history coverage.
- Research references: NIST SP 800-53 Rev. 5 AC-2/AC-6 (including NIST's Release 5.2.0 notice) and OWASP Authorization / Authorization Patterns Cheat Sheets.
- Updated `.agents/skills/tacresq-admin-ips/SKILL.md` so the reusable skill applies Sections 39–41 and prohibits unjustified Admin sub-roles or conflating account/duty states.
- **Commits:** `6cb3927f8143b7843e99c9ce88b44e58c36a71a1` (blueprint); `03e3c2daae1aef2c2c4b5a054140fce24f44956a` (skill).
- **Validation boundary:** Source was re-fetched and reviewed; the documentation and skill were updated on the existing PR branch. No application code, role grants, database, production configuration, or deployment changed. Exact-head CI for the new documentation/skill commits must be checked before any merge decision. No formal NIST compliance claim is made.
- **Next:** Continue the next evidence-backed Admin IPS research gap on the same PR #370; do not create another PR.

---

## TACResQ Admin IPS reusable skill — 2026-11-26

- Created repository skill `.agents/skills/tacresq-admin-ips/SKILL.md` to operationalize the canonical Admin IPS redesign blueprint.
- The skill links to `docs/TACRESQ_UI_FUNCTIONALITY_MASTER_PLAN.md` rather than duplicating its detailed evidence. It defines activation triggers, repository-first workflow, Admin IPS product boundaries, theme preservation, existing collapsible sidebar correction, page-specific layouts, adaptive design, SVG icon conventions, accessibility, IDS/IPS/security invariants, validation, and completion checklist.
- Repository convention check: no existing `SKILL.md` or skill directory was present; `AGENTS.md` and `docs/AGENT_WORKFLOW.md` remain governing instructions. Skill is placed under `.agents/skills/tacresq-admin-ips/`.
- **Commit:** `aadee9d7e1f18a28552c1921cd06ece0a64a733f`.
- **Validation:** Skill file created on existing branch; fetch it back and verify content/links. No application code, backend, database, or deployment changed. No skill runtime/tool invocation test was run.
- **Next:** Verify skill file and PR head/CI, then continue the next requested Admin IPS task under this skill.

---

## Laravel root-route test syntax follow-up — 2026-11-18

- The first rerun reached PHPUnit and exposed a PHP parse error in the newly added `ExampleTest.php`: the namespace separators had been written as doubled literal backslashes.
- Corrected the namespace and import declarations to standard PHP namespace syntax in commit `c539f0b`.
- This was a test-file syntax defect, not an application routing failure. The earlier RPC allowlist correction remains in place.
- **Validation:** awaiting a fresh CI run on the corrected head. Do not treat the Laravel integration job as passing until that run completes.

---

## Laravel backend feature-test corrections — 2026-11-18

- **Exact-head CI finding:** The Laravel/dashboard integration job had 75 passing tests and two failures. The root-route test expected HTTP 200 although `backend/src/routes/web.php` intentionally redirects `/` to `/admin/`. The RPC inventory test still expected `api_update_my_assignment_status`.
- **Root-route research:** The backend Dockerfile creates a conventional Laravel 13 application before overlaying tracked `backend/src/` files. Because the repository did not include an overriding feature test, Laravel's generated default `ExampleTest` remained in the image and asserted a generic 200 response. Laravel's HTTP testing API provides `assertRedirect('/admin/')` for the actual route contract. Added a tracked `backend/src/tests/Feature/ExampleTest.php` to replace the generated default test with an explicit admin-portal redirect assertion.
- **RPC security verification:** The migration `20261122050825_dispatch_managed_incident_progress.sql` explicitly revokes execute on both `public.api_update_my_assignment_status(bigint,text)` and its private implementation from PUBLIC, anon, and authenticated. The function is intentionally retired because incident progress is dispatcher-owned. The Laravel bridge allowlist already excludes it; updated the inventory test to match the current reviewed allowlist. Did not restore the retired RPC or widen grants.
- **Changes:** `815c49b` updates the allowlist contract test; `9aa8cd1` replaces Laravel's generated root smoke test with the actual redirect contract.
- **Research source:** Laravel HTTP Tests documentation: https://laravel.com/docs/13.x/http-tests (redirect assertions); Laravel response redirect documentation: https://laravel.com/docs/13.x/responses.
- **Validation:** The original two failures were confirmed in the exact-head GitHub Actions log. The corrections are committed but have not yet been rerun by CI. No local PHP test suite, production deployment, production migration, or merge was performed.

---

## Chat image retry contract correction — 2026-11-18

- **Finding:** Exact-head CI on `c78bfb8` failed `tools/test_incident_chat_attachments_contract.py` because the source contract still required the removed `pendingImageClientMessageId` identifier.
- **Source verification:** The current image retry implementation stores one `SessionStore.PendingChatImage` as `pendingImageRecord`; the stable idempotency key is read from `restored?.clientMessageId` and reused for retry. The same record binds incident, original caption, MIME type, SHA-256 fingerprint, and account owner. The production flow therefore has a stronger single-record contract than the stale variable-name assertion represented.
- **Change:** Updated the static contract to assert the current pending record and its restored client-message ID. No production Kotlin or backend behavior changed.
- **Commit:** `9f2d272`.
- **Validation:** Confirmed the exact assertion failure from the GitHub Actions log and inspected the current ViewModel retry path. The corrected contract has not yet rerun. Android build, CodeQL, and RPC migration replay were still running when this correction was committed. No local build/test, emulator/device validation, production migration, deployment, or merge was performed.

---

## Responder lifecycle contract boundary correction — 2026-11-18

- **Finding:** Exact-head CI passed the Android debug/release builds, Android JVM unit tests, instrumentation-test compilation, Android Lint, and APK artifact collection. The Source checks job then failed in `tools/test_activity_lifecycle_failure_safety.py` before its responder tracking assertions because it searched for a retired `fun advance(` method as the end boundary of `resumeTracking`. Dispatcher-owned incident progress intentionally removed responder-owned milestone advancement.
- **Change:** Updated the source contract to delimit `resumeTracking` at the current `stopTracking` method. This preserves the assertions for synchronous busy-state claiming and guaranteed busy-state release without reintroducing the retired responder status API. No production Kotlin code changed.
- **Validation:** Confirmed the exact failure from CI logs and inspected the current responder ViewModel implementation. The Android build and JVM unit tests passed on the preceding exact head; the corrected source contract still needs a new CI run. No production migration, deployment, or merge was performed.

---

## Incident chat lifecycle source-contract correction — 2026-11-18

- **Finding:** Exact-head CI failed because `tools/test_activity_lifecycle_failure_safety.py` required exactly nine generation-token checks. The current `IncidentChatScreen.kt` has ten guarded checks: one additional guard protects an asynchronous completion from updating a conversation that has since been closed or reopened. The implementation is stricter than the stale test count.
- **Change:** Changed the contract to require at least nine generation-scoped completion checks, allowing additional guards while still preventing accidental removal of the lifecycle protection. No production Kotlin code changed.
- **Commit:** `e496ebe`.
- **Validation:** Confirmed the failure from the exact-head CI log and counted the ten actual checks in `IncidentChatScreen.kt`. The revised contract has not yet rerun. No local tests/builds, emulator/device checks, production changes, or deployment were performed.

---

## Adaptive incident-history selection source-contract correction — 2026-11-18

- **Finding:** Exact-head TACResQ CI failed `tools/test_adaptive_multi_pane_scaffolds.py` because it expected an older selected-card border expression with an outline-variant fallback. The current `IncidentHistoryListItem` intentionally renders a primary 1.5dp border only when selected, uses secondary-container versus surface fill, and uses 2dp versus 0dp elevation.
- **Change:** Updated the source contract to assert the current selected/unselected card treatment rather than a removed expression. No production UI code changed.
- **Commit:** `9928683`.
- **Validation:** Failure confirmed from the exact-head CI log and compared against `Components.kt` lines implementing `IncidentHistoryListItem`. The revised contract has not yet rerun. No local tests/builds, emulator/device checks, production changes, or deployment were performed.

---

## OpenStreetMap attribution source-contract correction — 2026-11-18

- **Finding:** Exact-head TACResQ CI failed `tools/test_osm_map_attribution.py` because it required two literal copies of the attribution label, one for each sheet state. The map uses one persistent `BottomSheetScaffold.sheetContent` subtree in both collapsed and expanded states, so duplicating attribution text would be unnecessary and could create inconsistent UI.
- **Change:** Updated the source contract to require exactly one attribution link inside the shared sheet content, retain its 48dp minimum touch target, and retain its OpenFreeMap destination. No production map UI code changed.
- **Commit:** `d29b011`.
- **Validation:** Failure confirmed from the exact-head CI log and compared against the current GPS map sheet implementation. The revised source contract has not yet rerun on this head. No local tests/builds, emulator/device checks, production changes, or deployment were performed.

---

## Shared dispatcher assignment-history renderer — 2026-11-18

- **Finding:** Active-incident cards and the recent-closed list both exposed assignment history, but each independently duplicated the entire timeline UI. Their loading, empty, error, retry, event-label, actor-label, and timestamp behavior could drift.
- **Change:** Extracted a single `AssignmentHistoryPanel` composable and reused it from both active and closed incident cards. It preserves the existing bounded event query, chronological rendering, read-only behavior, and generic actor privacy labels. No database or dispatch workflow behavior changed.
- **Regression guard:** Updated `tools/test_assignment_history_api_contract.py` to require one shared renderer, both call sites, and a single chronological event renderer.
- **Documentation:** Updated `docs/OPERATIONAL_IMPROVEMENT_REVIEW.md` to reflect that recent closed incidents already expose history; clarified the five-incident display bound and remaining migration/RLS validation.
- **Commits:** `4524a66` shared renderer; `ec327de` regression contract; `9cbb9e7` implementation review update.
- **Validation:** Re-fetched the dispatcher source, verified both call sites and the shared component, and confirmed the user model uses a Long ID. The static contract and Android build have not run on this head. No local tests/builds, emulator/device checks, production migration, or deployment were performed.

---

## GPS pin-scale wrapper contract follow-up — 2026-11-18

- **Finding:** The next CI run showed the corrected scale assertion was still too exact: the source has three default-scale declarations because the two bitmap helpers are joined by a small annotation-icon wrapper. The wrapper correctly forwards the same scale and does not define a separate bitmap fallback.
- **Change:** Relaxed the declaration-count assertion to require at least the two bitmap helper defaults while retaining the exact two finite/positive fallback checks. No Kotlin map code changed.
- **Commit:** `3b6a715`.
- **Validation:** Confirmed the third declaration from the current `GpsMapScreen.kt` source and the exact-head CI failure. The revised contract has not yet rerun. No local test/build/emulator/device checks or production changes were performed.

---

## Profile theme preference repair — 2026-11-18

- **Finding:** The Profile theme chips were rendered in `RoleSectionScreen`, but none of the three role call sites forwarded the root `themeMode` or `onThemeModeChanged` values. The selector therefore used the screen's default SYSTEM value and no-op callback. In addition, `MainActivity` held the chosen mode only in `rememberSaveable`, so a fresh app launch did not have a durable user preference.
- **Change:** Forwarded the actual theme state and callback for requester, responder/driver, and dispatcher Profile screens. Added an app-local Preferences DataStore key for the theme mode, safely defaulting to SYSTEM for absent/unknown values and read I/O failures. MainActivity now observes the stored mode and persists Profile selections. No account/server data or production service is involved.
- **Regression guard:** Added `tools/test_theme_preferences_contract.py` and wired it into TACResQ CI.
- **Commits:** `d8f9a7c` theme-state propagation; `756c810` DataStore preference; `084161c` MainActivity persistence; `1deda93` regression contract; `6e26be0` CI step.
- **Validation:** Re-fetched and inspected all three role call sites, MainActivity, the preference implementation, and CI wiring. The new source contract and Android build have not yet run on this final head. No local build/test, emulator/device validation, backend change, migration, or deployment was performed.

---

## GPS pin-scale source-contract alignment — 2026-11-18

- **Finding:** The exact-head CI source check failed because `tools/test_gps_map_fullscreen.py` expected a local `val scale = 0.75f`. The production helper actually declares `scale: Float = 0.75f` as a parameter and sanitizes it into `safeScale`, in both annotation-pin and style-pin bitmap helpers.
- **Change:** Updated the contract to assert both helper defaults and both finite/positive fallback guards. No production Kotlin map rendering code was changed.
- **Commits:** `7b68e80` source contract; `c3d1a32` task documentation.
- **Validation:** Failure confirmed from the exact GitHub Actions log and compared with the current Kotlin helper implementations. The updated source was committed; the static test has not yet rerun on this head. No local test/build/emulator/device checks or production changes were performed.

---

## GPS full-screen source-contract alignment — 2026-11-18

- **Finding:** The latest PR #334 source-check workflow reached `tools/test_gps_map_fullscreen.py` and failed one assertion that expected `maxLines` to be immediately followed by `softWrap`. The actual GPS map toolbar correctly places its `Modifier.padding(...)` argument before those text properties. This was a stale, over-specific source assertion, not a missing UI behavior.
- **Change:** Updated the contract to assert the actual toolbar call structure, including its padding modifier, single-line constraint, disabled soft wrapping, and ellipsis overflow. No production Kotlin UI code was changed.
- **Validation:** Failure was confirmed from the exact GitHub Actions source-check log and the current `GpsMapScreen.kt` source. The updated contract was re-fetched for inspection; it was not executed locally. CI must rerun on the new head. No builds, emulator/device checks, or production changes were performed.

---

## CI compile and migration-history contract repair — 2026-11-18

- **Finding:** The latest PR #334 CI run failed Android Kotlin compilation because `TacRepository.kt` referenced `ReleaseIncidentAssignmentRpc` without importing its model, and `ResponderScreen.kt` used `CardDefaults` without importing the Material 3 symbol. The CodeQL Android build failed on the same compilation errors.
- **Change:** Added the two missing imports without changing assignment behavior or UI behavior.
- **Migration contract:** CI also rejected three newly committed migration files as untracked by the static history guard. Added the request-edit notification, assignment-event history, and dispatcher-release migrations to the pending allowlist only. This does not mark them applied or alter the live Supabase ledger.
- **Commits:** `767ee5f` repository RPC import; `d24570c` Material 3 import; `3fa4118` pending migration allowlist.
- **Validation:** Failure causes were read from the exact PR-head GitHub Actions logs. Updated source and contract files were re-fetched for inspection. No local tests/builds/emulator/device checks or production database changes were run; a new CI run for the repaired head is still required.

---

## Mutually exclusive chat retry recovery — 2026-11-18

- **Finding:** Text sends already refuse to start while an image retry is unresolved, but the image-send path did not symmetrically block an unresolved text retry. A restored state containing both retry records was also not identified explicitly. This could allow overlapping uncertain sends and ambiguous retry recovery.
- **Change:** Image sends now wait for pending-state restoration and refuse to start while a text retry identity exists. Restoring both a text and image retry record now leaves sending blocked with an explicit recovery message instead of silently choosing one.
- **Regression guard:** Added `tools/test_chat_retry_mutual_exclusion_contract.py` and wired it into the Android source-check workflow.
- **Validation:** Source and workflow files were re-fetched from the PR branch and inspected. The new contract was not executed; no Gradle build, tests, emulator, device checks, or production changes were performed.

---

## Android dispatcher assignment-history timeline — 2026-11-18

- Connected the Supabase-only `TacAssignmentHistoryApi` through `TacRepository` with positive incident-ID and 1–100 page-size validation. The optional capability remains unavailable on the Render bridge/fake backend rather than silently falling back to an unreviewed route.
- Added a read-only timeline to each active dispatcher incident card. It loads newest-first assignment, release, and incident-closure events; shows the affected field unit, actor, and formatted timestamp; and includes loading, empty, error, and retry states.
- Field-unit names resolve from the dispatcher's current units snapshot. The current dispatcher is labelled “You”; other actor IDs remain explicitly labelled as dispatcher account IDs because the event query intentionally does not join profile PII.
- RLS on `incident_assignment_events` remains the authorization boundary. No client-side role check is treated as security. No event writes or status controls were added.
- **Known limitation:** The timeline is currently exposed from active-incident cards. Closed incidents are not yet given a dedicated history-list entry, and actor names for other dispatchers are not resolved. The database migration remains unapplied to production, so the feature will report a recoverable read error until the migration exists in the target environment.
- Added UI assertions to `tools/test_assignment_history_api_contract.py` for repository capability use, event labels, timestamps, and retry/loading/error states.
- **Validation:** Source changes and static assertions reviewed only. The static test was not executed; no Gradle build, unit tests, emulator/device test, live PostgREST request, or production database change was performed.

---

## Assignment event history security contract — 2026-11-18

- **Review:** The migration records assignment, release, and incident-closure events through database triggers. Authenticated clients receive SELECT only; row-level security restricts reads to dispatcher/admin profiles. Trigger writers are private SECURITY DEFINER functions with a fixed `pg_catalog` search path and no client EXECUTE grant.
- **Change:** Added `tools/test_assignment_event_history_contract.py` to guard the event types, trigger coverage, actor attribution, and dispatcher/admin read boundary against accidental regression.
- **Scope:** This adds a static source contract only. Android retrieval/display is still not implemented; the event ledger migration remains unapplied to production.
- **Validation:** Test source was added but not executed in this continuation. No database replay, Android build, emulator/device test, or production mutation was performed.

---

## Web dispatch retirement — 2026-11-18

- **Decision:** Dispatch operations are centralized in the TACResQ Android app to avoid competing web and mobile dispatch surfaces.
- **Implemented on PR #334:** Removed the web incident queue, field-unit board, and assignment controls. The web frontend is now an administrator-only portal for user management and administrative audit review. Dispatcher accounts receive a clear message directing them to Android.
- **Routing:** The portal is served at `/admin/`; the service root redirects there. The former `/dispatch/` static dashboard path is no longer built or served.
- **Preserved:** Supabase dispatcher authorization, assignment/status RPCs, backend APIs, and Android dispatcher workflows remain unchanged. This is a web UI scope change, not a removal of the dispatcher role or backend capabilities.
- **Validation:** Updated Docker build path, frontend base path, runtime-config alias, CI smoke-test path, and deployment documentation. No CI run, web build, Render deployment, or production Supabase change has been performed in this continuation.

---

## Dispatcher-managed incident progress — 2026-11-17

- Reframed status ownership: the dispatcher advances shared incident milestones; responders no longer tap through per-person Assigned → En route → On scene → Transporting → Resolved steps.
- Dispatcher active-incident cards now show the assigned roster summary and meaningful incident-level actions. The per-member progress count and expandable member status list were removed.
- Responder cards retain assignment visibility and optional GPS sharing, but no longer ask field personnel to update progress. GPS is not treated as proof of arrival or completion.
- Repository status updates now call the dispatcher status API. Added migration `20261122050825_dispatch_managed_incident_progress.sql` to authorize dispatcher-only incident transitions, mirror the milestone to legacy assignment fields for GPS/older-client compatibility, and synchronize assigned personnel duty status.
- Supabase production schema was inspected read-only. The migration is committed to the PR branch but has **not** been applied to the production database; applying it before the Android release would make old responder status clients incompatible.
- Research basis: Supabase database-function guidance recommends explicit execution grants and secure search paths; the new RPC checks the authenticated profile role and validates allowed transitions. Existing incident status audit trigger remains in place.
- Validation: source inspection and live schema/function inspection only. No Gradle build, automated tests, emulator, or device validation run.

---

## Latest UI review — 2026-11-17

- Dispatcher field-unit availability is now explicitly marked as unconfirmed during refresh or after refresh failure. The ready-unit overview metric and no-available-unit guidance no longer present retained cached data as current fact. Assignment behavior is unchanged. Commit: `96a9e29652d282be495f8e72cceeabf71448ab29`.
- No build, tests, emulator, or device validation performed.

---

## Latest UI review — 2026-11-17

- Dispatcher pending empty state now distinguishes a confirmed empty queue, an in-progress check, and a failed refresh with a retained cached snapshot. It no longer presents an unverified cached empty subset as current fact. No queue or assignment behavior changed. Commit: `876f5f4850338346082f3c2f2e7376371e33d65b`.
- No build, tests, emulator, or device validation performed.

---

## Latest UI review — 2026-11-17

- Dispatcher overview now shows only actionable counts (Pending, Active, Ready units); removed the ambiguous all-record “Incidents” metric that could include completed/cancelled records. The queue and assignment filters are unchanged. Commit: `a981104419eb2a098c7b79d8837a755b7f5428e9`.
- No build, tests, emulator, or device validation performed.

---

## Latest UI review — 2026-11-17

- Dispatcher field-unit cards now separate role metadata from a clearly labelled contact number. Blank phone values are omitted; no data access or dispatch behavior changed. Commit: `0a3ed42bcd989b5747c41fa2c8136d7aa0d034c0`.
- No build, tests, emulator, or device validation performed.

---

## Latest UI review — 2026-11-17

- Dispatcher active-team summary now presents one precise progress count (“moved beyond Assigned”) instead of a count plus its redundant complement. Expanded member statuses remain unchanged. No assignment/status behavior changed. Commit: `2ce421ba208d6b2b3591731503bc35df4b24a42c`.
- No build, tests, emulator, or device validation performed.

---

## Latest UI review — 2026-11-17

- Responder location-sharing status now distinguishes startup from attention-required messages and only shows tracking status on the incident actually being shared. Direct start/resume verification failures use the existing error banner. No tracking or assignment behavior changed. Commit: `4bd7a664a7c15e687a0be70f010bb60566875d40`.
- No build, tests, emulator, or device validation performed.

---

## Latest UI review — 2026-11-17

- Responder duty-status chips are now disabled when already selected, preventing no-op duty updates and unnecessary refreshes. The existing busy and active-assignment/freshness guards remain unchanged. Commit: `4479382f7c58faa56adbec367591dbabe209b04c`.
- No build, tests, emulator, or device validation performed.

---

## Latest UI review — 2026-11-17

- Responder active-incident cards now separate requester name and phone under a clear “Requester contact” label. Contact remains visible to the assigned responder; no dialer or authorization behavior changed. Commit: pending in this handoff.
- No build, tests, emulator, or device validation performed.

---

## Latest UI review — 2026-11-17

- Dispatcher field-unit list now displays AVAILABLE units first to reduce scan effort; all other statuses remain visible and preserve their relative order. Assignment eligibility still uses the existing fresh snapshot filter. Commit: pending in this handoff.
- No build, tests, emulator, or device validation performed.

---

## Dispatcher queue-age urgency cue — 2026-11-17

- **Finding:** Waiting age switched to the error color at 15 minutes, but no dispatch policy or service-level target in the reviewed screen defines 15 minutes as an escalation threshold.
- **Change:** Kept the elapsed waiting-time label but removed the unsupported error-color threshold. Oldest-first ordering and timestamp handling remain unchanged.
- **Safety:** No queue ordering, incident priority, dispatch assignment, or status behavior changed. Add a semantic urgency cue only after an authorized policy defines its threshold and meaning.
- **Validation:** Source review only. No Gradle build, tests, emulator, or device validation run.

## Dispatcher pending-action hierarchy — 2026-11-17

- **Finding:** Each pending incident card showed team selection and field-claiming configuration as equally prominent outlined buttons, although team assignment is the main dispatch action and claimability is a secondary configuration toggle.
- **Change:** Kept “Select response team” as the primary outlined action and changed the claimability toggle to a full-width text action. The conversation action remains secondary.
- **Safety:** Existing busy and assignment-freshness guards are unchanged. No dispatch, team assignment, claimability, or messaging behavior changed.
- **Validation:** Source review only. No Gradle build, tests, emulator, or device validation run.

## Dispatcher summary metric labels — 2026-11-17

- **Finding:** The summary mixed incident counts and available-unit counts, but labels “Total” and “Available” did not name their subjects. “Live overview” also overstated freshness when the retained snapshot could be stale.
- **Change:** Renamed the labels to “Incidents” and “Ready units”, and the heading to “Dispatch overview”. Count calculations and sync/freshness indicators are unchanged.
- **Validation:** Source review only. No Gradle build, tests, emulator, or device validation run.

## Dispatcher sync-warning consolidation — 2026-11-17

- **Finding:** Dispatcher refresh failure could render the shared sync-status surface and a second “Assignment paused” error card. Both repeated that the retained incident/unit snapshot might be stale.
- **Change:** Removed the duplicate card and incorporated the assignment-safety consequence into the existing sync message whenever the incident/unit snapshot is not fresh. The sync panel remains the single place to see freshness and invoke Refresh.
- **Safety:** Assignment and field-claim controls remain gated by the existing `assignmentDataFresh` state. No dispatch, assignment, claimability, refresh, or backend behavior changed.
- **Validation:** Source review only. No Gradle build, tests, emulator, or device validation run.

## Responder route guidance consolidation — 2026-11-17

- Removed repeated routing-provider/fallback paragraphs from each active incident card.
- Added one shared explanation below the active-response heading when at least one active incident has a valid map pin.
- Kept each card's route-preview action and invalid/missing-pin warning intact.
- No routing provider, coordinate, location-sharing, or status logic changed.
- Source review only; no build, tests, emulator, or device validation run.

## Requester emergency form selection cleanup — 2026-11-17

- Removed the repeated “Selected” label from the selected emergency-type option. The radio state and highlighted card already communicate selection, while the selectable row retains its accessibility selected semantics.
- No request validation, emergency type, location, SOS, or server behavior changed.
- Source review only; no build, tests, emulator, or device validation run.

## Incident chat follow and composer polish — 2026-11-17

- Added conditional auto-follow for incoming messages: new messages scroll into view only when the reader is already at/near the bottom. The screen no longer stays pinned to the first loaded position after polling, and users reviewing older messages are not forcibly repositioned.
- Disabled the composer while pending-message restoration is unresolved.
- Hid the draft character counter until the user reaches 1,800 of the 2,000-character limit.
- Targeted UI-only behavior; message persistence, server delivery, authorization, and image upload logic are unchanged.
- Source review only; no build, tests, emulator, or device validation run.

## Notifications refresh race and redundant retry control — 2026-11-17

- Guarded notification refresh success, failure, and exception publication with the active refresh generation. A superseded request can no longer overwrite the latest inbox snapshot if its underlying operation completes after cancellation.
- Removed the duplicate “Try again” button from the empty-error card; the status panel's Refresh action remains the single retry control.
- No notification authorization, read-state, deep-link, realtime, or backend behavior changed.
- Same PR #334. Source review only; no build, tests, emulator, or device validation run.

## Adaptive history recovery affordance — 2026-11-17

- Added an explicit Refresh history action to stale/unavailable history states in both the adaptive list and detail panes, so a warning is actionable without navigating away.
- Same PR #334. No build/tests/device validation run.

## Adaptive history stale-state handling — 2026-11-17

- The adaptive requester/responder/dispatcher history pane now receives role-specific loading and freshness context from its ViewModel.
- It distinguishes initial loading, confirmed empty history, and stale/unavailable history. It no longer labels a failed or cached-only request/response list as “Nothing here yet.”
- A stale-snapshot warning is shown in both list and detail panes while preserving read access to the last-loaded details.
- No backend, assignment, status, or incident visibility behavior changed. Same PR #334; source review only, no build/tests/device validation.

## Requester and dispatcher stale-state clarity — 2026-11-17

- Requester “No requests yet” and the My requests heading now require a successful live status check (or initial loading). Cached-only and unknown states no longer appear as a confirmed empty request history.
- Dispatcher refresh failures retain the last loaded lists; the warning now explicitly says those lists may be out of date. An empty active list during sync failure is described as an unconfirmed last-loaded snapshot, not a current empty queue.
- No request, assignment, cancellation, status-transition, or backend logic changed.
- Same PR #334. Source review only; no build/tests/device validation run.

## Authentication honesty follow-up — 2026-11-17

- Found that the password-reset UI claimed a link was sent, while the repository implementation only validated the email and returned success without contacting an auth recovery endpoint.
- Removed the fake send flow and unused repository stub. The Forgot password dialog now explicitly says no email was sent and directs the user to the organization's official support channel.
- Real password recovery remains a follow-up requiring Supabase Auth recovery endpoint integration and app deep-link/redirect handling.
- Same PR #334. No backend/auth provider change, build, tests, or device validation run.

## Profile, messages, and notifications continuation — 2026-11-17

- Removed the local-only Step-Up Security / OTP switch; it did not enforce verification or call a security service.
- Removed duplicate name/email/role rows already present in the Profile identity header.
- Corrected Profile location-sharing copy to acknowledge background updates during an active en-route response when sharing is enabled.
- Replaced the false account-deletion success message with an explicit information-only dialog; it now says in-app submission is not connected and does not claim a request was received.
- Reduced chat header height by removing the persistent reminder card and redundant subtitle; the subtitle now identifies the authorized-participant visibility scope.
- Simplified the Notifications header subtitle without removing push/inbox guidance.
- Added `docs/ANDROID_SCREEN_REVIEW.md` with implemented findings and outstanding workflow/device/legal-review items.
- Same PR #334. Source review only; no build/tests/device validation or backend deletion implementation.

## Role-screen continuation — 2026-11-17

- Simplified responder and dispatcher information hierarchy: shared operational guidance is now shown once at section level rather than repeated in every incident card.
- Responder arrival-vs-GPS-sharing reminder appears once only when an en-route response exists.
- Dispatcher no-available-units explanation appears once above the pending queue; team assignment action remains absent unless at least one unit is available.
- No operational/business logic changed. Same PR #334 branch. No build/tests/device validation run.

## Latest GPS continuation — 2026-11-17

- Map-load failure feedback now distinguishes a known offline condition from a generic map-data load failure using the latest validated connectivity state.
- Successful load clears the transient error. Offline badge remains independent. Did not label individual tile/style failures because current callback does not provide reliable per-tile diagnosis.
- Same PR #334; no validation checks run.

## Latest GPS continuation — 2026-11-17

- Improved selection invalidation: expired/removed field-unit reports and incidents now produce explicit feedback rather than silently dropping the selected detail.
- When a selected incident leaves the visible active set, pending route work is cancelled and late results invalidated; the obsolete route preview is cleared.
- Same PR #334 branch. No build/tests/device validation run.

## Latest GPS continuation — 2026-11-17

- Fixed an explicit map-retry omission: stale responder clusters now reconnect to the newly recreated style source alongside incident and fresh/aging responder clusters.
- Same PR #334 branch; no validation checks run. Next inspect selected-location persistence and map load/error recovery without changing emergency location-sharing semantics.

## Dense-cluster feature lookup optimization — 2026-11-17

- Built remembered indexes for visible incidents and valid responder reports, keyed by incident ID and the incident/user pair respectively.
- Cluster leaf resolution, page loading, point-hit selection, and selection revalidation now use keyed lookups instead of repeatedly scanning the full Compose lists for every map feature. This changes the per-leaf record lookup from linear to constant-time after each source-list refresh; index construction remains linear.
- Kept the indexes tied to the current visible lists and exposed their latest versions to the long-lived MapLibre click listener, so refreshed records are still revalidated at interaction time.
- No map behavior, role visibility, freshness policy, or backend contract changed.
- No build, tests, emulator, device validation, or profiling run.

## Cluster chooser consistency and pagination follow-up — 2026-11-17

- Updated the older annotation-overlap chooser to initialize the same count and pagination state as the new style-layer chooser, preventing a misleading “0 of N” count.
- Gave nested page iteration an explicit leaf label so invalid records skip only the current leaf, not the enclosing source page.
- Cluster chooser copy now distinguishes a dense cluster from ordinary overlapping pins and gives a concise next action.
- No build, tests, emulator, device validation, or profiling run.

## Dense cluster pagination — 2026-11-17

- Added paged cluster-leaf retrieval for dense incident and responder clusters. The chooser initially loads up to 50 leaves per source and now exposes a clear “Load more locations” action while more leaves remain.
- Pagination tracks each overlapping cluster source independently, so a mixed incident/responder overlap can page both without merging their source semantics. The displayed count reflects the underlying cluster counts.
- Every loaded page is resolved against the latest visible incident and valid responder records; stale responder details remain labelled as last known. Expired cluster IDs are caught and the chooser asks the user to tap the refreshed cluster again.
- Selecting or dismissing a choice clears paging cursors so stale cluster references are not reused.
- No build, tests, emulator, device validation, or profiling run.

## Cluster style retry and pin anchor correction — 2026-11-17

- Corrected the explicit map-retry style callback to pass the required Android context when rebuilding cluster sources and style images. This keeps retry behavior aligned with initial style installation.
- Separated legacy annotation bitmap padding from GeoJSON style-symbol bitmaps. Style symbols are now created at the drawable's actual bounds, so `icon-anchor=bottom` anchors the rendered pin tip to its geographic coordinate rather than to an invisible padded region.
- Retained the existing padded bitmap helper for legacy device-location and annotation markers to avoid changing their established anchor behavior.
- MapLibre's style specification defines bottom anchoring as placing the image's bottom edge at the feature anchor; see the official Android/style API documentation.
- No build, tests, emulator, device validation, or visual pin-alignment check run.

## Cross-category GPS overlap handling — 2026-11-17

- Fixed an interaction gap where an incident cluster could consume taps intended for a responder cluster at the same screen position. When incident, fresh/aging responder, and stale responder clusters overlap, the map now builds one explicitly typed chooser from the current validated leaves rather than prioritizing one category.
- Individual incident and responder pins now share the same 48dp hit-area chooser. A responder marker can no longer become inaccessible merely because an incident pin occupies the same touch area (or vice versa).
- Cluster leaves and point features are revalidated against the latest visible incident and responder collections before presentation. Stale units remain labelled as last known.
- No build/tests/device checks run.

## Restore emergency pin artwork on clustered points — 2026-11-17

- Reused the app's existing incident, selected-incident, responder, and stale-responder drawable artwork as MapLibre style images, instead of leaving individual points as generic circles after clustering was introduced.
- Individual incident pins now have a distinct selected state; responder pins retain their own artwork, and stale responder artwork is faded to reinforce last-known status. Cluster bubbles remain count-based and category-colored.
- The shared bitmap helper now supplies both legacy device-preview annotation icons and style-backed symbol images, keeping pin-tip anchoring consistent.
- No build/tests/device checks run. Verify sprite registration, pin anchor, symbol scaling, selected state, and style recreation on supported screen densities.

## Separate stale responder clustering — 2026-11-17

- Split responder map data into independent fresh/aging and stale GeoJSON cluster sources. Stale last-known reports now form grey clusters with their own counts instead of being mixed into the teal live/aging cluster count.
- Individual stale points remain grey and are only queried from the stale point layer. Fresh/aging responder points retain their separate layer and cluster source.
- Cluster tap handling supports both responder sources and still resolves individual leaves against the latest validated responder list before opening details.
- This avoids a potentially misleading cluster that visually implied all grouped responder locations were current. No build/tests/device checks run.

## Cluster point hit-target and overlap disambiguation — 2026-11-17

- Individual clustered-source points now use a 48dp screen-space query area instead of requiring taps to land inside the small painted circle.
- When multiple incident points or responder points occupy that touch area, the map presents the existing nearest-first location chooser rather than silently selecting whichever feature is returned first.
- Chooser targets are revalidated against the latest visible incidents and valid responder reports. Stale responder locations retain their last-known warning.
- Incident and responder point queries remain separate so a mixed set cannot be mistaken for a single type of cluster.
- No build, tests, emulator, device validation, or performance profiling run.

## Cluster source invalidation correction — 2026-11-17

- Added both GeoJSON source references to the annotation/source update effect keys. This ensures the first render and a style reload publish current features after the asynchronous style callback creates the sources; previously a source becoming available alone would not retrigger that effect.
- Explicit map retry now clears old source references and recreates both cluster sources/layers on the replacement style, matching initial style setup.
- This is an important lifecycle correction for the new clustering path. No tests/build/device checks run.

## Cluster interaction and lifecycle follow-up — 2026-11-17

- Moved the map click listener effect below MapView and cluster-source state declarations so it is owned only after its dependencies exist; listener registration remains paired with removal.
- Cluster taps now zoom to MapLibre's expansion level when that level is available. At maximum expansion, the tap retrieves up to 50 leaves and builds a chooser from only currently visible incidents and still-valid responder reports. Stale responder entries remain explicitly marked last-known.
- Expired cluster features and reports are handled as recoverable refresh states rather than selecting stale records. Native cluster API runtime errors are caught because cluster IDs can expire during source replacement.
- The chooser is bounded to 50 records to avoid unbounded synchronous work on the UI thread; when records have expired, the user is told that only currently available reports are shown.
- No build, tests, emulator, device validation, or performance profiling run.

## Native GeoJSON clustering — 2026-11-17

- **Implemented on PR #334:** Replaced independent incident and responder marker annotations with two native MapLibre GeoJSON cluster sources. Each source has its own cluster circles, count labels, and unclustered point layer. Fresh/aging responder points and stale last-known points have separate visual treatments; device preview and route rendering remain outside the clusters.
- **Interaction:** Tapping a cluster requests MapLibre's expansion zoom. Tapping an individual point resolves its stable domain ID against the latest Compose data and opens the existing incident/field-unit selection flow. Cluster lookups catch runtime failures caused by stale cluster features during source refresh.
- **Refresh/style lifecycle:** Cluster source data is updated from the current validated visible incident and responder sets. Both sources and layers are recreated after initial style load and explicit style retry.
- **Research:** MapLibre Android's official example uses `GeoJsonSource` with `GeoJsonOptions.withCluster(true)`, `point_count` filters, and `SymbolLayer` count labels. Android clustering is supported from MapLibre Native 4.2.0; this app uses 13.7.1. References: https://maplibre.org/maplibre-native/android/examples/styling/circle-layer/ ; https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-source/get-cluster-expansion-zoom.html ; https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map/query-rendered-features.html.
- **Known limitations:** Native cluster bubbles currently use category-specific colors and counts; individual pin artwork is replaced by colored circles. A bounded leaf chooser for clusters that cannot expand further is outstanding. Verify density, tap targeting, style reload, and source-update races on devices before calling this complete.
- **Validation:** No Gradle build, JVM tests, emulator/device run, or profiling performed, in accordance with the instruction not to run checks after each commit.

## GPS camera restoration and route failure follow-up — 2026-11-17

- Prevented initial-data auto-framing from immediately overriding a restored camera after screen recreation.
- Tightened the route exception path to require both request and selection generations before publishing a failure message. A route failure from a previously selected incident can no longer overwrite the current selection's status.
- The request-generation-only `finally` guard remains intentional: it clears loading only for the latest route request, while selection changes invalidate results through the separate selection generation.
- No tests/build/device checks run.

## GPS route identity, camera restoration, and memory pressure — 2026-11-17

- **Route ABA race:** A route for incident A could pass the old ID check after the user selected B and then A again. Added a monotonic selection generation captured by each route request; every incident-selection transition invalidates prior generations. Checks now cover location-settings completion, fresh-fix completion, provider completion, result/error publication, and the posted camera move.
- **Camera restoration:** Persisted validated camera target, zoom, bearing, and tilt with saveable Compose state. A MapLibre camera-idle listener records settled camera state and is removed on disposal. Recreated map screens restore the user's last camera rather than always resetting to Tacloban; invalid saved coordinates fall back safely.
- **Memory pressure:** Registered application-level ComponentCallbacks2 for the lifetime of the MapView effect and forwarded low-memory / running-low trim callbacks to MapView.onLowMemory on the main thread. The callback is unregistered during teardown and ignores events after disposal.
- **Validation:** No tests/build/device checks run. Verify A→B→A with delayed providers, rotate/recreate while zoomed/panned, and background/foreground under memory pressure on physical devices.

## GPS marker callback freshness — 2026-11-17

- **Finding:** The MapLibre marker click listener is installed once when the map becomes ready, but it called a selection function that closed over the then-current Compose lists and freshness clock. After data refreshes, a tap could validate a responder/incident against stale screen data.
- **Fix on PR #334:** Wrapped marker selection in `rememberUpdatedState` and have the long-lived MapLibre listener dispatch through the latest callback. Current visible incidents, responder reports, device fix, and freshness are therefore checked at tap time.
- **Validation:** No tests/build/device checks run. Verify marker taps after incident refresh, responder-location refresh, expiry, and permission changes.

## Unified MapView lifecycle ownership — 2026-11-17

- **Finding:** MapView destruction and lifecycle pause/stop were split across two independent Compose effects. Their disposal ordering was not explicit, so teardown could destroy the native map before its pause/stop callbacks.
- **Fix on PR #334:** Consolidated map load listeners, asynchronous map-ready callback, lifecycle observer, and native MapView destruction under one `DisposableEffect`. Teardown now removes listeners, unregisters the observer, pauses/stops only if active, then destroys the view exactly once.
- **Validation:** No tests/build/device checks run. Verify screen exit while resumed, while paused, and during map initialization; also verify returning from background.

## MapView listener and disposal cleanup — 2026-11-17

- **Finding:** The MapView registered map-load success/failure listeners without removing them on composable disposal. Its asynchronous `getMapAsync` callback also had no disposed-screen guard.
- **Fix on PR #334:** Retain both listener instances, guard their state updates, unregister both listeners in `onDispose`, mark the effect disposed before teardown, and ignore a late map-ready callback after disposal.
- **Why:** MapLibre 13.x exposes matching `removeOnDidFailLoadingMapListener` and `removeOnDidFinishLoadingMapListener` APIs. This prevents stale callbacks and avoids retaining disposed screen state unnecessarily.
- **Validation:** No build/test/device checks run. Verify repeated navigation into/out of GPS and delayed map initialization on device.

## GPS route-request stale-result protection — 2026-11-17

- **Finding:** Route cancellation alone did not protect against a provider that returns after cancellation or a user changing the selected incident while location capture/routing is suspended. A late result could update route state/status or post a camera move for an incident no longer selected.
- **Fix on PR #334:** Validate both request generation and selected incident before sending the route request, after the provider returns, before publishing route state, inside the posted camera callback, and before publishing exception status. Late results are discarded.
- **Safety:** A changed selection prevents the old incident destination from being sent to the routing provider after the fresh-location step. No provider, permission, routing mode, or backend behavior changed.
- **Validation:** No tests/build/device checks run, per instruction. Verify rapid incident switching with delayed/non-cooperative routing responses on device.

## GPS marker signature correctness — 2026-11-17

- **Finding:** Incremental marker reconciliation stored signatures as delimiter-joined strings. Incident notes and other display text can contain the delimiter, making state comparison ambiguous.
- **Fix on PR #334:** Replaced string signatures with a typed immutable data class containing exact coordinates, explicit icon variant, title, and snippet. Incident selection and responder freshness are represented explicitly; device preview uses the same signature shape.
- **Scope:** Marker keys, selection behavior, freshness rules, and displayed text are unchanged. This is correctness hardening, not a measured performance claim. No tests/build/device checks run.

## GPS style reload and route invalidation correction — 2026-11-17

- **Finding:** The renderer's internal route comparison used exact coordinates, but the Compose effect that triggers reconciliation still depended on `geometry.hashCode()`. A collision could prevent the exact comparison from running. Style replacement/retry also left the remembered annotation owner unchanged, risking stale marker/polyline references after a style reload.
- **Fix on PR #334:** The effect now keys on ordered latitude/longitude coordinate pairs. Initial style completion and explicit retry invalidate the renderer owner so the next reconciliation clears stale annotation references and rebuilds visible markers and route on the loaded style.
- **Scope:** No map provider, routing, incident data, permissions, tracking, or backend behavior changed. No tests/build/device checks run, per instruction.

# GPS marker identity and dispatcher selection linkage

- Replaced marker click behavior based on parsing the visible title with a registry keyed by MapLibre's stable annotation ID. Incident, field-unit, and device-preview marker behavior now resolves from typed metadata kept separately from display text.
- Tapping a dispatcher field-unit marker highlights its matching field-unit row in the expanded map sheet; tapping a row also selects/highlights it. The highlight clears when that location report is no longer available or another marker type is selected.
- Routine incident-list refreshes no longer clear map action/marker status text. Incident selection still clears if its incident is no longer visible.
- Preserved marker titles/snippets for user-facing map metadata, stale-location warnings, coordinate data, camera behavior, route rendering, permissions, location sharing, and backend contracts.
- Research: MapLibre's current Android API exposes marker annotation IDs, while its legacy Marker/MarkerOptions/Annotation APIs are deprecated from 7.0. This change uses the existing dependency's marker ID rather than attempting a renderer migration in the same patch. The Annotation Plugin migration remains a separate, coordinated task.
- Validation: not run per the requested commit cadence. Review marker taps, row highlighting, report refresh/removal, and screen-reader announcements on-device.

# GPS marker summary readability

- Shortened the status text shown after tapping field-unit and device-location markers so it fits the map sheet's compact two-line summary instead of concatenating the full marker snippet and risking truncation.
- Stale field-unit selections prioritize the safety-critical “last known only; confirm with dispatch” warning. Fresh/aging selections direct dispatchers to the field-unit detail list for exact age and accuracy; the one-time device preview retains its not-uploaded/not-tracked disclosure.
- Full age, accuracy, and freshness details remain in the existing field-unit list and marker metadata. No location data, permissions, sharing, routing, or backend behavior changed.
- Validation: not run per the requested commit cadence; verify summary readability with long incident IDs and stale/fresh markers on-device.

# GPS map marker interaction consistency

- Unified marker taps with the existing in-app bottom sheet. Incident pins continue to select the incident and expand its details; dispatcher field-unit pins and the one-time device-location preview now show their title and explanatory snippet in the map status area and expand the same sheet.
- Prevented MapLibre's default InfoWindow from appearing for responder/device markers, avoiding a second, inconsistent detail surface. Stale responder text remains explicit that the coordinate is last known and must be confirmed with dispatch.
- Unknown marker types remain unhandled rather than being assigned invented behavior. Marker data, freshness thresholds, location permissions/sharing, camera behavior, routing, and backend contracts are unchanged.
- Validation: not run per the requested commit cadence. Review marker taps, stale warnings, and screen-reader announcements on-device before merge.

# GPS map header — remove remaining search-field silhouette

- **Review finding:** After removing the full-width header container, the title still sat inside a weighted, elongated rounded Surface. On a narrow map header it continued to resemble an inactive search field, recreating the same misleading visual affordance at a smaller scale.
- **Change:** Made the title backing content-sized with a maximum width, reduced its padding and corner radius, and retained a high-contrast local surface for legibility over both light and dark map tiles. The back, retry/offline, and locate actions remain separate controls with their existing touch targets.
- **Layout behavior:** The title no longer expands to consume all remaining row width; actual header height continues to drive native compass placement, so the compass follows the compacted layout.
- **Preserved:** GPS capture, permissions, marker rendering, map gestures, route previews, attribution, role visibility, and backend contracts.
- **Validation:** Not run per the requested commit cadence. Verify the header at 320 dp width, with/without Retry map, and over light/dark tiles on-device before merge.

# GPS map research and rendering refinement

- **Compass placement:** Replaced the fixed 180–224 dp guess with placement derived from the measured bottom edge of the actual Compose header plus a small gap. This adapts automatically when filter chips appear/disappear, the device rotates, or available window height changes. A safe initial fallback is retained until the first layout measurement; style retry reuses the latest measured position.
- **Marker refresh behavior:** Removed the 15-second freshness tick as a direct trigger for full MapLibre annotation teardown/recreation. Marker snippets now use minute-granularity age text (with an explicit under-one-minute label) and a refresh key that changes only when displayed age or the Fresh/Aging/Stale classification changes. Sheet freshness counts still refresh on the existing 15-second cadence; changed incident/responder data, selection, device preview, and route changes still redraw the map.
- **Why:** MapLibre exposes compass placement through pixel margins and supports positioning its native controls independently; Android Compose layout measurement provides the actual rendered dimensions. Android accessibility guidance recommends at least 48 dp interactive targets; the existing map controls retain their current sizing.
- **Research sources:** [MapLibre Android UiSettings](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-ui-settings/index.html) documents compass gravity/margins and attribution/logo placement; [MapLibre Android configuration](https://maplibre.org/maplibre-native/android/examples/configuration/) documents native map controls; [Android Compose insets guidance](https://developer.android.com/develop/ui/compose/system/material-insets) explains that custom layout/inset handling must be based on actual component placement; [Android touch-target guidance](https://support.google.com/accessibility/android/answer/7101858) recommends 48 dp targets.
- **Preserved:** Map provider/style, native MapLibre attribution and logo, gestures, map camera rules, GPS permissions/capture, location sharing, responder freshness thresholds, routing, incident visibility, and backend contracts.
- **Validation:** Not run, per the requested commit cadence. Review compass position on short/tall layouts and confirm markers do not flicker during idle 15-second refreshes on a device before merging.

# GPS map overlay contrast and compass clearance

- **Review finding:** Removing the faux-search container also removed the only backing surface behind the back affordance and map title. Those controls were left directly on variable map imagery, making their contrast dependent on whichever tile happened to sit underneath. The native MapLibre compass also used a top margin that could intrude into the two-row header/filter area on shorter map windows.
- **Implementation:** Kept the full-width faux-search bar removed. Restored contrast only where needed: a compact tonal back button, a small title surface, and a tonal retry action. Existing location action and contextual filter chips retain their own compact surfaces. Moved the native compass lower and made its responsive bounds account for the header and filter row.
- **Scope:** GPS map overlay presentation and compass placement only. Map content, map gestures, camera behavior, attribution, GPS capture, permissions, routing, and incident data are unchanged.
- **Validation:** Not run per the requested incremental-commit cadence; inspect contrast, compass clearance, and overlap on short and tall devices before merging.

# Remove GPS map faux-search header container

- Removed the full-width rounded, elevated Surface surrounding the GPS map title and controls. It looked like a search field despite having no search function and visually competed with the map.
- Kept the back action, map title, offline/retry state, location action, and contextual incident/field-unit filter chips. The controls now sit directly over the map rather than inside a large faux-search bar.
- Validation: not run per the requested incremental-commit cadence; verify text/control contrast over varied map tiles on-device.

# GPS map filter touch targets

- Increased the Incidents and Field units map filter chips to a 48 dp minimum height, aligning their tap area with the other primary map actions and improving reachability on touch devices.
- Scope: filter control sizing only. Filter behavior, map camera actions, incident visibility, and responder data are unchanged.
- Validation: not run per the requested incremental-commit cadence; device review remains outstanding.

# GPS field-unit list touch targets

- Raised each actionable dispatcher field-unit location row to a minimum 48 dp height so its map-centering action is reliably reachable on compact screens and easier to tap without changing the freshness details or interaction.
- Scope: GPS map sheet presentation only. Responder location data, freshness rules, camera behavior, permissions, routing, and backend contracts are unchanged.
- Validation: not run per the requested incremental-commit cadence; device and accessibility review remain outstanding.

# GPS camera actions wait for map readiness

- Added a shared readiness guard for camera-focused actions from the Incidents and Field units controls and incident detail cards.
- When map data/style is still initializing or recovering, the action now announces that state instead of silently issuing a camera animation against an unavailable map.
- Empty-list feedback and selection behavior remain unchanged; map loading, retry, permissions, location sharing, routing, and backend behavior are untouched.
- Validation: not run per the incremental-commit cadence; build and device review remain outstanding.

# GPS dispatcher field-unit list interaction

- Made each dispatcher field-unit location entry a clearly accessible map-centering action. Selecting an entry now centers the map on that responder's reported coordinate and updates the live sheet status.
- Preserved freshness age, accuracy, and stale-position warnings; stale entries explicitly state that the centered position is last known and must be confirmed with dispatch.
- If the map/style is not ready, the action reports that state rather than silently appearing to work.
- Scope: dispatcher GPS presentation and map camera focus only. Location collection, polling, permissions, responder sharing, freshness thresholds, and backend behavior are unchanged.
- Validation: not run per the incremental-commit cadence; device and accessibility review remain outstanding.

# GPS dispatcher empty-state accuracy

- Corrected the dispatcher map sheet's no-incident message when field-unit location reports are still available. It now directs dispatchers to review field-unit freshness below instead of incorrectly saying there are no incident or field-unit locations.
- Scope: empty-state copy only. Field-unit visibility, freshness classification, map pins, camera framing, permissions, location sharing, and backend behavior are unchanged.
- Validation: not run per the incremental-commit cadence.

# GPS map accessibility description accuracy

- Updated the MapLibre view's screen-reader description so it no longer claims the map is centered on Tacloban City after users pan, zoom, or the app frames other incident locations.
- Kept the role-specific instructions for selecting incident pins and reviewing the bottom sheet, including dispatcher responder-freshness context.
- Scope: accessibility wording only. Initial camera target, map gestures, GPS capture, marker data, permissions, routing, and location sharing are unchanged.
- Validation: not run per the incremental-commit cadence; screen-reader/device review remains outstanding.

# GPS map selection and expanded-sheet controls

- Selected incident markers now use a larger pin asset variant. The annotation renderer observes selectedIncidentId, so selection changes redraw the marker immediately and the active pin is easier to distinguish when several incidents are nearby.
- The floating fit-all control is hidden while the details sheet is expanded, avoiding a control that would otherwise sit beneath the sheet. An equivalent accessible fit-all action is placed in the expanded sheet header, where it remains reachable.
- Marker tap behavior, incident selection, map framing, sheet navigation, permissions, location sharing, stale-location handling, routing, and backend contracts remain unchanged.
- Validation: not run per the requested commit cadence; visual/device review is still needed for overlapping markers and narrow sheet headers.

# GPS map research implementation — camera, compass, recovery

- Camera framing: Replaced the uniform bounds inset with asymmetric, density-aware pixel insets. The top reserves room for the header/filter controls; the bottom reserves the measured bottom-sheet obstruction plus breathing room. Insets are capped to preserve a usable viewport on short windows and expanded sheets. If sheet measurement is not ready, the calculation falls back to the existing 120 dp collapsed-sheet height.
- Compass: Extracted compass positioning into a shared helper and made its top margin respond to rendered map height, bounded between 144 dp and 196 dp, rather than always using a fixed 176 dp.
- Map recovery: Added a user-triggered “Retry map” action when MapLibre reports a style/data loading failure. It reuses the existing OpenFreeMap style, resets existing loading/error flags, and restores compass setup after style reload. It does not change providers or network policy.
- Regression coverage added: Pure unit tests cover normal camera insets, expanded-sheet capping, fallback sheet geometry, and invalid dimensions/density.
- Research basis: MapLibre documents asymmetric newLatLngBounds padding and states that bounds padding does not persist to later camera transformations; Android documents that bottom-sheet offsets are pixel values initialized after layout; Android adaptive guidance recommends adapting layouts to available window dimensions. Sources: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.camera/-camera-update-factory/new-lat-lng-bounds.html ; https://developer.android.com/reference/kotlin/androidx/compose/material3/SheetState.html ; https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes
- Scope: No incident data, GPS permission, consent, location-sharing, routing, backend, role visibility, or marker freshness rules changed.
- Validation: Not run per the incremental-commit workflow. Device review remains necessary for actual overlay geometry and retry behavior.

# GPS map control icon refinement

- The single fit-all control now uses the map-location pin icon rather than the device “my location” reticle. This better communicates that it frames all currently visible map points, not just the user's device position.
- No camera behavior or GPS capture behavior changed. Validation not run per the incremental-commit workflow.

# GPS map follow-up — visible tile-load failure state

- **Finding:** A MapLibre style/tile load failure updated the bottom-sheet status, but the full-screen map header only showed an error chip when Android reported no validated internet. A provider/style failure while the device remained online could therefore leave a blank map without a visible explanation.
- **Change:** The header now also displays a compact “Map unavailable” error chip whenever the existing MapLibre load-failure state is active. Its accessibility description explains that incident details remain available. The existing “Offline” chip remains for connectivity loss.
- **Scope:** Reuses the existing map-load listener state; no retry loop, provider switch, tile-source change, pin behavior, or network policy was introduced.
- **Validation:** Not run per the incremental-commit workflow.

# Current handoff — 2026-11-17: GPS map interaction simplification

- **Finding:** The full-screen map exposed separate zoom-in and zoom-out buttons alongside a fit-locations action. This added three stacked controls and duplicated gestures already available directly on the map; the zoom buttons were also a previously identified UI distraction.
- **Change:** Replaced the stack with one 56 dp floating fit-locations control using the familiar location-target icon. It remains disabled until the map style is ready and continues to frame the same role-appropriate visible locations. Removed the now-unused explicit zoom handlers.
- **Scope:** Presentation and map-camera affordance only. Pin rendering, map gestures, GPS capture, route previews, incident selection, and location permissions are unchanged.
- **Validation:** Not run per the incremental-commit workflow; verify on a device that the single control is reachable above the sheet and frames the expected pins for each role.

# Current handoff — 2026-11-17: remove duplicate full-screen map attribution overlay

- **Finding:** The GPS map manually overlaid a second `© OpenMapTiles · © OpenStreetMap contributors` label while the map drawer already provided a detailed attribution link. The map uses MapLibre with OpenFreeMap; OpenFreeMap documents that MapLibre automatically displays the required attribution control.
- **Implementation:** Removed only the app-authored bottom-right duplicate text overlay. Kept the drawer's detailed attribution link and left MapLibre's native attribution control enabled. This preserves map-data credit without stacking a redundant app-level label over the emergency map.
- **Scope:** Removed duplicate presentation only. No map style, provider, tile source, attribution settings, navigation, or GPS behavior changed.
- **Validation:** Not run per incremental-commit instruction; no build, emulator, or device behavior is claimed.
- **References:** OpenFreeMap attribution guidance; OpenMapTiles attribution guidance.
- **Next action:** Continue focused client UI refinements on PR #334; consolidate validation after implementation changes are complete.

---

# Current handoff — 2026-11-17: preserve GPS map status context in collapsed sheet

- **Finding:** The collapsed map sheet forced its status/supporting message onto one line. Longer operational messages (such as location-permission guidance and dispatcher field-unit freshness) were routinely ellipsized.
- **Implementation:** Allow the collapsed map summary up to two lines while retaining ellipsis for narrow windows and unusually long messages.
- **Scope:** Summary text layout only. Map state, GPS permissions, incident visibility, routing, and sheet expansion behavior are unchanged.
- **Validation:** Not run per incremental-commit instruction; no build, emulator, or device behavior is claimed.
- **Next action:** Continue focused client UI refinements on PR #334; consolidate validation after implementation changes are complete.

---

# Current handoff — 2026-11-17: accessible GPS map loading feedback

- **Objective:** Ensure assistive-technology users receive the same map-loading feedback that sighted users see.
- **Finding:** The centered map loading indicator displayed a message visually, but did not expose the loading message as a polite live-region announcement.
- **Implementation:** Merge the loading indicator and its text into one polite live region so the loading state is announced without repeatedly interrupting higher-priority speech.
- **Scope:** Loading-overlay semantics only. Map loading lifecycle, error handling, map interaction, and GPS behavior are unchanged.
- **Validation:** Not run by request for incremental commits; no build, emulator, or device behavior is claimed.
- **Next action:** Continue focused client UI refinements on PR #334; perform consolidated validation after implementation changes are complete.

---

# Current handoff — 2026-11-17: GPS map camera control loading state

- **Objective:** Prevent map camera actions from appearing interactive before the map style is ready to receive them.
- **Finding:** Zoom and fit-visible-locations controls remained enabled while MapLibre was loading; tapping them only produced a transient “map is still loading” message.
- **Implementation:** Tie all three camera buttons to the existing `mapStyleLoaded` state. They remain visible in their stable map positions but are disabled until camera operations are available.
- **Scope:** Camera-control interaction state only. Map rendering, GPS capture, incident pins, camera framing, routing, and sheet navigation are unchanged.
- **Validation:** Not run by request for incremental commits; no build, emulator, or device behavior is claimed.
- **Next action:** Continue focused client UI refinements on PR #334; perform consolidated validation after implementation changes are complete.

---

# Current handoff — 2026-11-17: authentication form wide-screen readability

- **Objective:** Keep sign-in and requester registration fields comfortably readable on tablets and desktop-sized Android windows.
- **Finding:** The authentication card expanded to the full available width on large windows, creating unnecessarily long form fields and scan lines.
- **Implementation:** Constrain the centered authentication card to a 520 dp maximum while retaining full available width on narrower phones.
- **Scope:** Authentication layout only. Validation, account roles, password handling, session state, and repository behavior are unchanged.
- **Validation:** Not run by request for incremental commits; no build, emulator, or device behavior is claimed.
- **Next action:** Continue focused client UI refinements on PR #334; perform consolidated validation after implementation changes are complete.

---

# Current handoff — 2026-11-17: chat attachment preview responsiveness

- **Objective:** Keep image selection responsive by avoiding bitmap decoding on the Compose UI thread.
- **Finding:** The selected chat attachment preview decoded up to the accepted 5 MB image payload synchronously inside composition, which could cause a visible pause on slower devices.
- **Implementation:** Move preview bitmap decoding into a keyed `produceState` coroutine on `Dispatchers.Default`; changing or clearing the attachment cancels/restarts the keyed work and updates the preview state.
- **Scope:** Local attachment-preview rendering only. Image validation, upload, message identity/retry behavior, and conversation permissions are unchanged.
- **Validation:** Not run by request for incremental commits; no build, emulator, or device behavior is claimed.
- **Next action:** Continue focused client UI refinements on PR #334; perform consolidated validation after implementation changes are complete.

---

# Current handoff — 2026-11-17: notification refresh timestamp readability

- **Objective:** Keep notification synchronization information readable and consistent with the inbox's localized date formatting.
- **Finding:** When realtime was connected, the status panel displayed the raw ISO-8601 server-refresh timestamp, unlike the human-readable timestamps used on notification cards.
- **Implementation:** Format the last successful server refresh through the existing locale- and device-time-zone-aware notification time formatter; retain the existing fallback when no refresh has succeeded.
- **Scope:** Notification status presentation only. No realtime lifecycle, refresh cadence, notification read state, navigation, or backend behavior changed.
- **Validation:** Not run by request for incremental commits; no build, emulator, or device behavior is claimed.
- **Next action:** Continue focused client UI improvements on PR #334; perform consolidated validation after implementation changes are complete.

---

# Current handoff — 2026-11-17: dispatcher active-queue accuracy

- **Objective:** Make the dispatcher's “Active incidents” section match the actual active-incident count and provide a useful empty state.
- **Finding:** The overview counted only statuses marked active, but the list displayed every non-pending incident, which could place completed or cancelled records under an “Active incidents” heading.
- **Implementation:** Derive one active-incident collection from the model's existing `isActive` status rule, use it for both the overview count and active list, clarify the section subtitle, and show a calm empty-state explanation when no active incidents exist.
- **Scope:** Dispatcher presentation/filtering only, reusing the existing status classification. No incident status transitions, assignment, claiming, API, database, or dispatch permissions changed.
- **Validation:** Not run by request for incremental commits; no build, emulator, or device behavior is claimed.
- **Next action:** Continue focused role-screen UI refinements on PR #334; run a consolidated validation cycle after the implementation set is frozen.

---

# Current handoff — 2026-11-17: responder active-response hierarchy

- **Objective:** Make the responder's active incident workload easier to scan without changing assignment or response workflows.
- **Implementation:** Add a distinct section heading before the active response cards, with concise supporting text that explains what the section contains.
- **Scope:** Presentation-only change in the responder screen. No claiming, status transitions, location sharing, routing, permissions, API, or database behavior changed.
- **Validation:** Not run by request for incremental commits; no emulator/device review is claimed.
- **Next action:** Continue refining role-specific client screens and preserve emergency-critical details.

---

# Current handoff — 2026-11-17: requester active-emergency accessibility

- **Objective:** Make the requester's current emergency state easier to navigate with assistive technology without changing incident actions or status rules.
- **Implementation:** Expose the active-emergency card as a polite live region so status/description changes can be announced, and mark its title as a semantic heading for screen-reader navigation.
- **Scope:** Requester presentation semantics only. No SOS submission, editing/cancellation, messaging, status polling, GPS, API, or database behavior changed.
- **Validation:** Not run by request for incremental commits; no emulator/device accessibility review is claimed.
- **Next action:** Continue requester form and role-specific screen refinements on this dedicated PR branch while preserving emergency-critical information.

---

# Current handoff — 2026-11-17: shared client UI polish

- **Objective:** Establish a more consistent, readable visual and accessibility baseline across requester, responder/driver, and dispatcher screens through shared Compose components.
- **Branch:** `agent/client-ui-polish-2026-11-17`.
- **Implementation:** Refine shared screen-header hierarchy and allow two-line supporting text; expose screen and section titles as accessibility headings; increase section and banner spacing; remove persistent neutral borders from unselected incident cards while retaining a clear selected state; use theme-aware incident status pills in cards and history rows; add subtle resting card elevation; constrain long incident-type titles without truncating emergency descriptions or location/access notes.
- **Scope:** Shared Android UI components only. No role permissions, incident workflow, GPS behavior, API, database, routing provider, or production configuration changed.
- **Validation:** Not run by request; this incremental UI task is being committed without running checks. No emulator/device visual review is claimed.
- **Next action:** Continue screen-specific requester, responder/driver, dispatcher, and adaptive-pane refinements on this dedicated PR branch. Keep GPS map full-screen and preserve all emergency-critical content.

---

# Current handoff — 2026-11-17: constrain wide-screen role content

- **Objective:** Improve Android large-window readability across requester, responder/driver, and dispatcher screens without changing compact phone layouts or the full-screen map.
- **Branch:** `agent/constrain-wide-screen-content`.
- **Finding:** The navigation rail now adapts to medium/expanded windows, but role content still fills all remaining horizontal space. Long descriptions and operational cards can stretch excessively on large tablets or resizable desktop windows.
- **Implementation:** Constrain non-map role content to a centered maximum width of 960dp in both rail and bottom-navigation layouts. This also covers wide, short-height/resizable windows that retain the bottom bar. The content remains fluid below that width; the dedicated GPS Map branch remains full-window and unconstrained.
- **Regression coverage:** Extended `tools/test_adaptive_multi_pane_scaffolds.py` to require the centered max-width container and verify that the map branch remains full-screen.
- **Research basis:** Android's current adaptive-layout guidance explicitly recommends setting a maximum width on content/components to prevent stretching, using containment/panes, and adapting based on window size. Material guidance also warns that overly long lines become difficult to read. Sources: https://developer.android.com/design/ui/mobile/guides/layout-and-content/adapt-layout ; https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts ; https://developer.android.com/codelabs/adaptive-material-guidance
- **Commits:** `3820c09709e2164ef202c0f1b6f5195c257272a4` (centered max-width content); `5e22b278f5cf296c6d9e24926a85773b0ade4957` (initial adaptive layout regression coverage); `43f8c676049eb03a1433c5fbe7a1e778e3bcfc4d` and `dec67eeb961fb1fb9e0e841388dc18b24f86f575` (cover short-wide bottom-navigation layouts too).
- **Validation:** Pending exact-head CI and Android build. No emulator/device visual review has been run.
- **Scope boundary:** No role navigation behavior, map viewport, backend, database, or production/deployment behavior changed.
- **Next action:** Validate the exact PR head, then continue with screen-specific tablet/landscape improvements.

---

# Current handoff — 2026-11-17: responder status transition confirmations

- **Objective:** Align the native responder/driver status UI with the repository's role-based wireframe specification.
- **Branch:** `agent/responder-status-confirmations`.
- **Finding:** The current UI required a confirmation before starting an en-route response (which also handles location-sharing permission), but advanced later statuses directly through `vm.advance`.
- **Implementation:** Every later transition now opens a confirmation dialog before calling `vm.advance`. The resolved transition has a distinct “Confirm response completion?” title and explicit instruction to mark resolved only when the responder's assigned response is complete. The dialog explains that per-unit status is shared with dispatch and the requester, while the overall request reflects team progress. Before allowing confirmation, it re-reads the latest incident and verifies that the captured per-unit status is unchanged and the requested transition is still valid; stale dialogs are disabled and explain that the responder must refresh. The specialized en-route/location-sharing dialog remains unchanged.
- **Regression coverage:** Added `tools/test_responder_status_confirmation.py` and wired it into `.github/workflows/ci.yml`.
- **Research basis:** The repository's `docs/wireframes/DESIGN_SPEC.md` requires deliberate confirmation for responder status progression and stronger confirmation for resolution. ITU-T F.760.2 (2024) recommends clear feedback, role/task-contextualized information, and reduced cognitive workload for emergency-response systems: https://www.itu.int/epublications/fr/publication/itu-t-f-760-2-2024-04-guidelines-for-user-interface-in-emergency-response-support-systems
- **Commits:** `61d75bf18eeae97aea29f085d3a5d248e18e76dc` (status confirmation UI); `c51f35396c78c6dfb2b74f1e6594d26e0946257f` (regression contract); `a9fde3b4048405de2155dba4c9be8c2d69a41f38` (CI integration); `8bf6465df81ca547ef3af9e3b82b28ec1bc52437` and `1a8349ea84a84ae341cbccdeb432ecd6207ab67b` (clarify that the resolution action applies to the individual responder, not the whole incident); `24e55be33d21f1aba7ce0568cfb909e4aeb86967` and `9c84ed72174b4ffe73d85de007cd4670f8c3bfaa` (revalidate current status and next-step legality before confirmation); `afbb630322c2dfdb59b8465c44f328d8bfa5484f` and `54bcbc5a7f800b0b8dd9bbe1ac6f78be8f91fa55` (align the regression contract with the stale-state dialog and conditional close label); `c49077f7be9488efa1cbc52e8e5433a283fcd94b` (merge already-validated main changes without force-pushing).
- **Validation:** Pending exact-head CI and Android build. No emulator/device UX test has been run.
- **Scope boundary:** No backend status rules, assignment permissions, location sharing, database, production, or deployment behavior changed.
- **Next action:** Validate exact PR head, then inspect dispatcher and remaining role-specific wireframe gaps.

---

# Current handoff — 2026-11-17: requester SOS review simplification

- **Objective:** Improve the actual Android requester emergency-reporting UI against the repository's own wireframe specification.
- **Branch:** `agent/requester-sos-single-review-action`.
- **Finding:** The review stage already displayed the emergency type, details, location source/freshness, and landmark. Its `Confirm SOS` button then opened a second modal that repeated those same details before `Send SOS`.
- **Implementation:** The review screen now contains the single explicit `Send SOS` action. The redundant second confirmation dialog was removed; the safety warning “Only send if help is needed now” remains on the review screen. The one-time-location disclosure was retained on the review page. The location step now requires an explicit acknowledgement that the one-time pin or landmark will be shared with dispatch and the assigned response team; the acknowledgement resets after a confirmed SOS or discarded draft and is not restored from draft data. Sending remains disabled unless an emergency type is selected, current incident status is confirmed, no refresh is running, location capture is inactive, and pending-SOS verification is complete without error.
- **Regression coverage:** Added `tools/test_requester_sos_single_review_action.py` and wired it into `.github/workflows/ci.yml`.
- **Research basis:** The repository's `docs/wireframes/DESIGN_SPEC.md` defines a review-and-confirmation stage with a separate deliberate send action. ITU-T F.760.2 (2024) recommends simplified information presentation and reduced cognitive workload in emergency-response interfaces. Material Design confirmation guidance supports meaningful confirmations but says not all actions warrant another confirmation; this screen already provides a complete final review. Sources: https://www.itu.int/epublications/fr/publication/itu-t-f-760-2-2024-04-guidelines-for-user-interface-in-emergency-response-support-systems ; https://m1.material.io/patterns/confirmation-acknowledgement.html
- **Commits:** `32a2d4342b011b5f031a08a2683df3fed04482c6` (single review action); `db73b611f4e13d72170ee9967308df3a482ad8e4` (keep safety warning); `13d2b9b6892fcd7ed2924ee46618d9d7ffab57e4` (initial regression contract); `8c93642d9a73b0f85726703bdbcc3c47a76ec894` (CI integration); `83e01e84e6df4c038d5b3080c5a3c0ad6f788082` (retain one-time location disclosure and current-status gating); `dd39197c84f7ade543704441e3d0b9f3fd15d0a3` (extend regression assertions); `18ca920a2061a672f1060a4bd43b923521e2aae1` (location-sharing acknowledgement); `4efaa427ec4fdf77355cb85b460a343da7d98c6c` (acknowledgement regression assertions); `17118cfd89e060eb04bdf50a683021cfbe3b65e0` and `ef97e1405de2b4864b0d227727f63c959dfec5bb` (preserve the existing canonical location-disclosure wording required by the provenance regression contract).
- **Validation:** Pending exact-head CI and Android build. No emulator/device UX test has been run.
- **Scope boundary:** No backend, database, production, permissions, or deployment changes.
- **Next action:** Inspect exact-head checks and review, then continue role-screen improvements using the in-repository wireframes.

---

# Current handoff — 2026-11-17: adaptive navigation and role-shell UI refinement

- **Objective:** Improve the actual Android application UI using the existing Kotlin/Jetpack Compose source and the uploaded TACResQ wireframe design references.
- **Branch:** `agent/adaptive-navigation-medium-window`.
- **Implementation:** `RoleShell.kt` now switches from bottom navigation to a navigation rail at medium-width windows (600dp+) when height is at least 480dp. Compact and short-height windows retain bottom navigation. This aligns the breakpoint with Android's current adaptive guidance rather than reserving the rail only for expanded-width (840dp+) windows.
- **Regression coverage:** Added `AdaptiveNavigationPolicyTest.kt` for compact, medium, expanded, and short-wide window cases. Updated `NavigationInstrumentedTest.kt` to expect the actual Messages destination instead of the obsolete Alerts label.
- **Research:** Android Developers adaptive-app guidance recommends bottom navigation for compact windows and navigation rail for medium/expanded windows; adaptive layouts should respond to app-window dimensions. Compose accessibility guidance recommends minimum 48dp touch targets. References: https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts ; https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation ; https://developer.android.com/guide/topics/ui/accessibility/apps
- **Commits:** `2b08cb497a791b4c0bd80704d0efba5a1d7bda5f` (adaptive breakpoint); `713aa5668ef573029fce5a11ba66753540361f70` (unit tests); `3638a04cac1a4e2290d4101b3b0d0f830b1ec945` (instrumented navigation test label).
- **Validation:** Not yet run; PR CI will provide exact-head validation. Physical-device/emulator visual review is not run.
- **Scope boundary:** No backend behavior, permissions, live Supabase state, or deployment configuration changed.
- **Next action:** Inspect the exact PR head and CI, then continue with the next high-impact screen-level improvement using the requester/responder/dispatcher wireframes.

---

# Current handoff — 2026-11-16: comprehensive review + durable chat outbox tests

- **Active PR:** [#312 Persist incident chat text retries across app restarts](https://github.com/RamProjector/TACResQ-School-Project/pull/312).
- **Current branch:** `agent/durable-chat-text-outbox`; latest commit after review follow-up: `f88c41c01c9684cd4f92e922ca5e34f427e8ffd9`.
- **Review follow-up:** added `SessionStorePendingChatPersistenceTest.kt` covering encrypted outbox restoration after store recreation/logout, account isolation, rejection of replacement content, and matching-ID-only clearing. Extended `test_android_chat_contract.py` to require these tests. These instrumentation tests compile in CI but are not executed on an emulator by the current workflow.
- **Prior exact-head CI evidence (before the two test commits):** at `e4e37d5431f6013a2cd030d024bb7df7ba3a995e`, source checks, clean PostgreSQL migration replay, Laravel/dashboard integration, RPC grant contract, static UI/UX review, repository security guardrails, CodeQL Web/Actions, and CodeQL Android passed. Android debug/Supabase debug builds, JVM unit tests, instrumentation-test compilation, and lint passed; release APK build was still running at last inspection. CodeQL aggregate was neutral because the `quality-assurance.yml:security` configuration was not found. CodeRabbit skipped due manual review being required for this zero-star OSS repo. All checks must be re-read on the new exact head before merge.
- **PR review notes:** server idempotency is already enforced by `(sender_id, client_message_id)`; the new local record is encrypted and account-bound. No backend migration is needed. Image attachment bytes/retry persistence remains deliberately separate. Do not merge until exact-head checks finish and the new instrumentation test compiles.
- **Read-only live Supabase audit (project `kttguamhznoflzagfutr`; no writes):** checked migrations, table metadata, function grants/search paths, RLS policies, storage bucket, extensions, and advisors. Live migration versions match checked-in main migration files through `20261116042927_responder_incident_claiming`. Chat attachment bucket is private with a 5 MiB limit and JPEG/PNG/WebP allowlist; storage policies bind paths to the authenticated profile and incident-chat access. `pg_cron` and `pg_net` are absent; only Vault was present among the queried dispatch extensions, so production Cron FCM dispatch remains inactive.
- **Supabase advisor findings:** leaked-password protection is disabled (warning); 18 public SECURITY DEFINER functions are callable by authenticated users, but sampled function bodies enforce `auth.uid()`/role/ownership and none of these RPCs is executable by anon. Five no-policy tables have direct grants revoked from anon/authenticated and are accessed through guarded functions. `api_units` is the one exposed SECURITY DEFINER function whose configured search path still includes `public, auth`; harden to a minimal search path in a separate migration after reviewing the complete definition. Performance advisor reports four unindexed foreign keys, six RLS initplan warnings, and nine unused indexes; do not drop unused indexes based on a low-traffic prototype alone.
- **System-wide review findings:** `PROJECT_ANALYSIS.md` is stale and contradicts current README/roadmap capabilities and live metadata. `backend/README.md` describes OSRM-only routing while `backend/src/config/tacru.php` and routing docs set Geoapify as default. GPS provenance stores only `gps/manual/none`; the Android `GeoFix` and incident schema do not retain the actual location provider name/version. Route preview remains disabled by default; FCM delivery is best-effort and not operationally activated. Physical-device QA issue #24 remains open.
- **No production side effects:** no Supabase migration, data mutation, extension activation, Vault secret, Render setting, Cron job, FCM send, or deployment was performed.
- **Next action:** verify CI on the new exact head, then continue the highest-risk system review/fix in a separate focused PR. Preserve the no-paid-services and no-production-change boundaries.

---



# Current handoff — 2026-11-16: Android FCM permission reconciliation

- **PR #311:** [Reconcile Android FCM registration with notification permission](https://github.com/RamProjector/TACResQ-School-Project/pull/311) — merged by squash at `ef28bf21d311a480b144c0c39163500282edef58`.
- **Implementation:** FCM token registration now requires Android runtime notification permission (Android 13+), app-wide notification availability, and an enabled TACResQ alert channel. If alerts are unavailable, the app unregisters the server-side token and retains local retry state if cleanup fails. Registration is reconciled on app resume and immediately after permission is granted.
- **Validation:** Exact-head TACResQ CI passed Android debug/release builds, source checks, clean PostgreSQL migration replay, and Laravel/dashboard integration. Security guardrails, Android and Web/Actions CodeQL, static UI/UX review, and RPC grant contract all passed. Manual code review found no reason to change the existing data-only payload or recipient-profile guard.
- **Safety boundary:** No FCM priority increase, auto-rendered notification payload, live notification send, Supabase mutation, production Cron activation, or Render environment change was made. The in-app inbox remains authoritative.
- **Remaining device gate:** Manually test permission grant, revoke from Android Settings while signed in, app resume/token cleanup, sign-in again after regrant, channel disabled, app-wide notifications disabled, account switching, and Doze on a physical Android device. CI builds do not prove these OS-level behaviors.
- **Next implementation candidate:** After device validation, assess high-priority data-only FCM for urgent user-visible alerts. Preserve the client-side recipient-profile check; do not use system-rendered notifications unless account-isolation and permission behavior are redesigned and validated first.
- **Production dispatch remains inactive:** PR #310's endpoint is deployed but fail-closed until the operator explicitly authorizes configuring Render/Vault secrets and enabling conditional Supabase Cron. No production configuration was applied.

# Current handoff — 2026-11-16: FCM queue dispatch trigger

- **Objective completed in code:** add a private, payload-free HMAC-authenticated endpoint at `POST /internal/fcm/dispatch` that invokes the existing FCM delivery-ledger dispatcher with a fixed batch size of three.
- **PR #310:** [Add signed Supabase Cron FCM dispatch trigger](https://github.com/RamProjector/TACResQ-School-Project/pull/310) — merged by squash at `c13dfbb3c3798a84aacdbc732a20aa6985212647`.
- **Render deployment:** `dep-dbt8pl49v7es73cnk1o0` is live on commit `c13dfbb3c3798a84aacdbc732a20aa6985212647`. Render build and deployment both succeeded.
- **Security:** the endpoint requires HMAC-SHA256 over a five-minute timestamp, UUID request ID, and exact empty JSON body; rejects caller-supplied query/body parameters and replays; uses a five-per-minute throttle and a single-instance file-cache lock; never accepts notification IDs, recipients, tokens, or message content.
- **Validation:** exact-head TACResQ CI passed, including Laravel feature tests, dashboard integration, Android debug/release builds and lint, source checks, and clean PostgreSQL migration replay. Security workflow (CodeQL Web/Actions and Android, repository guardrails), RPC grant contract, Render smoke, frontend review, and deterministic automated code review all passed. Automated review reported 0 findings. CodeRabbit did not complete a substantive incremental review.
- **Free-tier design:** [FCM Supabase Cron trigger guide](docs/FCM_SUPABASE_CRON_TRIGGER.md) documents Supabase Vault + pg_cron + pg_net. Cron checks the delivery ledger every minute but contacts Render only when new, retry-due, or expired-lease work exists, so it does not keep the free Render service awake while idle.
- **Production configuration intentionally NOT applied:** read-only verification found `pgcrypto` installed but `pg_cron` and `pg_net` absent. No Vault secrets, `FCM_DISPATCH_WEBHOOK_SECRET` Render variable, Cron job, live notification, or database migration was created/applied. The live due-delivery-pair query currently returns 0.
- **Next gate:** after operator authorization and test-device readiness, add the same high-entropy HMAC secret to Render and Supabase Vault, enable pg_cron/pg_net, create the conditional job, then verify real FCM delivery with a dedicated test account/device. Keep the in-app inbox authoritative; FCM is best-effort only.
- **Additional Android delivery limitation:** the sender currently uses data-only FCM messages with default normal priority; Firebase notes these may be delayed during Doze. Do not switch to auto-rendered notifications because the Android client validates the recipient profile before display. Review [FCM_SETUP.md](docs/FCM_SETUP.md) before changing priority; notification-permission/token reconciliation and real Doze/device testing are required first.

---

# Current handoff — 2026-11-15: responder incident claiming & security settings completion

- **Objective:** Complete the responder incident claiming workflow, password recovery, theme switcher, privacy notice (RA 10173), step-up security toggle, and emergency help guide across the TACResQ app.
- **Branch:** `main` (commit `27ac1312`).
- **Implementations Completed & Verified:**
  - **Responder Incident Claiming:** `20261115120000_responder_incident_claiming.sql`, `api_claim_incident` atomic FOR UPDATE locking, `api_set_incident_claimable`, `api_claimable_incidents`, `Models.kt`, `TacApi.kt`, `SupabaseTacApi.kt`, `FakeTacApi.kt`, `TacRepository.kt`, `ResponderScreen.kt`, and `DispatcherScreen.kt`.
  - **Password Recovery Request:** Provider-backed password recovery request dialog in `AuthScreen.kt` with non-enumerating feedback.
  - **App Appearance & Theme Switcher:** `ThemeMode` selector (`SYSTEM`, `LIGHT`, `DARK`) in `Theme.kt` and `AppRoot.kt` with live theme toggling.
  - **Privacy Policy Notice (RA 10173 & TACRU Standards):** In-app modal privacy notice in `AppRoot.kt` detailing controller identity, data collection scope, lawful bases, and data subject rights under RA 10173 / RA 10121.
  - **Step-Up Security & Account Deletion:** Interactive step-up security toggle switch and Google Play compliant account deletion request dialog.
  - **Emergency Help & Operation Guide:** Interactive emergency help and system usage guide dialog.
- **Actual Validation Results (100% passing):**
  - Android Gradle build: `:app:assembleDebug` and `:app:assembleRelease` passed cleanly.
  - Android JVM unit tests: `:app:testDebugUnitTest` passed with 110 passing tests (0 failures).
  - Android debug lint: `:app:lintDebug` passed with 0 errors.
  - Python test suite: All 82 contract test scripts passed (including `check_android_source.py`, `test_responder_incident_claiming.py`, `test_workflow_security.py`).
- **Next Concrete Implementation Task:** Conduct physical-device QA verification on hardware/emulators for notification delivery, GPS accuracy, and background location tracking.

---

# Current handoff — 2026-11-13: live notification lookup and migration-history reconciliation

- **Live Supabase migration ledger:** Read-only verification confirms 30 applied migrations. The newer applied versions now include `20261105235009_persistent_in_app_notifications`, `20261105235016_fcm_delivery_attempts`, `20261107234752_notification_realtime_broadcast`, `20261108092044_incident_scoped_chat`, `20261108092648_incident_message_rate_limit_index`, `20261108092703_incident_chat_image_attachments`, and `20261112170112_add_my_notification_lookup`.
- **Repository alignment:** The corresponding seven migration files are renamed in this branch to those exact database version keys. SQL content is preserved; only filenames and the alignment contract are changed. The migration-history test now classifies 30 applied files and 9 unapplied files.
- **Notification lookup RPC:** `public.api_my_notification(bigint)` exists in the live database. Its body requires a signed-in profile and filters by both notification ID and recipient profile. Live ACL verification: `anon` cannot execute; `authenticated` can execute; direct `SELECT` on `public.user_notifications` is denied to both `anon` and `authenticated`. Function search_path is fixed to `pg_catalog`. The disposable PostgreSQL regression test verifies owner access, cross-profile non-disclosure, and no direct table access.
- **Advisor review after migration:** Health advisor returned no findings. Security advisor still reports 5 RLS-enabled/no-policy tables, 11 authenticated-callable SECURITY DEFINER RPCs (including this intentionally caller-scoped lookup), and leaked-password protection disabled. The five no-policy tables explicitly deny SELECT to `anon` and `authenticated`; their ACLs are limited to `postgres` and `service_role`. Performance advisor reports 4 unindexed foreign keys, 6 RLS init-plan warnings, and 9 unused-index candidates. No unrelated grants, policies, indexes, password settings, or paid-plan features were changed.
- **Still pending:** `20261108120000_allow_requester_pending_cancellation` is not in the live migration ledger. Do not apply it until separately reviewed and authorized. The remaining local pending migration files also remain unapplied.
- **PR #294:** `fix/notification-tap-identity-and-routing` remains a draft. The prior tested head `654c8e1c74eb5d21ba41752b113ceffc349cd019` had Android debug/release, clean PostgreSQL replay, Laravel/dashboard integration, source, RPC grant, and security checks passing; CodeQL summary was neutral because one workflow configuration was missing. Instrumentation tests were compiled but not run on a physical device/emulator. The new merge/reconciliation commit requires a fresh exact-head CI run. No device-verified crash fix is claimed.
- **Operational boundary:** No FCM delivery worker, live FCM send, Render configuration, deployment, or user/incident-row mutation was performed. The applied database change is DDL for the protected lookup RPC only.


## Verification refresh — 2026-11-13 (PR #294 head `249202d9073dfc663e5734e12825a92c6ad4353a`)

All automated checks on this exact head completed successfully:
- Source checks; Android debug and release APK builds; Android JVM unit tests; Android debug lint.
- Clean PostgreSQL replay of the Supabase migrations; Laravel/dashboard integration; RPC-grant contract.
- Repository security guardrails; CodeQL Android; CodeQL Web/GitHub Actions; static UI/workflow review; static security/correctness review; automated code review.

The Android workflow **compiled** instrumentation tests but did not execute them on an emulator or physical device. No notification tap/session-lifecycle device validation is claimed. The PR remains a draft and is not merged. Keep it open until the notification tap flow is manually verified on an Android emulator or physical device, including session isolation and process recreation; then mark ready for review and re-check the exact head before merge.

Artifacts were generated and uploaded by CI: demo-mode debug APK, Supabase-mode debug APK, and release APK. Release signing verification was skipped because signing was not configured; do not describe that APK as signed or production-ready.

---

# Current handoff — 2026-11-11: cross-layer error/fix history

- **Consolidated error/fix history:** [`docs/APP_BACKEND_SUPABASE_ERROR_FIX_HISTORY.md`](docs/APP_BACKEND_SUPABASE_ERROR_FIX_HISTORY.md) combines the requester recording/report with documented Android, Render/Laravel bridge, dashboard, Supabase, notifications/Realtime/FCM, offline, and relevant CI/build fixes. It distinguishes confirmed vs inferred causes, merged code from live deployment, and verified checks from device/live gaps. README links to it. This is comprehensive across the audited records, not a claim to capture every undocumented runtime error or every repo change.
- **Baseline:** PRs #279 (requester-local numbering/edit-coordinate tolerance and map controls), #291 (synthetic realtime reliability coverage), and this follow-up PR #292 are merged.
- **Verified cancellation root cause (read-only):** The live RPC `api_cancel_my_incident` exists, but its `UPDATE incidents SET status='cancelled'` passes through `tacresq_guard_incident_update`, whose live guard rejects requester pending-to-cancelled changes and raises the misleading “Incident is not assigned to this field unit” text. PR #292 adds a follow-up migration narrowly permitting only the owner requester, pending/unassigned incident, no assignment rows, and unchanged report/assignment fields; responder/driver status protections remain intact. Its isolated PostgreSQL replay regression exercised the cancel RPC.
- **Additional hardening:** Notification card title/body/event fields are normalized for missing/null JSON before Compose renders/routes them. Map pin artwork is reduced to 75% with its anchor padding preserved. The notification crash itself remains unconfirmed without a stack trace.
- **Chat blocker:** Read-only live schema inspection confirms `api_my_incident_messages` / `api_send_incident_message` are absent and `20261026200000_incident_scoped_chat`, `20261030130000_incident_message_rate_limit_index`, and `20261031130000_incident_chat_image_attachments` are not applied. This explains chat connection failures. No live schema or user/incident records were changed; the prior approval covered only the notification Realtime migration, not these changes.
- **PR:** [#292 — fix requester cancellation and polish emergency flow](https://github.com/RamProjector/TACResQ-School-Project/pull/292), merged at `88f63f89462b71bab617635f21f68c07c9dddd98` from exact PR head `2eb8b13baa835c947ddd3222b6e8c8706d12e2cd`.
- **Validation:** Exact-head TACResQ CI passed source checks, Android debug/release builds and JVM tests, clean PostgreSQL migration replay, and Laravel/dashboard integration; security (CodeQL and repository guardrails), frontend review, RPC grant contract, and automated code review passed. Aggregated PR status: 11 successful, 2 skipped (CodeQL neutral and CodeRabbit skipped), 0 failed, 0 pending. Focused local requester-cancellation/notification/map/edit/chat contracts, migration-history alignment, Python byte-compilation, and `git diff --check` also passed. No emulator/device test or live DB migration was performed.
- **Next:** Ask the user whether to apply the exact requester-cancellation migration and the three missing incident-chat migrations to the only TACResQ Supabase project, which may be production. Do not run them without explicit approval; no separate staging project exists. The notification crash still needs a sanitized stack trace for a definitive root-cause diagnosis.
- **Render FastAPI found:** Render lists `LifeLink-API` from `RamProjector/LifeLink` (`lifelink_fastapi/`, Python/Uvicorn, `https://lifelink-api-uzje.onrender.com`) separately from TACResQ’s Laravel/PHP service. TACResQ source points its optional bridge to `tacresq-school-project.onrender.com`; no LifeLink URL/reference was found. Release builds use direct Supabase. The services’ intended relationship is not established; no health probe or configuration changes were made.
- **Documentation update:** PR #293 (`agent/handoff-requester-cancellation`) contains the consolidated cross-layer history, README index link, and this handoff. Commit `acf771f17fd0ffad564108350af15ef79f00b111` adds the deeper notification-tap audit to `docs/APP_BACKEND_SUPABASE_ERROR_FIX_HISTORY.md`: FCM payload field loss, PendingIntent identity, `NEW_TASK | CLEAR_TOP` with default `standard` Activity behavior, missing MainActivity→Compose handoff, session/authorization requirements, test gaps, and acceptance criteria. This is a documentation-only commit; it does not claim the crash is fixed or alter app behavior.
- **Next implementation task:** Create a separate focused notification-tap reliability PR from the latest `main` after reviewing PR #293's final merge state. Implement stable notification identity and a lifecycle-safe, one-shot Activity-to-Compose event handoff; gate routing on session restoration and protected incident authorization; add PendingIntent/Activity/process-death/navigation regression coverage. Preserve default launch mode until task/back-stack tests justify any change. Do not mix app code into PR #293's documentation-only scope. Obtain sanitized Logcat/build/device details if the reported crash recurs. No live FCM send, Supabase mutation, deployment, or device test is authorized by this task.


# Phase-3 follow-up — 2026-11-10: synthetic realtime reliability coverage

- **Objective:** Complete the phase-3 notification realtime reliability slice while preserving the protected notification RPC and 30-second polling fallback.
- **Implementation:** PR #277 merged an OkHttp Supabase Realtime v1 client with private profile topics, authenticated `phx_join`, heartbeat, reconnect backoff, explicit connection states, and an allowlisted hint flow. Hints trigger `api_my_notifications` reconciliation; they never carry notification title/body, incident details, coordinates, or authorization decisions.
- **Synthetic coverage:** PR #291 adds JVM tests for duplicate/out-of-order hints and authoritative removal after membership revocation, plus corrected phase-3 evidence.
- **Validation:** Static phase-3, notification, FCM, and server contracts pass locally. Exact-head CI for PR #291 passed; the branch is being rebased onto newer mainline changes before merge.
- **Remaining gates:** No live Supabase migration, staging connection, or physical-device foreground/background FCM/realtime test has been run. These are the only remaining phase-3 gates. Do not add responder-location streaming in this PR.

---

# Previous handoff — 2026-11-07: implement authenticated notification realtime

- **Objective:** Add a profile-scoped Supabase Realtime broadcast signal for notification hints while keeping the protected notification RPC and 30-second polling fallback authoritative.
- **Implementation:** Added an OkHttp Supabase Realtime v1 client with authenticated private-topic joins, heartbeat, reconnect backoff, lifecycle states, and an allowlisted hint flow. Hints reconcile through `api_my_notifications`; they do not carry notification content or incident/location data. Demo mode uses a no-op transport.
- **Backend migration:** Added `20261107160000_notification_realtime_broadcast.sql` for a profile-scoped private topic and minimized payload. It was not applied to live Supabase in that task.
- **Branch/status:** `agent/phase3-notification-realtime`; exact CI and staging/device verification were pending at task handoff.
- **Validation:** Local Android JVM/build could not run because the sandbox lacked an Android SDK. Follow-up validation is recorded in this current task as read-only checks only.

---

# Previous handoff — 2026-11-07: make requester offline status recovery privacy-safe

- **Objective:** Continue PR #273's requester offline-recovery work, fix review findings without changing SOS dispatch semantics, and preserve the prototype boundary.
- **PR/branch baseline:** PR [#273](https://github.com/RamProjector/TACResQ-School-Project/pull/273), `feat/requester-offline-confirmed-cache`, base commit `4c8ec21a9e9e8ce3c3fd9618d361ce6125868270`; open and clean at task start.
- **Implemented locally:** Replace full incident cache payloads with an allowlisted ID/type/status snapshot; enforce a 24-hour freshness window and remove expired/legacy cache entries on read; clear status snapshots on sign-out while preserving unresolved SOS envelopes; await cache write before publishing a successful refresh; separate stale cache from current incidents and require successful live status before create/edit/cancel/message actions; show an explicit unavailable state when neither current nor cached status can be used. Existing pending-SOS retry behavior is unchanged.
- **Regression coverage added:** JVM tests for snapshot minimization, age policy, and current-status action gating; Android instrumentation tests for encrypted DataStore round-trip, owner isolation, and logout deletion; CI source contract plus an update to the active-emergency visual contract.
- **Validation passed locally:** `test_requester_active_incident.py`, `test_requester_active_emergency_visual_contract.py`, `test_requester_feedback_hierarchy.py`, `test_durable_sos_idempotency.py`, `test_pending_sos_verification_state.py`, and `test_requester_offline_cache.py`; CI YAML parsed; Python checks compiled; `git diff --check` clean.
- **Validation not run:** Android JVM tests, debug lint, and instrumentation execution cannot run locally because this sandbox has no Android SDK (`SDK location not found`). The existing CI workflow builds Android and runs JVM tests and compiles the instrumentation APK; exact-head results must be checked after push. No emulator or physical-device test was run.
- **Next:** Review the full patch, commit and push to PR #273, inspect the exact new head's CI and review threads, fix any failures, then merge only if GitHub reports it eligible and no critical finding remains.
---

# Previous handoff — 2026-11-06: remove Admin-specific Android UI

- **Objective:** Remove Admin UI and operational functions from Android only; preserve the web Admin console and protected server-side Admin authorization/tools.
- **Verified baseline:** `agent/remove-admin-mobile-ui` was created from latest `origin/main` at `676179290d2a5c6b5c4f3ce9d6a259a70e161813` (PR #266 merged); no open PRs were listed before this follow-up.
- **Prior result:** PR #266 removed the Android Admin console and Admin RPC/repository APIs but retained an Admin-specific blocked screen. User clarified the scope is Android only and asked that Admin UI/functions be removed from the app.
- **Follow-up implementation:** Removed the dedicated blocked screen and remaining Admin-specific UI copy, including signup and map text. Admin-role sessions are automatically signed out using generic sign-out/sign-in messaging before operational ViewModels or push registration can run. Role decoding and fail-closed guards remain for safe denial.
- **Preserved:** Protected Supabase Admin RPCs/grants, web Admin console, and server role model. No backend, web, live-data, or production-configuration changes.
- **Validation passed locally:** Admin role-safety (12 checks), Admin mobile/server/web preservation (8 checks), FCM, navigation, GPS map role presentation/coordinates, requester/provisioning, Android source integrity, build-mode, Render bridge contracts, and `git diff HEAD --check`.
- **Build/device status:** Android Gradle compilation is not available in this sandbox because its Android SDK is missing (previous `SDK location not found`). Current follow-up CI must run Android debug/release builds and unit tests. No device/emulator test has been run.
- **Branch/PR:** `agent/remove-admin-mobile-ui`; implementation commit `767f44511ff34c1b03c2ea975f52477ab3ec5a94`; PR [#267](https://github.com/RamProjector/TACResQ-School-Project/pull/267) is open.
- **Next:** Refresh PR #267, inspect every workflow on its current exact head, and merge only if required checks pass and GitHub reports it mergeable.
---

# Previous handoff — 2026-11-05: failed one-off Geoapify Routing API test and cleanup

- **Authorization:** User explicitly approved one live regular-Routing API call with fixed synthetic coordinates and temporary diagnostic deployment, followed by cleanup. No real responder/incident coordinates were used; no second Geoapify request is authorized or made.
- **Diagnostic:** PR #261 passed exact-head CI (12 passed, 2 skipped) and merged as `c838789bae48480f4fd41c2019debd8d04c2b224`. Render deployed it. The gate used a fresh temporary `GEOAPIFY_SMOKE_TEST_TOKEN`, no caller data, fixed waypoints, and the regular EU `GET /v1/routing` endpoint.
- **Live result:** Exactly one bodyless POST was made. Render returned sanitized HTTP 502: `Geoapify rejected the Routing API request or configured key.` This means the upstream response was in the 400/401/403 class; it does not prove whether the key itself is invalid or some request parameter was rejected. No raw response, key, or URL was inspected. No retry was made.
- **Cleanup in progress:** `GEOAPIFY_SMOKE_TEST_TOKEN` was set to empty only (the existing `GEOAPIFY_API_KEY` was neither read nor changed). Token-clear deploy `dep-dblmi7oae00c73cbtkq0` was in progress at last status check. Local temporary token/curl config were deleted. Branch `agent/cleanup-geoapify-routing-smoke` reverts the temporary diagnostic changes; next: verify token-clear deploy is live and endpoint returns 404, open cleanup PR, pass CI, merge, and confirm Render auto-deploy removed the temporary Routing API diagnostic. No Render deploy after cleanup will test Geoapify.
- **Operational status:** PR #260 remains merged and Render's regular-route provider integration is live, but Geoapify Routing API success is unverified. Do not enable Android routing or claim working route calculation until a separately approved and corrected test succeeds. Do not inspect or change `GEOAPIFY_API_KEY`.

---

# Previous handoff — 2026-11-03: Geoapify provider selection

---

# Previous handoff — 2026-11-04: Route Planner smoke-test result

- **Objective and outcome:** Correct the gated Geoapify diagnostic to exercise the Route Planner API, then make exactly one approved synthetic request (maximum 4 credits). PR #258, [Fix Geoapify Route Planner smoke test](https://github.com/RamProjector/TACResQ-School-Project/pull/258), merged at `5cb4385f7f01ce2328bce9ed69c0b2ae92fe511d`; feature head was `08b1897100e9d911802f8faa1858dc70b40e5967`.
- **Implementation:** The smoke endpoint now sends `POST https://api.geoapify.com/v1/routeplanner` with a fixed JSON plan containing one synthetic agent start and one synthetic job location in GeoJSON `[longitude, latitude]` order. It accepts no caller data, requires a separate temporary token, suppresses upstream details, and returns only sanitized status/counts. Operational `/api/route-preview` remains private-OSRM-only.
- **Validation:** PR #258 exact-head CI completed with 12 checks passed, 2 skipped, 0 failed, and 0 pending; the Laravel/database integration check passed. Local `python3 tools/test_location_contract.py`, `python3 tools/check_android_source.py`, and `git diff --check` passed. Local PHP, Composer, and Docker were unavailable, so Laravel tests ran in CI rather than this sandbox.
- **Live test:** Render deployed merged commit `5cb4385` (`dep-dblkpabbc2fs739sam8g`). After verifying the gate returned 404, one bodyless request to the live gated endpoint used only the fixed two-location synthetic plan. It returned sanitized HTTP 502: `Geoapify returned an invalid Route Planner result.` This code path means the upstream response was success-class HTTP, but the validator did not find a valid nonempty `FeatureCollection` with a `type=job` action; the precise raw response was intentionally not retained or exposed. This is evidence of an HTTP-level response, not confirmation that the requested job was routable or that the complete production plan works. No retry was made. Actual account credit deduction was not independently read.
- **Cleanup/security:** Only the temporary `GEOAPIFY_SMOKE_TEST_TOKEN` was added, then cleared; cleanup deploy `dep-dblkr26tlv9c73c1kvl0` reached live. A no-token POST returned 404 afterward. The local token file is absent. The `GEOAPIFY_API_KEY` value was never read or changed.
- **Next concrete task:** Diagnose why this minimal Route Planner result did not satisfy the expected assigned-job schema. Review the request/response contract and, if needed, improve sanitized diagnostics without exposing geometry or credentials. Do not make another Geoapify request until the user explicitly authorizes an additional test (up to 4 credits). No operational Geoapify routing or Android integration is enabled.

---

# Previous handoff — 2026-11-01: in-app route preview and responder GPS continuity

---

# Active handoff — 2026-10-31: clarify dispatch team assignment versus field leadership

- **Objective:** Implement the safe portion of the proposed team-based workflow without inventing TACRU command authority.
- **Verified existing capabilities:** The repository already supports atomic multi-responder assignment (up to five eligible responders/drivers), independent per-assignment status transitions with timestamps, a conservative incident-level aggregate, dispatcher member-detail disclosure, and incident chat. These are existing capabilities, not new work in this branch.
- **Implementation:** The dispatcher assignment dialog now explicitly says that selecting responders assigns them to one incident response group and does not appoint a team leader. Product scope now defines that a dispatch team is currently an incident-level grouping, not a standing roster; the legacy `assigned_to` field must not be treated as proof of leadership; individual status RPCs remain authoritative; chat is not the status source of truth.
- **Leadership boundary:** No new account role, leader flag, leader-specific permission, or dispatcher appointment feature was added. TACRU's authorized SOP and qualification/appointment rules are not present in the repository; adding a leader designation before confirming them would invent chain-of-command authority.
- **Regression coverage:** Extended `tools/test_dispatch_team_assignment.py` to require the no-leader-appointment explanation.
- **Branch:** `agent/clarify-dispatch-team-leadership`, based on main commit `c78d6adc226e854cbc20ea2eca41cda8342aaed1`.
- **Commits:** `413147b7a47b25fbe758023f229577539f3a4107` (dispatcher copy), `cdbcb6991e3f07bda8dba917e61e1c4be3661eaf` (contract), `30cbb06e7ce04b5c9d8a51472c23b36bdcf86cc1` (scope).
- **Validation:** Not yet run; PR CI must validate the exact head. No local checkout/build, emulator, live Supabase migration, live data mutation, or deployment was performed.
- **Research update (2026-10-31):** Tacloban CDRRM Plan 2016–2022 says CDRRMO exercises direction/control over city-to-barangay emergency operations and lists revision of TACRU emergency-response guidelines as an action, but does not expose the leader appointment rule. RA 10121 assigns LDRRMOs responsibility to organize, train, and directly supervise local emergency response teams. Philippine ICS FOG recognizes defined Task Force/Strike Team Leader and Single Resource Boss roles, with reporting through chain of command. Tacloban 2023 Annual Report confirms CDRRMO monitoring/response activity but no TACRU appointment policy. Sources and implementation guardrails are recorded in `docs/PRODUCT_SCOPE.md`.
- **Conclusion:** Strong basis for a scoped field-lead designation, but no public source found establishes TACRU-specific appointing authority, qualification criteria, or leader permissions. Keep unrestricted leader assignment out until the current approved TACRU guidelines / CDRRMO Manual of Operations are obtained.
- **Next:** Open a focused PR, inspect exact-head required checks and review findings, fix failures, and merge only if all required checks pass and GitHub confirms mergeability. Formal team-leader functionality remains blocked on an authoritative TACRU SOP.

---

# Active handoff — 2026-10-31: dispatcher status clarity and PR #215 CI repair

- **PR:** #215, branch `fix/map-fullscreen-ui-consistency`; still open and unmerged.
- **Status summary wording:** changed the dispatcher count to “members with a status update.” The count is based on each assigned member's status being different from `ASSIGNED`; it must not be described as a live count of people currently travelling or physically present.
- **Individual status freshness:** expanded member details now show each assignment's `status_updated_at` in the device's local time zone using the existing `formatTime` helper. Missing timestamps are explicitly labeled “Update time unavailable”; no arbitrary stale threshold or automatic status transition was introduced.
- **PR #215 compile repair:** inspected the failing Android build log and fixed missing Compose Material3 `Icon`/`IconButton` and Material `Icons.Filled.Notifications` imports in `Components.kt`.
- **Regression contract repairs:** updated the GPS source check to recognize the responder/driver logout wrapper while still requiring requester, responder/driver, and dispatcher map wiring; updated the notifications contract for the notification-aware `ScreenHeader` signature; extended per-responder status contract for the timestamp UI.
- **Commits:** `e31c1e37c3b8298da8fc6854fa28f78959b42d31`, `92d82c39832670a88cd479ca67bff5604e65ad34`, `202812cfefd33a172f1abf190eca04ca7c789e1e`, `a7d6beb38434aea35001141e842a2008448d8a76`, `65336d4cbe51fb31dfab27eb35b0a25da3dad4bc`, `b3265fcaffd374d8b8995502a2eec2427aa0ad95`, `007bebac9c076988df73654362e1db9079df4fd9`, `6eb05e619009a03453230f426d8273c6e8825865`, `d8615892fb772d6ff3ff2775bcb0113b09eea394`.
- **Validation:** inspected previous CI failure logs; Android compilation failed because of the missing notification icon imports. The GPS emulator job also logged an SDK command-line-tools discovery failure, but the shown Android build failure was specifically Kotlin unresolved references. Latest head is `d8615892fb772d6ff3ff2775bcb0113b09eea394`; workflow/status APIs returned no fresh runs/statuses at handoff time. Do not claim latest-head CI passes, do not merge, and do not publish an APK until the exact head builds.
- **Workflow note:** the NIMS-informed team view remains an overview of individual resource records. Do not invent a team-leader role or change server-side status authority without reviewing authorization, transition logic, and auditability first.

---

# Active handoff — 2026-10-31: responder sign-out safety and dispatcher team summary

- **PR:** #215, branch `fix/map-fullscreen-ui-consistency`; remains open and unmerged.
- **Responder sign-out regression fix:** the responder/driver role now wraps the Profile/chat sign-out callback to stop that user's foreground location flow before calling repository logout. Updated `tools/test_location_contract.py` to assert the actual AppRoot wiring rather than expecting a now-removed header callback inside ResponderScreen.
- **Dispatcher team-status UX:** active incident cards now show an overall team status and a compact count of assigned members who have started responding. Individual member status rows are collapsed behind an explicit “View member details” action and can be hidden again. This preserves individual accountability while reducing the five-row status wall in the default queue view.
- **Regression coverage:** extended `tools/test_per_responder_assignment_status.py` for the collapsed-by-default team summary and detail disclosure.
- **Commits:** `f471ee01576c406d092d05cd584904b6c8248237` (sign-out safety); `bf3544b03af0a10fd391cdc6001b338c083d179f` (sign-out contract); `70654357b2c7ecf791d1b04ed5d1b8003d71d0ea` (team summary UI); `dd545995daa597ed08d347962e75c19e5772fa22` (team summary contract).
- **Validation:** changes were committed through GitHub; no local Android build or emulator run was performed. Previous exact-head CI on `8a9b42f4...` failed in Android build/source checks and CodeQL Android. The new commits trigger fresh CI; do not claim success until the latest head's checks finish.
- **Next:** inspect exact-head CI failures, resolve them without weakening checks, then continue with a focused responder/dispatcher workflow review. Do not merge until all required checks pass.

---

# Active handoff — 2026-10-30: GPS permission-denial recovery

- **Verified main at task start:** `b6fa737230f7e69de8273ba4652088026e2e9f7a` (PR #206 merged; external map intent recovery).
- **Current branch:** `agent/gps-permission-settings-recovery`.
- **Implementation:** `GpsMapScreen.kt` now exposes an explicit “Open TACResQ app settings” action after location permission denial or a permission failure. It opens this app's Android details settings page, provides manual navigation instructions if Android cannot open it, and hides the recovery action after permission is granted. The incident map remains usable; this does not request background location or broaden manifest permissions.
- **Regression coverage:** added `tools/test_gps_permission_settings_recovery.py` and wired it into `TACResQ CI` source checks.
- **Commits on branch:** UI fix `5deef1e81f552dc3f44bcc0496a7f3d48eb06e5a`; test `7d4a4718c8fca59760fa737af552a9a9bc32884e`; CI `085dff5374be186532960335b4b134ab4833a2b4`.
- **Validation:** not yet run locally; PR CI must execute the new contract and full required builds/integration checks. No emulator or physical-device verification was performed.
- **PR:** #207 — https://github.com/RamProjector/TACResQ-School-Project/pull/207 (open; head `63a4292c2707d654ef8794018fed3a8ceb242a1a`). Initial combined commit status had no reported statuses yet; CI is pending/not yet visible. Do not merge until required checks appear and pass. **Next action:** monitor exact-head CI, fix failures, and merge only when all required checks pass and GitHub confirms mergeability. Then continue GPS reliability work (permission revocation/resume and location freshness), followed by the auth-authority blocker for Admin.
- **Limitations:** no live backend, deployment, device permission flow, or production data was touched.

---

# Active handoff — 2026-10-29: external map intent recovery

- **Latest verified main:** `8046540f70e05886938fb08577d69d96f53a3d14`. PR #205 documentation handoff was merged at this commit after all PR checks passed.
- **Fresh main CI:** TACResQ CI and RPC grant contract both passed for merged GPS hardening/icon commit `12cd2894a85aa7c93b05eac8582e47576c7e18c7`. PR #205 source checks, Android debug/release builds, emulator smoke tests, prototype navigation/function tests, backend integration, RPC grant contract, and static review all passed. Automated reviewer reported zero configured pattern findings; CodeRabbit automatic review was skipped due repository eligibility.
- **Completed:** PR #201 GPS RPC security hardening and PR #202 emergency GPS launcher icon are merged. No live Supabase migration or deployment was performed.
- **Current implementation:** PR #206 `fix(android): recover from unavailable external map handlers`, branch `agent/maps-intent-fallback-recovery`, head `d5352da41b7d057ea8cfecb9902e369bf22850ba`. It treats both ActivityNotFoundException and SecurityException as recoverable when launching turn-by-turn navigation, tries the generic geo intent after Google Maps explicit launch fails, and displays an error only if both handlers fail. Adds a source regression test wired into CI. **PR checks pending at handoff.**
- **Known automation issue:** OpenCode run `40905524704` failed during GitHub App token exchange with HTTP 502 before model execution. A later comment-triggered run was skipped (not an agent execution). Do not claim OpenCode completed a code task until a run actually reaches model execution and opens a PR.
- **Next action:** verify PR #206 exact-head CI and automated review; fix any failures, merge only after required checks pass. Then resume the highest-priority verified GPS/map/backend reliability issue. Admin role implementation remains gated on resolving the separate Supabase-vs-Laravel identity authority design; do not implement a frontend-only admin route.
- **Limitations:** device-specific Android intent resolution and live backend behavior remain unverified. TACResQ is an emergency-response coordination project, not an emergency service or production-ready dispatch system.

---

# Active handoff — 2026-10-29: GPS security and emergency launcher identity

- **Latest verified main before this handoff:** `12cd2894a85aa7c93b05eac8582e47576c7e18c7` (PR #201 merged as `446e71586c5ffbedeabae4bfb97f8aa8fc912832`; PR #202 merged as `12cd2894a85aa7c93b05eac8582e47576c7e18c7`).
- **Completed:** PR #201 isolates responder GPS writes behind a private, narrowly scoped helper, removes direct authenticated INSERT to `public.location_logs`, validates assignment/incident state and coordinates/timestamps, rate-limits by server receipt time, and restricts active GPS reads to dispatchers for en-route assigned units. Added isolated PostgreSQL runtime privilege/authorization tests and migration replay coverage.
- **Completed:** PR #202 updates the launcher identity to a distinctive emergency GPS pin/beacon/route vector icon. No blood-donation imagery or blood-matching behavior was introduced.
- **Validation evidence:** PR #201 head `8ff403ba885aaa57db69ff3d1f004cc6eebf1ff4` had successful check-runs for Android debug/release builds, source checks, emulator smoke tests, prototype navigation/screenshots, Laravel/dashboard integration, migration replay, RPC grant contract, and static security/correctness review. PR #202 head `14b9ca7642ed2b179f3d1f8ba742f0a2f94080e7` had the same set successful. CodeRabbit was skipped. These were pre-merge branch results; **fresh main CI after the merges was still queued/in progress at handoff time** (runs `40907186698`, `40907186533`, `40907233500`, `40907233536`).
- **OpenCode blocker:** workflow run `40905524704` failed at GitHub App token exchange with HTTP 502. Checkout and OpenCode installation succeeded; no model response or code task ran. This is not evidence of an invalid `OPENCODE_API_KEY`. Retry once; if repeated, investigate the action/App token exchange path before altering repository permissions or credentials.
- **Important limitation:** these merges do not mean the live Supabase project was migrated or the app is production-ready. Migrations were validated in disposable CI PostgreSQL only; live deployment/device behavior remains unverified.
- **Next concrete action:** verify fresh main CI for `12cd2894a85aa7c93b05eac8582e47576c7e18c7`; then inspect remaining open PRs against latest main and choose the next highest-priority non-duplicate security/reliability task. Keep TACResQ scoped to emergency response and GPS.

---

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

- **Intended product:** TACResQ is being developed as a real emergency-response service requested by TACRU, not merely as a school-project demo. The engineering target is a polished, reliable Android client backed by Supabase, with dispatch operations centralized in Android and a separate administrator-only web portal.
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


## Handoff — 2026-10-29: OpenCode GitHub Actions agent setup

- **Objective:** Add an owner-triggered OpenCode GitHub Actions workflow after the user confirmed adding the `OPENCODE_API_KEY` repository secret.
- **Branch:** `agent/opencode-github-actions`.
- **Change:** Added `.github/workflows/opencode.yml` using the official `anomalyco/opencode/github@latest` action and `opencode/big-pickle`, which the current OpenCode Console model documentation lists as free.
- **Invocation:** The repository owner `RamProjector` can trigger the workflow by commenting `/oc` or `/opencode` on an issue/PR or PR review comment. Other actors are filtered out to reduce abuse and model usage.
- **Credential handling:** Workflow reads `secrets.OPENCODE_API_KEY`; no credential value is stored in repository files or logs intentionally.
- **Required external setup:** The OpenCode GitHub App must also be installed for this repository. This workflow file alone cannot install the app or verify the secret's existence/value.
- **Validation:** Workflow syntax and a real model invocation have not yet been run in GitHub Actions. Verify the first manually triggered run and inspect the resulting PR/checks before relying on the agent.
- **Safety/limits:** The selected model is documented as free, but availability and rate limits may change. Workflow only allows the repository owner to invoke it and has a 20-minute timeout. No automatic merge or production changes are enabled.
- **Next action:** Open a PR for this workflow, then verify required checks and the first real invocation after confirming the OpenCode GitHub App installation.


## Handoff — 2026-10-31: requester incident-message discoverability

- **Objective:** Make requester messaging visible at the active-emergency point without introducing a standalone requester Chats tab.
- **Implementation:** Requester Home's active-emergency card now receives the existing incident-chat navigation callback and displays a full-width `Open incident messages` action for the active incident. The action passes that incident's ID into the existing chat route; existing request-list and notification entry points remain.
- **Research basis:** Android navigation guidance reserves bottom navigation for top-level destinations; child/detail actions should be reached contextually. Android recommends 3–5 primary destinations. See https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns.
- **Security:** No chat RPC, RLS, database schema, sender identity, message content, or notification behavior changed. Existing server-side incident authorization remains authoritative.
- **Validation:** Added source-contract assertions for requester callback wiring and active incident ID routing. CI results must be checked on the final PR head before merge. Emulator/device and live Supabase account checks are not run by this source edit.
- **Branch/PR:** `fix/map-fullscreen-ui-consistency`, PR #215. Keep PR open until exact-head required checks pass; do not claim merge.
- **Next task:** Inspect requester Notifications/Alerts placement and profile/sign-out behavior, then address the next discrete UI task without changing unrelated role workflows.


## Handoff — 2026-10-31: sign-out placement

- **Objective:** Prevent accidental logout by removing Sign out from shared screen headers and moving it to Profile.
- **Implementation:** Shared ScreenHeader no longer renders Sign out. The Profile section now presents a full-width secondary Sign out action and asks for confirmation. Confirmation explicitly states that signing out does not cancel active emergency requests.
- **Affected UI:** Shared components, requester/responder/dispatcher/chat headers, notifications header, role profile section, static notification/navigation contract test.
- **Scope guard:** Existing Alerts navigation remains in place for now. The top-right notification button is the next separate task; do not remove Alerts until notification routing and unread indication are proven.
- **Admin caveat:** AdminAccessScreen is currently a dedicated screen outside RoleShell/Profile. A role-appropriate admin account/session exit destination remains to be designed; do not silently remove admin sign-out access.
- **Validation:** Static contract assertions added. Exact-head Android CI and security checks must pass before merge. No emulator/device session-flow test or live backend operation was performed.
- **Next task:** Add a discoverable top-right notification action that routes to the existing notification inbox, then assess whether the Alerts bottom-navigation destination should be retained or retired.


## Handoff — 2026-10-31: top-right notification entry point

- **Research basis:** Android's Jetpack Compose app-bar guidance describes top app bars as the place for core actions, with actions placed at the trailing/right side; icon actions should use IconButton and a meaningful content description. https://developer.android.com/develop/ui/compose/components/app-bars
- **Implementation:** Shared ScreenHeader now accepts an optional notification callback and displays a trailing bell IconButton labelled **Open notifications**. RoleApp routes that action to the existing Alerts & safety destination. Requester, responder, driver, dispatcher, and incident-chat headers are wired to the route.
- **Validation added:** Static notification contract checks verify callback propagation; Compose instrumented test checks the action is displayed with its accessibility description and invokes the callback.
- **Compatibility:** Existing Alerts bottom-navigation entry remains temporarily as a duplicate route to the same inbox. Do not remove it until device testing confirms the header action is discoverable and the navigation change does not strand notification access.
- **Admin caveat:** AdminAccessScreen is separate from RoleShell and does not yet expose this shared header action.
- **No backend changes:** Notification RPCs, polling, read state, RLS, push delivery, and notification content are unchanged.
- **Next:** Run exact-head CI, then inspect the admin role's profile/session UX and consider badge/unread count only after verifying refresh cadence and performance.


# Active handoff — 2026-10-31: compact requester review action

- **Task:** Prevent the requester location-step primary action from wrapping/clipping on narrow displays.
- **Research basis:** Android navigation/layout guidance supports keeping actions concise and accessible; Android touch-target guidance recommends at least 48dp. Official sources: https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns and https://developer.android.com/guide/topics/ui/accessibility/views/apps-views.
- **Implementation:** Shortened the step action label from `Review request` to `Review`, forced a single line, and preserved the existing 52dp button height, weighted layout, validation behavior, and step transition.
- **Regression coverage:** Added source contract assertions for the one-line label and prevention of the old wrapping label.
- **Scope:** No request payload, validation, SOS submission, location handling, or backend behavior changed.
- **Validation:** CI must verify this exact PR head. Emulator/narrow-device visual check not run.


# Active handoff — 2026-10-31: notification access from every incident-chat role

- **Finding:** Requester incident-chat headers already exposed the notification bell, but responder/driver and dispatcher chat routes did not pass the shared navigation callback.
- **Implementation:** Wired the existing notification destination callback into both remaining role-specific incident-chat routes. No new screen or notification behavior was introduced.
- **Research basis:** Android top app bars host core contextual actions, and notification taps/actions should take users to the directly relevant destination. Official guidance: https://developer.android.com/develop/ui/compose/components/app-bars and https://developer.android.com/design/ui/mobile/guides/home-screen/notifications.
- **Regression coverage:** Static notification contract now requires all three incident-chat route groups to provide the callback.
- **Validation:** Await exact-head CI. No device visual validation or live notification delivery test performed.

# Active handoff — 2026-11-05: FCM client registration foundation
- **Objective:** Connect the existing authenticated Supabase FCM-token RPCs to Android system-notification plumbing without committing Firebase credentials or sensitive notification content.
- **Implementation:** Added the Firebase Messaging dependency, `TacPushApi`/Supabase RPC calls, repository registration methods, `PushTokenCoordinator`, token-refresh service, Android notification channel, `POST_NOTIFICATIONS` guard, manifest service registration, and explicit sign-out token removal.
- **Privacy/security:** Tokens remain in app-private preferences and are never logged. Push alerts are generic; incident details are fetched only after the user opens the authenticated app. Existing RLS/authenticated RPC ownership remains authoritative.
- **Configuration:** Added `docs/FCM_SETUP.md`. Firebase project configuration and a trusted server-side FCM sender are intentionally deployment work; no service account or production credential is in source.
- **Validation:** `python3 tools/test_fcm_registration_contract.py` is the offline source contract. Android compilation was attempted but this sandbox has no Android SDK (`ANDROID_HOME`/`sdk.dir` unavailable); no device or end-to-end FCM delivery test was performed.
- **Next action:** CI/device-test registration, Android 13 permission grant/denial, token rotation, and sign-out removal. Then implement the server-side sender against the existing notification event path with minimal, non-sensitive data payloads.

# Active handoff — 2026-11-05: FCM research-backed CI and permission follow-up
- **Research findings:** Firebase's Android guidance requires a runtime `POST_NOTIFICATIONS` grant on Android 13+ and recommends handling token changes because tokens can rotate after restore, reinstall, app-data clearing, or security events. Firebase HTTP v1 delivery requires trusted server-side credentials/application-default credentials and the `firebase.messaging` scope; these must never be placed in the Android app.
- **Implementation follow-up:** AppRoot now requests notification permission once after an authenticated session is available. The FCM service has a narrowly scoped lint suppression after its explicit permission check. The pre-existing incident-chat source contract was made robust to the new `TacPushApi` interface instead of relying on a contiguous interface-string assertion.
- **Validation:** All local source, FCM, Android-source, RPC-grant, and diff checks pass. PR #264's first run had only the brittle chat assertion and notification lint failure; fixes are pushed in `e39916354915e18bd5b1f615a9caa511dd704f23`.
- **Next:** Verify the new exact-head PR checks. Do not merge until source/build checks pass. After merge, the next implementation slice is the trusted server-side sender, contingent on an approved Firebase project credential/ADC deployment path; no live push test is authorized from this sandbox.

# Active handoff — 2026-11-06: trusted server-side FCM sender foundation
- **Objective:** Start Firebase HTTP v1 delivery using ADC without placing credentials in the repository or Android app.
- **Implementation:** Added `App\\FcmHttpV1Client` using `google/auth` `ApplicationDefaultCredentials` and the Firebase Messaging OAuth scope; added `FcmNotificationDispatcher` and `php artisan tacresq:dispatch-fcm`; reads unread Supabase inbox events and registered tokens with a server-only service-role key, sends minimal data-only messages, records an idempotent per-notification/token ledger, retries stale claims after 10 minutes, and removes invalid tokens reported by FCM. Added the pending `fcm_delivery_attempts` migration, private config examples, Docker dependency, CI contract, and deployment documentation.
- **Security boundaries:** No public dispatch route, no service-account file, token, privileged key, incident narrative, coordinate, or phone data added. The sender prints aggregate counts only. Delivery remains disabled until deployment supplies `SUPABASE_SECRET_KEY`, `FCM_PROJECT_ID`, and ADC securely.
- **Validation:** `test_fcm_server_contract.py`, `test_fcm_registration_contract.py`, migration-history alignment, Render startup/bridge contracts, Android chat contract, RPC-grant contract, and `git diff --check` pass. PHP syntax, Docker image build, live Supabase migration, ADC authentication, and live Firebase delivery were not run because PHP/Docker/credentials are unavailable in the sandbox.
- **Next:** Review exact-head CI, then merge only after required checks pass. Separately authorize and configure the pending Supabase migration plus a private scheduler/worker; do not claim live delivery until a synthetic non-sensitive device test passes.


# Active handoff — 2026-11-07: FCM operational hardening and documentation alignment
- **Objective:** Reduce rollout risk after merging the FCM client and trusted server sender.
- **Implementation:** Added `php artisan tacresq:dispatch-fcm --check-config`, which validates Supabase/Firebase configuration and ADC token minting without reading notifications or sending a push. It never prints credentials or OAuth tokens.
- **Documentation:** Updated `README.md`, `backend/README.md`, `docs/COMPLETION_TRACKER.md`, `docs/PRODUCT_SCOPE.md`, `docs/SUPABASE_BACKEND.md`, `docs/FCM_SETUP.md`, `docs/FCM_PUSH_DELIVERY_SECURITY_GATE.md`, and `docs/APP_FEATURE_GAPS_AND_ROADMAP.md` so FCM is described as implemented but not live/device-validated.
- **Validation:** `test_fcm_server_contract.py`, `test_fcm_registration_contract.py`, `test_migration_history_alignment.py`, `check_android_source.py`, stale-claim scan, and `git diff --check` pass. PHP/Android compilation and live Render execution remain unavailable in this sandbox.
- **Security:** No credentials, tokens, provider responses, incident narratives, or coordinates were added. The configuration check is not a public HTTP route.
- **Next:** Open a focused PR; after merge, run `php artisan tacresq:dispatch-fcm --check-config` on the private Render worker, then perform one synthetic physical-device delivery test. Do not claim push delivery from the configuration check alone.


# Active handoff — 2026-11-13: live Supabase migrations and end-to-end validation
- **Target:** TACResQ-School-Project / Supabase project `kttguamhznoflzagfutr`; branch `fix/notification-tap-identity-and-routing`; PR #294 remains draft.
- **Database:** Applied all nine previously pending migrations. Supabase ledger now contains 39 versions. Repository migration filenames and `tools/test_migration_history_alignment.py` align to those exact versions; no unapplied migration files remain.
- **Deployment detail:** The location receipt/rate-limit index is intentionally concurrent. Supabase's migration tool wraps submitted migrations in a transaction and rejected `CREATE INDEX CONCURRENTLY`; the index was created via direct SQL and verified, then the corresponding ledger row was recorded using a no-op `CREATE INDEX IF NOT EXISTS`. The repository's clean replay script executes it outside an enclosing transaction.
- **Runtime checks:** Rollback-only PostgreSQL test passed synthetic signup/requester-only role enforcement, invalid signup rejection, requester pending cancellation, responder GPS write and eight-second rate limit, dispatcher-only fresh location read, Admin page/role-change/audit path, non-admin denial, and emergency location fallback validation. Test rows/users were rolled back.
- **Live state:** Role/duty violations = 0; invalid/missing SOS location rows = 0. Four pre-existing profiles have incomplete name/phone values and remain unchanged.
- **Review:** Security advisor reports five RLS-on/no-policy tables (these intentionally block direct client table access), 14 authenticated-callable SECURITY DEFINER RPCs with auth checks/grants, and leaked-password protection disabled. Performance advisor reports four unindexed FK constraints, six RLS auth-initplan opportunities, and eight currently unused indexes; do not drop/add indexes without validating query workload.
- **CI:** After renaming migration files, stale hard-coded migration paths were updated in `test_required_request_and_admin_feedback.py`, `test_android_admin_rpc_contract.py`, `test_location_rpc_search_path.py`, `test_location_contract.py`, and `test_requester_active_incident.py`. The current exact-head CI run still needs to complete, including clean PostgreSQL replay and Laravel/dashboard integration.
- **Device/operations:** Android JVM tests and build pass on the preceding head; instrumentation tests were compiled but not executed on a device. Real FCM delivery, notification tap/process recreation, physical device/OEM tests, Render worker configuration, and signed release artifact remain rollout checks. No production Auth user, incident, or location row was changed by the synthetic test.
- **Do not merge PR #294 until all exact-head required checks pass and manual device/FCM acceptance is separately completed.**


# Active handoff — 2026-11-13: consolidated notification PRs

- **Objective:** Resolve overlapping PR #295 and #296 notification stability changes and address the review findings before merge.
- **Branch/PR:** `fix/notifications-screen-stable-lifelink-pattern`, PR #296. The combined application-code head validated below is `f15f2c51c2be0a21563b32776a58a72a6ab146f6`, based on `main` at `e1cc01d6aa14e7d4255dc3fd9e4015985a54d90b`.
- **Implementation:** Removed the unsupported `wss` assignment from OkHttp's `HttpUrl.Builder`; OkHttp keeps the supported HTTP(S) URL and upgrades it for WebSocket. Realtime startup/teardown failures are contained while coroutine cancellation propagates. A caller-verified focused notification is moved to the top and de-duplicated. PR #295's startup regression test and ViewModel fallback behavior are included, with focused-alert and URL regression checks.
- **Review response:** Addressed the PR #296 review findings about the invalid URL scheme and a focused notification remaining below the fold. The source contract was corrected to assert the cancellation-safe lifecycle wrapper.
- **Validation on application-code head `f15f2c51c2be0a21563b32776a58a72a6ab146f6`:** Source checks, Android debug/release APK builds, Android JVM unit tests and debug lint, clean PostgreSQL migration replay, Laravel/dashboard integration, repository security guardrails, CodeQL Android, CodeQL Web/GitHub Actions, static UI/workflow review, static security/correctness review, RPC-grant contract, and advisory prototype checks all passed. The top-level CodeQL summary is neutral because two scan configurations are not found on `main`; both configured language/security jobs passed. CodeRabbit was skipped because manual review is required for this OSS repository. Instrumentation tests were compiled but no physical-device execution or Logcat crash trace is available.
- **PR cleanup:** PR #295 is the overlapping fallback-only change and should be closed as superseded after PR #296's final exact-head checks pass.
- **Deployment boundary:** The TACResQ Render service auto-deploys from `main`. Merging PR #296 will deploy; wait for the user's explicit approval before merge. No live config or database changes are included.


---

# Current handoff — 2026-11-16: activity and incident lifecycle failure safety

- **Objective:** Harden the TACResQ Android activity/incident lifecycle so unexpected repository exceptions do not crash high-value workflows or strand loading/busy/freshness state.
- **Research:** Reviewed current MainActivity notification handoff, AppRoot role routing, SessionStore persistence, requester/responder/dispatcher/chat ViewModels, lifecycle-aware state collection and polling, incident state model, and existing regression contracts. Official Android guidance recommends lifecycle-aware collection/effects and catching likely exceptions inside ViewModel coroutines while rethrowing CancellationException; Kotlin documents uncaught launch exceptions as unhandled failures.
- **Changes on branch:** `agent/activity-lifecycle-failure-safety`, based on main `088e7415e8108693f8b79ca1db0b7130edc6119a`.
  - Added `RepositoryCall.kt` to normalize unexpected exceptions from Result-returning repository calls while preserving cancellation.
  - Hardened dispatcher refresh: coalesces overlapping refresh requests, releases its job slot in finally, fails assignment freshness closed, and surfaces unexpected failures.
  - Hardened dispatcher assignment: synchronously claims busy state to prevent rapid duplicate requests and safely reports unexpected repository exceptions; failed assignment confirmation invalidates local freshness.
  - Hardened requester incident refresh: safely handles unexpected network/cache failures, clears refresh job state, and marks live incident state unavailable on unexpected exceptions.
  - Hardened chat refresh, pagination, text sends, and image sends with the same cancellation-safe repository boundary.
  - Added JVM tests for exception conversion/cancellation preservation, a source regression contract, and `docs/ACTIVITY_AND_INCIDENT_LIFECYCLE_RELIABILITY.md` describing role workflows, lifecycle scenarios, and unverified behavior.
- **Validation:** Not yet run on the branch. GitHub Actions must validate the exact PR head before merge. Emulator/physical-device execution is not planned as a blocker; report as not run.
- **Production boundary:** No Supabase migrations/data, Render settings, credentials, real incident records, or FCM sends were changed. Merging to main may trigger the repository's automatic Render deployment even though these changes are Android/source/docs only. Do not merge until the user explicitly authorizes that deployment side effect.
- **Next:** Run the new Python source contract and Android JVM/build CI on the PR head; inspect the complete diff for Kotlin/test failures; then present the PR and wait for explicit approval before merge/automatic Render deploy.


---

# Follow-up hardening — 2026-11-16: chat conversation instance isolation

- **Finding:** The chat ViewModel checked only `activeIncidentId` before applying async results. If a user closed and reopened the same incident while a read/send was still in flight, the old response could pass the incident-ID check and mutate the new conversation state.
- **Fix:** Added a monotonically increasing conversation-generation token. Refresh, pagination, text-send, and image-send results now require both the incident ID and the generation captured when the operation began. Opening a different conversation and closing the current one invalidate older work. Close now cancels refresh, pagination, and send jobs and clears their references.
- **Regression contract:** Extended `tools/test_activity_lifecycle_failure_safety.py` to require four generation captures, all eight guarded result handlers, generation invalidation, and cancellation/clearing of chat jobs.
- **Documentation:** Updated `docs/ACTIVITY_AND_INCIDENT_LIFECYCLE_RELIABILITY.md` with the same-incident reopen race and required close-during-send scenarios.
- **Validation:** This changes PR #307's head. All prior CI results apply only to the previous commit; the updated exact head must complete a fresh full workflow run before it is considered validated. No merge, Render deployment, Supabase change, or FCM send was performed.


---

# Follow-up hardening — 2026-11-16: responder action serialization

- **Finding:** Responder duty/status actions and location-sharing resume checked `busy`, but published `busy = true` only after the launched coroutine began. Rapid taps could race into multiple operations. The resume path also lacked a guaranteed busy reset if a repository or tracking call threw.
- **Fix:** Responder actions now claim the busy state synchronously before launching. The shared action runner clears busy in `finally`, including cancellation and unexpected failures. Location resume verifies the current incident assignment and responder identity through the cancellation-safe repository boundary, leaves sharing off on verification failure, and always releases busy.
- **Regression contract:** Extended `tools/test_activity_lifecycle_failure_safety.py` to assert synchronous busy claims, exception-safe identity/assignment checks, and finally-based cleanup.
- **Validation:** PR #307's exact head changed again; all previous CI results are stale for this commit. A new full exact-head workflow run is required. No production deploy, Supabase mutation, Render configuration change, or FCM send was performed.


---

# Follow-up hardening — 2026-11-16: requester submission and draft recovery

- **Finding:** Requester SOS creation and pending-SOS retry/discard handled normal repository `Result` failures but could leave `sending` set if a repository call threw unexpectedly. Draft restore/save/clear and the post-submit draft clear also lacked a uniform exception boundary.
- **Fix:** Applied the cancellation-safe repository wrapper to SOS creation, pending retry/discard, and draft persistence. SOS and pending-recovery operations now release transient sending state in `finally`. The original pending-submission identity and existing no-replacement-SOS rules remain unchanged; an accepted-but-unverified SOS still explicitly tells the user not to resend it.
- **Regression contract:** Extended the source contract to cover requester create/retry/discard, draft restore/save/clear, and finally-based sending-state cleanup.
- **Validation:** Exact-head CI must run after this commit. Previous checks are not evidence for the latest head. No Supabase or Render change, live SOS, FCM send, or production deployment was performed.


---

# Validation correction — 2026-11-16: chat source-contract update

- The first CI run after the lifecycle follow-ups failed in `tools/test_android_chat_contract.py`, which still required the previous incident-ID-only callback guard.
- Updated that existing chat contract to require the new incident-ID + conversation-generation guard. The failure was a stale test expectation, not a Kotlin compiler result; Android build jobs were still running at the time.
- The latest commit is `ea6eeb5146949494f1fbe20af06eb2ccd1153cf3`. All workflows must be checked again against this exact head. Do not count earlier successful workflows or the failed source-check run as validation for the new head.


- **CI integration:** Added `tools/test_activity_lifecycle_failure_safety.py` to the Android source-check workflow so the new dispatcher/requester/responder/chat lifecycle regression contract runs on every PR, rather than existing only as an uninvoked test file.


---

# Research and audit continuation — 2026-11-17

- **Documentation:** Added [Security and Reliability Control Matrix](docs/project-memory/SECURITY_RELIABILITY_CONTROL_MATRIX.md) and indexed it in project memory. It maps OWASP API Security Top 10/ASVS/MASVS, Android location, Supabase/PostgreSQL, and FCM guidance to TACResQ source paths, confirmed gaps, acceptance tests, and limitations.
- **Exact PR refresh:** PRs #316–#321 were open, unmerged, and reported mergeable. Exact heads checked: #316 `21e8c5eb3c5013256aa9063ae3736c15c20d057e`; #317 `5ba1727f760aeb2a22f79198e2048db727af69b7`; #318 `1276b6b22473982c7781dbcfac654ff541e6f917`; #319 `02c1e7bb05b6965adae01b811b708a457264f63f`; #320 `21495bfbe7d93c87850ab9151b6cf156c1081d8f`; #321 `f44af2d88a8c822ab911612352a9cf65a114137a`. Returned TACResQ CI, Security, RPC grant contract, and Frontend Review workflows completed successfully for each exact head. PR #316's Render live smoke also completed successfully. These results do not prove live migration application or device behavior.
- **Live Supabase read-only finding:** migration ledger ends at `20261116042927_responder_incident_claiming`. It does not include the already-committed repository migration `20261122050807_pin_api_units_search_path.sql`. Live `public.api_units()` remains SECURITY DEFINER with `search_path=pg_catalog, public, auth`; repository migration narrows it to `pg_catalog`. This is a pending deployment drift issue. No live migration was applied.
- **Location trace:** `LocationProvider.kt` uses FusedLocationProviderClient; `Models.kt` GeoFix retains coordinates, accuracy, capture/elapsed timestamps, permission precision, and manual selection. The incident RPC payload sends `location_source` (gps/manual/none), not a provider identifier. Do not add a provider column until the full edit/responder-write path and source semantics are verified.
- **No production side effects:** no live schema/data/configuration change, deployment, notification send, or PR merge was performed.
- **Next action:** review PR #316–#321 for unresolved comments and current exact-head checks; then add isolated two-session concurrency coverage where missing. Keep live migrations pending absent explicit approval.


## PR #322 follow-up — 2026-11-17

- Opened [PR #322 — Document TACResQ security and reliability control matrix](https://github.com/RamProjector/TACResQ-School-Project/pull/322).
- Branch: `agent/security-reliability-control-matrix`; latest observed head before this handoff update: `6e2eefbe3f18b0a3570f2abf45326dc3dea2ab90`.
- Documentation-only scope: standards-to-source control matrix, project-memory index, and this handoff. README index line-break formatting was corrected after diff inspection.
- PR #322 workflows were observed running at the initial head `74463939626d463c0abdd50d09b011b29caec655`; they must be rechecked for the latest head.
- No local checkout/content-link validation was run. No live DB migration, deployment, or merge was performed.


## Concurrency validation update — 2026-11-17

- PR #317 now has a true two-session PostgreSQL 16 race test for one responder claiming two incidents. Latest-head clean migration replay passed and emitted both expected PASS assertions (one `claimed`, one `UNIT_BUSY`, final one assignment/one assigned incident).
- PR #319 now has a true two-session race between dispatcher claimability enable and requester cancellation. Latest-head migration replay is still running; verify the final log before marking it passed.
- Both tests run only against disposable CI PostgreSQL using synthetic accounts. No live Supabase changes.


## Latest exact-head validation — 2026-11-17

- PR #317: `84a95488872d4808a837bd7d03646b1290de8ab2`. TACResQ CI passed, including clean PostgreSQL migration replay and the cross-incident two-session race; RPC grant contract and Frontend Review passed. Security/CodeQL was still in progress at last poll.
- PR #319: `b21f8cd7d9697b7c358a25e09e342d3470950248`. Clean PostgreSQL migration replay passed, including the two-session dispatcher-toggle/requester-cancellation race; RPC grant contract and Frontend Review passed. TACResQ CI and Security were still in progress at last poll.
- Both PRs remain open/unmerged. No live Supabase migrations were applied.


## TACResQ audit continuation — 2026-11-17 (latest)

- PR #317 exact head `84a95488872d4808a837bd7d03646b1290de8ab2`: TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review passed. Open/unmerged.
- PR #319 exact head `b21f8cd7d9697b7c358a25e09e342d3470950248`: TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review passed. Open/unmerged. Runtime authenticated DML-denial/RPC-success integration check remains pending.
- PR #322 latest documentation head `d701f9f9164c8fc3056b89dee3ebaa41304c9e8f`: audit matrix refreshed with live advisor, function ACL/search-path, and RLS no-policy table grant evidence. Exact-head workflows are pending/not yet returned at the latest poll. Compare reports 12 commits ahead and 0 behind main; PR currently reports mergeable.
- Live Supabase read-only advisor recheck: health 0; security 5 RLS-enabled/no-policy tables, 18 authenticated-callable public SECURITY DEFINER functions, leaked-password protection disabled; performance 4 missing FK indexes, 6 RLS initplan warnings, 8 unused-index candidates.
- Direct privilege checks show all five RLS/no-policy tables have zero policies and no anon/authenticated table privileges. Do not add policies merely to silence the advisor.
- SECURITY DEFINER catalog/ACL review found no EXECUTE grants to PUBLIC among inspected public/private functions. `private.tacresq_is_incident_assignee(bigint)` has an unnecessary `public` search_path entry; schema-qualified body references and no confirmed exploit. `public.api_units()` remains the known live search_path drift.
- No live migration, data mutation, deployment, FCM send, or merge was performed. Continue function-by-function authorization review and plan any search_path hardening as a forward-only migration after checking repository history and dependencies.


## Authenticated RPC review continuation — 2026-11-17

- Reviewed all 18 public SECURITY DEFINER RPC definitions currently executable by `authenticated`, plus the private incident-chat authorization helper.
- Reviewed access controls include admin-role checks, requester ownership checks, recipient-scoped notification operations, chat participant authorization, bounded message/notification/admin-page inputs, message idempotency, and image MIME/path/size/object verification.
- Confirmed the current live legacy `api_admin_list_users()` still returns all profile PII without pagination to admin callers. PR #321 removes client EXECUTE and stale dashboard typing; its exact-head four workflows passed. Its clean migration replay now checks that authenticated cannot execute the legacy RPC while the bounded paginated RPC continues to work. A live PostgREST authenticated-session test remains unrun.
- Live claim RPCs still predate #317 and #319 until those migrations are applied. Do not treat passing CI as production deployment.
- `api_claimable_incidents()` is currently unpaginated; this is an operational scale concern, not a confirmed exposure, because access is restricted to authenticated on-duty responders/drivers. Add bounded paging only with queue completeness and emergency workflow tests.
- No confirmed cross-account disclosure or privilege escalation was established by static review.
- PR #317, #319, #321, and #322 remain open/unmerged; no live database migration or deployment performed.


## Location provider provenance trace — 2026-11-17

- Traced Android requester/responder location capture through `LocationProvider.kt`, `GeoFix`, Supabase RPC request DTOs, and live PostgreSQL columns.
- No provider name is currently captured or stored. `location_source` means GPS/manual/none; it is not the provider identity.
- Requester capture uses Google Play services `FusedLocationProviderClient.getCurrentLocation`; responder sharing uses fused `requestLocationUpdates`. `GeoFix`, `CreateIncidentV4Rpc`, `RecordLocationRpc`, and the database schemas omit provider metadata.
- Live `location_logs.received_at` already records server receipt time; incident `created_at` provides server row creation time.
- Recommendation: if operationally needed, store a constrained `capture_provider` diagnostic value such as `google_fused_location` or `manual_map_selection`, distinct from existing `location_source`; keep `map_data_provider` separate and only set it when that provider actually supplies a selected coordinate/address. Treat client values as untrusted diagnostics, never proof of physical presence.
- No payload or schema changes made pending confirmation of which provider distinction is required.


---

# Implementation phase closeout — 2026-11-17 12:27 PHT

## Scope and execution boundary

- The active implementation set remains PRs #316–#322. Do not add UI redesign scope, unrelated features, or replacement PRs during this closeout.
- The seven-PR integration candidate is recorded as `cef63fa713dc3488b76952791742d91ebd0cf4bf`, assembled from the reviewed PR snapshots with the shared CI workflow, migration replay harness, migration-history inventory, and project-memory index reconciled.
- The candidate record reports successful combined TACResQ CI, TACResQ Security, and RPC grant-contract workflows. It includes the six timestamp-ordered migrations, authenticated-role SQL checks, concurrency suites, Android builds/JVM checks/lint, dashboard build/type checks, and Laravel integration (77 tests / 229 assertions).
- This evidence is historical candidate evidence, not proof that a later PR head or production environment has been validated. Preserve exact-head checks after any subsequent source change and do not describe CI as device or production acceptance.

## Implementation disposition

- Review-thread refresh found no outstanding inline review threads on PRs #316–#322.
- The recorded combined candidate resolves the known cross-PR replay/CI/history-file integration concern for the snapshots it contains. Do not make speculative changes to already-integrated migrations or tests merely to create more commits.
- Keep the existing PR scopes and merge order: #316 → #317 → #318 → #319 → #320 → #321, then #322 documentation. This is a sequencing recommendation only, not merge authorization.
- Remaining items are acceptance/operational gates rather than reasons to expand the current implementation set: isolated live PostgREST verification, physical-device GPS and OEM behavior, notification/FCM delivery behavior, live routing-provider behavior, production migration planning, and operator review of leaked-password protection.
- The live `api_units()` search_path drift remains a deployment-history issue: the repository hardening migration exists, but the live migration ledger had not recorded it at the last read-only inspection. Do not apply migrations or mutate live data as part of this PR work.

## Validation sequencing instruction

- Finish and record any justified source/documentation commits across the active PRs before starting a new validation cycle.
- No tests or CI were started for this closeout commit. The next validation cycle must target the final exact heads after the implementation commit set is frozen.
- No PR merge, live Supabase migration, Render change/deployment, secret change, FCM send, or production data mutation was performed.


## GPS map collision handling — 2026-11-17

- **Finding:** Incident and dispatcher field-unit pins can render inside the same tap area. Selecting the top marker alone can make the other emergency location difficult to reach.
- **Implementation on PR #334:** Added screen-projection collision detection using MapLibre `Projection.toScreenLocation`, scaled to a 48 dp radius. When multiple marker anchors overlap the tap area, the bottom sheet presents explicit choices for each nearby incident, field unit, or device location. The tapped marker is listed first. Choosing an entry then uses the existing typed marker-ID behavior.
- **Safety/UI:** No coordinate merging or synthetic cluster count; no location sharing, permission, freshness threshold, routing, backend, or database changes. Stale field-unit choices explicitly say last-known/not-live. Chooser rows have full-width 52 dp minimum touch targets and button semantics.
- **Test additions:** Added pure JVM coverage for screen-radius boundaries and invalid points/radii. Tests were not run, per the current no-checks-after-commit instruction.
- **Research note:** MapLibre 13.x Projection returns screen-space PointF coordinates relative to the MapView; the threshold is converted from dp using display density. See `docs/GPS_MAP_INTERACTION_REVIEW.md`.
- **Remaining:** Device validation across zoom/pitch/compact layouts, marker icon footprint review, annotation redraw profiling, and a separately researched renderer/GeoJSON clustering migration. No build, tests, emulator, device, or performance checks run.

- **Follow-up safety guard:** Chooser selections are checked against the latest visible incident, current validated responder reports, or current one-time device fix before opening details. Expired/replaced entries show a refresh/retry message rather than selecting stale marker metadata.


## GPS annotation redraw invalidation — 2026-11-17

- **Finding:** The MapLibre annotation effect watched raw incident/responder collections and route result objects. Routine refreshes could clear and recreate all markers and route lines even when visible marker content was unchanged.
- **Implementation on PR #334:** Replaced raw collection/object dependencies with a render signature for incident coordinates/type/status/location source/accuracy/notes/age; responder coordinates/accuracy/freshness/age; device coordinates; and displayed route geometry. Map instance/style, selected incident, and live-navigation state remain explicit dependencies.
- **Scope:** No renderer migration, data-model change, tracking behavior change, or freshness-threshold change. No profiling or tests run; improvement is an invalidation reduction by code inspection, not a measured performance result.


## GPS renderer incremental reconciliation — 2026-11-17

- Researched MapLibre Native Android 13.7.1 marker APIs. Existing markers expose mutable position/icon/title/snippet and MapLibreMap.updateMarker; individual markers and polylines can be removed without clearing the whole map. Official references: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.annotations/-marker/index.html and https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map/index.html.
- PR #334 now reconciles incident, field-unit, and device markers by stable domain keys. Unchanged markers retain their annotation objects/IDs; changed markers update in place; removed records remove only their own marker.
- Route rendering is tracked independently and only replaced when the displayed route geometry hash changes. A full clear remains for a changed MapLibre map instance.
- This reduces work by code-path inspection; no runtime profiling or device evidence is claimed. Route hash collision risk remains theoretical and should be eliminated by exact geometry signatures if this optimization is extended.
- No clustering migration, API/provider change, database change, location-sharing change, or backend change was made. No tests/build/emulator/device checks run per user instruction.


## MapLibre clustering research — 2026-11-17

- Confirmed the current MapLibre Android 13.7.1 SDK supports native GeoJSON clustering; core Android support dates to 4.2.0. APIs include cluster enablement/radius/max zoom/min points, cluster expansion zoom, child retrieval, and leaf retrieval.
- Corrected cluster-radius interpretation: `withClusterRadius` is measured in 1/512ths of a tile in the Android API documentation, not Android dp. Do not reuse a numeric default as a dp/screen-pixel specification.
- Architecture decision: separate incident and field-unit sources; keep device preview and routes outside clusters; keep stale responder locations distinguishable from fresh/aging reports; use stable domain IDs and revalidate tapped leaves against current state.
- Cluster taps should expand by native expansion zoom, falling back to a bounded leaf chooser only when expansion cannot separate points. Preserve selected-feature state through data/style updates.
- Keep renderer clustering and legacy Annotation Plugin migration as separate changes. Current PR source change only reduces redraw churn; this research does not claim clustering is implemented.
- References: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-options/index.html ; https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-source/get-cluster-expansion-zoom.html ; https://maplibre.org/maplibre-native/android/examples/styling/circle-layer/.


## Exact renderer invalidation signatures — 2026-11-17

- Replaced route geometry hash with exact ordered coordinate pairs to avoid hash-collision false equality and stale route display.
- Removed responder icon hash from marker signatures; freshness state already fully determines the current stale/normal icon class.
- Confirmed MapLibre 13.7.1 API documentation supports the marker mutation/update and individual removal methods used by the incremental reconciler. These are legacy deprecated APIs; no claim of migration made.
- References: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.annotations/-marker/index.html ; https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map/index.html.
- No tests/build/device checks run.


## PR #334 — Dispatcher incident-list freshness cues (2026-11-17)

- Updated pending and active incident section subtitles to identify the displayed queue as the last loaded, unconfirmed snapshot while refresh is in progress or incident/unit data is stale.
- Cached incident cards remain visible. No queue, assignment, or dispatch behavior changed.
- Commit: pending at handoff; source review only. No build/tests/emulator/device validation performed.


## PR #334 — Dispatcher team progress completeness (2026-11-17)

- Team progress now uses the assigned-user roster as its denominator and counts only distinct per-member status rows belonging to that roster.
- Missing per-member status rows are identified as unavailable; no assignment or status behavior changed.
- Commit: pending at handoff; source review only. No build/tests/emulator/device validation performed.


## PR #334 CI stabilization — 2026-11-18

- Re-inspected the exact PR head and its recorded GitHub Actions results before editing. PR #334 remains open and mergeable; no production migration was applied.
- Corrected the suspicious indentation reported by Android Lint in `IncidentChatScreen.kt` around the pending image retry flag. Commit: `b19cc2a653459f9e17a4ee622277b343f9d1e820`.
- Updated the GPS empty-state source contract to match the accuracy-label prefixes actually used by the current MapLibre screen. This was a stale assertion, not a missing runtime accuracy label. Commit: `eb57c49ad7c82c5d0b2e7dbec77f5521f238f37f`.
- These are source corrections based on the recorded CI logs. No builds, tests, emulator, or device checks were run after the commits, per the existing execution constraint. The new exact-head CI result is still pending and must be inspected before calling the failures resolved.
- Next: inspect the existing duty-status and assignment schema before proposing a duty-session migration. Do not equate login or online presence with on-duty availability. Preserve dispatcher-owned incident milestones and the current active-assignment locks.

## 2026-11-18 — CI blocker repairs
- Corrected the shared assignment-release API contract: FakeTacApi and SupabaseTacApi now accept the same ReleaseIncidentAssignmentRpc body declared by TacApi; the repository sends that typed request body.
- Fixed the Compose delegated-state smart-cast error by rendering the nullable assignment-history error with a safe fallback.
- Updated the dispatcher wait-time source contract to match the intended neutral elapsed-wait display; it no longer expects the removed 15-minute escalation warning.
- Commits: e4bee7a, 342ec65, 4fbf3a3, f971a43, b903eb5.
- Validation pending: GitHub Actions must rebuild the latest branch head. No local Gradle build, emulator/device test, migration deployment, or production Supabase mutation was performed.

## 2026-11-18 — Location contract CI follow-up
- Fixed the failing responder-location source contract: the test still expected freshness/missing-marker copy in the retired web dispatcher dashboard.
- The web dashboard is intentionally admin-only; dispatch operations and responder-location freshness UI remain in the Android app. The updated contract now asserts the web role boundary rather than obsolete dispatcher-dashboard markup.
- Commit: e5823ff.
- Validation: confirmed the updated assertion matches the current Home.tsx role gate and dispatcher retirement copy. Full source-check job and Android builds must be rerun on the new head; no local Gradle build, device test, or production change was performed.

## 2026-11-18 — Closed incident assignment history
- Added a compact “Recently closed” section to the existing Android dispatcher screen, showing at most five resolved or cancelled incidents from the loaded authorized queue.
- Each closed incident exposes the existing bounded, read-only assignment-history query with loading, empty, error, and retry states. No new tab, route, assignment action, or status mutation was added.
- Extended `tools/test_assignment_history_api_contract.py` to guard the closed-incident access and five-item bound.
- Commits: a0a107d (UI), c4eeab2 (source contract).
- Validation: source-level review only; the static test and Android build have not been executed locally. The latest GitHub Actions run must confirm compilation and test results. No production migration or Supabase mutation was performed.

## 2026-11-18 — Closed-incident ordering clarity
- Corrected the dispatcher section label: the five displayed resolved/cancelled incidents are ordered by `created_at` (request time), not by closure time. The UI now calls them “Closed incidents” and explicitly says “Latest 5 by request date”.
- Updated `tools/test_assignment_history_api_contract.py` to guard the accurate label. No incident timestamps, API fields, database schema, ordering logic, or history access behavior changed.
- Commits: `df8c4c4` (UI copy), `8f84a49` (source contract).
- Validation: source review only; the static contract test and Android build have not been executed. No production Supabase or Render changes were made.

## 2026-11-18 — Assignment-history panel state
- Fixed a dispatcher UI state mismatch: the screen stores one assignment-history result in the ViewModel, but each incident card previously kept an independent expanded flag. Expanding multiple cards could leave earlier cards showing a permanent loading state while the shared result belonged to the most recently opened incident.
- Active and closed incident cards now share one expanded incident ID, so opening another timeline closes the previous panel and the visible panel always matches the single loaded history result. History remains read-only.
- Updated the source contract to guard the single-panel behavior and removed an obsolete assertion left behind by the prior closed-incident heading clarification.
- Commits: `0a169fb` (UI), `98783bd` (source contract).
- Validation: source-level review only; no test execution, Gradle build, emulator/device check, or production backend change performed.


## 2026-11-18 — Assignment history chronology
- The assignment-history endpoint intentionally fetches the newest bounded event window first. The dispatcher now reverses that retained window before rendering, so both active and closed incident timelines read chronologically from the earliest retained event to the latest.
- This changes presentation only; the incident filter, descending server query, 50-event repository bound, audit records, and authorization remain unchanged.
- Updated the source contract to guard chronological rendering in both panels.
- Validation: source-level review only; static test and Android build were not executed. No production backend or migration changes.


## 2026-11-18 — Assignment history actor labels
- Replaced raw numeric account-ID labels for other audit actors with “Another dispatcher/admin” in both active and closed incident timelines. The current operator remains “You” and system-generated events remain “System”.
- This is a presentation/privacy improvement only; the immutable event still retains actor_user_id for authorized audit retrieval. No schema, API, or authorization changes.
- Added a source contract to prevent raw actor account IDs from reappearing in the UI.
- Validation: source-level review only; contract test and Android build were not executed. No production backend changes.


## 2026-11-18 — Accurate GPS cluster totals
- Corrected the cluster chooser's total when different map sources overlap: its count now sums every hit cluster's point_count, including a smaller cluster that does not need pagination.
- Single-source cluster choosers now use the source feature's point_count directly. Paging still applies only to sources with more than 50 leaves.
- Added a source contract and CI step to guard mixed-source aggregation and single-cluster counts.
- Validation: source review only; the new contract test, Android build, and device map interaction were not run. No production backend changes.


## 2026-11-18 — GPS camera-restoration contract follow-up
- The exact-head source-check run exposed a second stale assertion in `tools/test_gps_map_fullscreen.py`: it expected incident auto-framing to ignore saved-camera state, while the current map correctly suppresses initial auto-framing when a restorable camera position exists.
- Updated the contract to require the saved-camera guard for both initial field-unit and incident framing, and to verify the selected point set. Production map behavior was not changed.
- Commit: `2da1668`.
- Validation: confirmed the failing assertion from the GitHub Actions log and compared it with the current Kotlin framing branch. The revised test and this note were re-fetched for source inspection; no local test/build/emulator/device checks were run. CI must rerun on the new head.

## 2026-11-18 — PR #334 pre-merge review follow-up

- Reconciled the notification inbox with the newer main-branch behavior: a persistent notification-load error now includes its own clearly labeled **Try again** action while retaining the single shared Refresh control. Updated tools/test_notification_action_feedback.py to require both paths and preserve transient action-error banner behavior.
- Added tools/test_dispatcher_release_assignment.sql as runtime PostgreSQL coverage for dispatcher-only release authorization, primary-member handoff, last-member protection, post-arrival release rejection, restoration of availability when no other active reservation exists, preservation of a legacy active incidents.assigned_to reservation, and actor-attributed release/closure audit events.
- Wired the runtime SQL test into tools/test_supabase_migration_replay.sh after the existing migration and authorization checks.
- Commits: 1c8e034 (notification retry UI), 6aa815b (notification retry contract), 627704e (release runtime test), f15cac4 (qualified PL/pgSQL test variable references), 4ab402f (CI wiring).
- Validation status: source was re-fetched after writes. The new PostgreSQL test has not yet run; it must be validated by the exact-head CI migration replay job. No local database execution, production Supabase migration, Render deployment, emulator, or device validation was performed.
- The PR branch remains based on the same merge-base as main; the four commits on main are being reconciled by preserving the relevant notification behavior rather than blindly merging unrelated history.

## 2026-11-18 — Release-test execution correction

- The first PostgreSQL execution of the new release runtime test caught an ambiguous PL/pgSQL reference where an unqualified incident_id column conflicted with the block variable. Qualified the assignment and audit table references with aliases; no production SQL migration was changed.
- Expanded the runtime test to invoke the public release wrapper as the authenticated database role, verify dispatcher and requester/admin assignment-history visibility under RLS, and assert that a responder is denied by the dispatcher-only function guard.
- The rerun on the corrected test source is in progress. The earlier ambiguous-reference failure is not counted as a pass; inspect the exact latest-head PostgreSQL job before treating release behavior as validated.
- Follow-up commits: e11f566 (authenticated RPC/RLS test), 99f46d0 (role-denial test independent of incident visibility), aad0861 (clarify lifecycle assertion), 80ad7cf (qualify assignment-history SQL references), 94ade35 (correct event-table aliases).


## Dispatcher release legacy-reservation regression — 2026-11-18

- **Exact-head CI finding:** The clean PostgreSQL migration replay and RPC grant workflow both failed in `tools/test_dispatcher_release_assignment.sql`: after releasing a member from the current team, the test observed `duty_status='available'` even though another active legacy incident was intended to reserve that member through `incidents.assigned_to`.
- **Trace:** The release function evaluated the legacy `assigned_to` reservation after deleting current team membership and after the primary-member handoff. The test did not independently assert that its legacy fixture still contained the expected active status and assignee before invoking release.
- **Change:** The release migration now captures whether another active legacy incident reserves the member before changing current membership, and uses that result when deciding whether to restore availability. The runtime test now asserts that the legacy fixture persisted with the intended `assigned_to` and `assigned` state before exercising release.
- **Commits:** `6d47e79` implementation; `51667ce` fixture assertion.
- **Validation:** The failure was confirmed from exact-head GitHub Actions logs. The correction has not yet been validated by a fresh CI run. The fixture assertion will distinguish a bad test setup from a release-path regression if the failure persists. No production migration, Supabase data change, Render deployment, local test, or merge was performed.


## Account recovery research trace — 2026-11-18

- Confirmed Android password recovery is intentionally unavailable today: `AuthScreen.kt` states no email is sent; `SupabaseAuthWire` has no recovery request or password-update endpoint; `AuthCallbackMessage` deliberately ignores URI query/fragment values and only displays a confirmation-return message.
- The existing `tacresq://auth/callback` intent filter provides a starting point, but a secure recovery flow must distinguish recovery from email confirmation, validate/exchange the recovery credential with Supabase Auth, handle cold-start and warm intents, avoid persisting/logging tokens, and provide a real set-new-password screen.
- Added the detailed end-to-end design and validation requirements to `docs/OPERATIONAL_IMPROVEMENT_REVIEW.md`. Do not ship a partial “email sent” state or change production Supabase Auth redirect/template settings without explicit approval.
- Admin portal review found its separate admin-only shell already has brand, admin identity, refresh, and sign-out. No real admin notification stream is currently defined, so do not add a decorative notification control. Audit actor/target fallback behavior is documented; no profile read permissions were broadened.
- Commit: `ec22ead`.
- No application behavior changed in this documentation commit. Exact-head CI was still running at the time of review; check fresh workflows before further source changes. No production configuration, deployment, local build, or device test was performed.


## Password recovery protocol decision — 2026-11-18

- Supabase Auth supports PKCE recovery and a token_hash + verifyOtp pattern, but this repository currently uses Retrofit directly and has no PKCE verifier lifecycle or recovery callback exchange.
- The existing tacresq://auth/callback is a custom scheme, not a verified Android App Link. Do not trust access_token values from arbitrary custom-scheme intents.
- Documented the required choices and security requirements in docs/OPERATIONAL_IMPROVEMENT_REVIEW.md: verified HTTPS App Link + PKCE and secure short-lived verifier persistence, or a deliberately configured/tested token_hash email template + verifyOtp flow.
- Keep the current honest unavailable message until redirect domain and Supabase email-template strategy are confirmed. No recovery code, auth configuration, or production settings were changed.
- Commit: a552e66. Documentation-only; no application behavior changed.

## GPS map async-disposal guard — 2026-11-18

- Traced the MapLibre lifecycle in GpsMapScreen.kt. Lifecycle events and camera/map-load listeners already guarded disposal, but the asynchronous setStyle callback and its nested getStyle callback could still run after the composable was disposed.
- Added disposed checks at both callback boundaries before installing cluster sources or mutating Compose/map state. This is a targeted lifecycle-race fix; no map behavior, permissions, location sharing, or routing policy was changed.
- Commit: 3039ed2. Validation pending exact-head GitHub CI; no device lifecycle test was run, so repeated navigation/background-resume behavior remains a manual verification item.

## Responder location service thread visibility — 2026-11-18

- Reviewed the explicit foreground-service start/stop path, tracking coroutine, assignment monitor, and destruction cleanup. The service already cancels both jobs and its service scope in `onDestroy()`; no duplicate cleanup or restart-policy change was needed.
- Found that `activeIncidentId` is written by service lifecycle callbacks and read by the I/O-dispatched tracking/monitor coroutines without a cross-thread visibility guarantee. Marked the nullable incident ID field `@Volatile`, matching the service's existing treatment of other cross-thread state flags/references. This ensures background loops observe start/stop changes promptly; it does not alter assignment authorization, foreground-service restart policy, or user consent.
- Commit: `85c52c1bbeac39895e30c2460e0c9af716c9f700`.
- Validation: source inspection only. No local Gradle build, unit test, emulator/device lifecycle test, production backend change, or merge was performed. Exact-head GitHub Actions must validate the updated branch.


## Responder tracking ownership guard — 2026-11-18

- Commit: `d18563d2676d18209cbf99c43bfac6bbf2f08c02` — `Guard responder tracking stops by assignment ownership`.
- A late result or exception from a cancelled tracking/assignment-monitor coroutine could call the shared stop routine after a replacement assignment had started. The stop routine now accepts an optional expected incident ID; asynchronous tracking paths supply their owning ID, and stale jobs return without clearing the newer session or its foreground notification.
- Explicit user/service stop and invalid start-command paths retain the unscoped stop behavior.
- Existing `activeIncidentId` volatility and `serviceScope.cancel()` teardown were inspected and are already present; no duplicate changes were made.
- Validation: source-level inspection of all stop call sites and the updated guard. No Gradle build, automated test run, or Android device lifecycle test has been performed for this commit. Verify rapid stop/restart across two assignments and late network failures on-device before release.


## Responder tracking session-generation guard — 2026-11-18

- Commits: `7e4c9c763efa6f09a4623d414df2a9aa31f8e873` and `5562828ce886f9c0d9ef0b1959789a71a183a2e5`.
- Extended the earlier assignment-ID ownership guard with a monotonically increasing tracking-session ID. This handles the important same-assignment stop/restart case, where incident IDs alone cannot distinguish a stale coroutine from the new session.
- Tracking and assignment-monitor jobs capture their session ID; asynchronous stop paths pass both incident and session IDs. The stop routine ignores obsolete sessions, and stale jobs cannot call `stopSelf()` while a replacement session is active.
- Explicit notification stop, invalid command handling, and service teardown retain their service-owned stop behavior. Service destruction also invalidates the current session before cancelling the service scope.
- Validation: inspected all tracking/monitor stop call sites, session checks, and service teardown in the updated source. No Gradle build, automated test run, or Android stop/restart lifecycle test has been performed. Device validation should include stopping and immediately restarting sharing for the same incident, then forcing a delayed network failure from the old request.


## Exact-head CI follow-up — responder tracking and dispatcher release fixtures — 2026-11-18

- **Responder source-contract failure:** The session-generation hardening changed both tracking coroutines to receive `sessionId`, but the static source contract still searched for the old one-argument launch and monitor calls. Updated the contract to require the session-aware launch/monitor signatures and the active-session guard while retaining HTTP failure classification checks. No tracking-service behavior changed in this correction.
- **Dispatcher release SQL fixture failure:** Exact-head migration replay failed because the legacy-reservation test inserted an active `incidents.assigned_to` row but left the profile's stored `duty_status` at its original `available` value. The older single-assignment model's effective status is derived from the incident row; it does not automatically seed the stored profile status. Updated the isolated CI fixture to explicitly seed `duty_status = 'assigned'` with the request identity cleared, then restore the dispatcher identity before exercising the release RPC. This makes the fixture accurately represent a pre-existing legacy reservation and does not change the production migration or live database.
- **Commits:** `fa1c7a0`, `1af6a6f`, `a7cb343`.
- **Validation:** The failures were traced to the exact-head GitHub Actions logs. Both test/contract corrections are committed; they still require a fresh exact-head run. The preceding head's Android debug/release builds, JVM tests, instrumentation compilation, and Android Lint passed. No local Gradle/PostgreSQL test, emulator/device test, production migration, deployment, or merge was performed.


## Responder route and write completion generation guard — 2026-11-18

- **Finding:** The unique tracking-session token already protected the collection loop and stop paths, but two asynchronous side-effect boundaries still compared only the incident ID. A delayed location-write response could update the replacement session's UI state, and an older route request for the same incident/destination could publish its result after the user stopped and restarted sharing.
- **Change:** Re-check the exact incident/session generation after the location RPC returns and before publishing send status. Pass the generation into destination refresh and route recalculation, reject stale work before starting, and re-check it after the routing provider returns. The assignment monitor loop now also uses the exact active-session predicate after each suspend point.
- **Regression guard:** Expanded the responder-location source contract to assert the session-aware helper signatures, monitor loop, and post-write guard.
- **Commits:** `b4987ac`, `881fc26`, `e669097`.
- **Validation:** Inspected every call site for the session-aware route helpers and verified the stale-response guard is immediately after the location RPC. No Gradle build, JVM test, PostgreSQL replay, or Android lifecycle/device test has run for these commits yet. The exact-head CI result is pending.


## Stale assignment-verification completion guard — 2026-11-18

- **Finding:** The tracking and assignment-monitor coroutines checked their generation before starting repository calls, but a cancelled old session could still complete `repository.me()` or `repository.myIncidents()` after a same-incident restart. In that case, the old coroutine could publish a misleading status message or process stale assignment data. The monitor's broad exception handler also compared only the incident ID.
- **Change:** Both coroutines now re-check the exact incident/session generation immediately after each repository suspension and before interpreting its result. The monitor exception handler now uses the same exact-session predicate. This complements the post-location-write and post-route guards added immediately before this change.
- **Regression guard:** Added a source-contract assertion that both repository calls in both coroutines are followed by the generation check, and that the monitor error path is session-scoped.
- **Commits:** `6ac39e7`, `a22a5e5`, `bf98a69`, `fe4f006`.
- **Validation:** Inspected the two `me()` and two `myIncidents()` call sites and confirmed each has an immediate generation check. No Gradle build, JVM test, PostgreSQL replay, or Android lifecycle/device test has run for this update. Exact-head CI is pending.


## MapLibre retry callback generation guard — 2026-11-18

- **Finding:** The initial MapLibre style load had a disposal guard, but the user-triggered `retryMapLoad()` style callbacks did not. A retry completing after screen disposal could still install style sources and mutate Compose state. Repeated retries could also allow an older style callback on the same map instance to overwrite the newest style's source references.
- **Change:** Added a monotonically increasing style-request generation scoped to the map screen. Both initial style loading and retry now capture the generation and verify both the active `MapLibreMap` instance and generation before processing the style and again inside the nested `getStyle` callback. Stale callbacks return without changing UI state or installing cluster sources.
- **Regression guard:** Added a lifecycle contract test requiring generation checks at all four asynchronous callback boundaries.
- **Commits:** `d2865ca` (implementation), `d7e7a9d` (contract test), and this documentation commit.
- **Validation:** Reviewed the initial and retry callback paths and verified that disposal clears `mapLibreMap`, while a newer retry increments the generation. No Android build, JVM test, or device navigation/retry test has run for this update. Exact-head CI is pending.


## Route preview selection-generation guard — 2026-11-18

- **Finding:** The route preview already captured both a request generation and a selection generation. It checked both while acquiring device settings/location, but after the routing provider returned it checked only request generation plus the selected incident ID. If the user moved away from an incident and back to the same incident while an uncooperative provider call was completing, the old response could pass the weaker check and publish a stale route.
- **Change:** Reused the complete `isCurrentRequest()` predicate after the provider call and immediately before publishing the route. The predicate requires the request generation, selection generation, and selected incident ID all to match.
- **Regression guard:** Added `tools/test_gps_route_selection_generation.py` to assert that both generations are checked before parsing and publishing a provider result.
- **Commits:** `16930d7` (implementation), `ed6d1bc` (contract test), and this documentation commit.
- **Validation:** Inspected the full route request coroutine and its selection-generation lifecycle. The new Python source-contract test has not been executed here; no Android build, JVM test, or device route-navigation test has run for this update. Exact-head CI is pending.


## Responder status-controller session isolation — 2026-11-18

- **Finding:** The foreground service's tracking and routing coroutines already used a monotonically increasing session ID to reject stale work, but the app-scoped `ResponderLocationTrackingController` still accepted status updates based only on incident ID. Stopping and restarting sharing for the same incident could therefore let a delayed callback from the old session overwrite the new session's UI status, even when the service itself correctly rejected stale writes.
- **Change:** The controller now records the active tracking-session ID. Location-send, navigation, and service-message updates require both the incident ID and session ID to match. Service-stop updates can be scoped to the ending session; explicit user stop clears the controller token immediately. Navigation enablement receives the active session ID, and service destruction preserves the pre-invalidation ID when clearing controller state.
- **Regression guard:** Extended `tools/test_location_contract.py` to require session-scoped controller updates, stop-token checks, teardown identity preservation, and session-aware navigation enablement.
- **Commits:** `31686e4` (controller token), `f1b8cc0` (propagate identity), `da13acd` (navigation/teardown correction), `527c44a` (scope error status), `d817ac8` (source contract).
- **Validation:** Re-fetched the latest source and traced all controller update call sites; each asynchronous update now carries its captured session ID. The Python source contract has not been executed here, and the Android build/JVM tests have not run for this change. Await exact-head CI. No device lifecycle validation, production migration, deployment, or merge was performed.


## Final merge-readiness gate — 2026-11-18

- **Exact-head CI finding:** At head `f96f71b`, the Source checks job failed only at `tools/test_continuous_navigation_contract.py`: its route-forwarding assertion still searched for the pre-session signature `maybeRecalculateRoute(incidentId, capture.fix)`. The implementation now correctly passes `sessionId`; the same job's preceding responder-location contract passed all 37 checks.
- **Correction:** Updated the continuous-navigation contract to assert the session-aware call `maybeRecalculateRoute(incidentId, sessionId, capture.fix)` in commit `878e828`. This is a test expectation correction, not a production behavior change. Exact-head rerun is pending.
- **Other observed checks at the prior head:** Frontend review and Render live smoke passed; repository security guardrails and CodeQL web/workflow analysis passed. Android demo-mode debug APK build completed, but Android JVM tests were still running and UI instrumentation compilation, Android Lint, Supabase-mode debug build, release build, and artifact upload were pending. Android CodeQL was still building. RPC grant contract was still replaying migrations; its anonymous RPC grant step had passed. The PostgreSQL migration replay and Laravel/dashboard integration jobs were skipped because the source-check dependency failed.
- **Merge gate still required:** All required checks must pass on one exact final head, especially source checks, Android JVM tests, instrumentation compilation, lint, both debug variants, release APK build, Android CodeQL, RPC grants, and clean PostgreSQL migration replay. Resolve any subsequent failures rather than relying on older run results.
- **APK gate:** CI artifact upload must complete and artifacts must be downloaded/inspected. A release APK built without configured signing is not equivalent to a distributable signed release. Preserve the demo-mode debug APK separately from the Supabase-mode debug APK. Do not claim a release is installable/updatable without verifying signing configuration and package/version details.
- **Operational rollout gate:** Dispatcher-progress, assignment-history, profile-guard, and other new Supabase migrations remain unapplied in production. Review the ordered migration set and backup/rollback plan before any production application; do not apply without explicit approval. Confirm required Render environment values and routing rollout flag before deployment; route preview remains fail-closed unless enabled. No deployment or production database change is authorized by this review.
- **Manual acceptance still outstanding:** Install the candidate APK on a real Android device and test requester SOS creation/cancellation/location correction, dispatcher multi-member assignment/progress/release, responder/driver duty and foreground GPS stop/restart, GPS map empty/loading/offline/stale states, chat text/image retry after process death, notifications, sign-out/account switching, adaptive layouts, and admin portal access separation. CI source contracts do not replace these checks.



## Dispatcher release fixture duty-state correction — 2026-11-18

- **Finding:** The clean PostgreSQL migration replay and RPC grant contract both failed in `tools/test_dispatcher_release_assignment.sql` because the legacy-reservation fixture wrote `profiles.duty_status = 'assigned'`. The core profile check constraint intentionally permits only `off_duty`, `available`, `en_route`, `on_scene`, and `transporting`. Assignment is represented separately by `incidents.assigned_to` and `incident_assignments`; expanding the profile constraint would conflate two different state models.
- **Correction:** The fixture now uses the valid `en_route` duty status while retaining the separate active legacy reservation through `incidents.assigned_to`. After releasing the member from the newer team, it asserts that the profile retains `en_route` and that the unrelated legacy incident still reserves the member. Production schema and release-function behavior were not changed.
- **Commit:** `a0526460324504ed474f6915d7e4197e89d0ea82` — `Use valid duty state in legacy release fixture`.
- **Validation:** The patch was applied to the PR branch after verifying the exact old fixture block and assertion. Clean migration replay, RPC grant contract, and all other CI checks must be rerun against the new exact head; no passing result is claimed yet. No production database changes, deployment, APK distribution, or merge were performed.


## 2026-11-18 — Isolate assignment-history RLS test identity

- Investigated the clean PostgreSQL replay failure reported as `Admin should be able to read assignment history under RLS`.
- The authenticated-role RLS assertions looked up the fixture incident ID through `public.incidents`. That makes the history assertion depend on the caller's separate incident-read policy; an invisible incident produces a NULL filter and a misleading zero-row result even when assignment-history RLS is correct.
- Updated `tools/test_dispatcher_release_assignment.sql` to save the fixture incident ID in a transaction-session setting while running as the trusted test operator, then use that retained ID for dispatcher, requester, and admin history assertions. This keeps the history-policy test independent of incident-table visibility and avoids widening production access.
- Commit: `cea59b0a72b23f7de21dfe823380261e61d27215` — `Make assignment history RLS tests independent of incident visibility`.
- Validation is pending on the exact updated PR head. This is a test correction, not a production RLS-policy change. No production database changes, deployment, APK distribution, or merge performed.


## 2026-11-18 — Isolate GPS RPC runtime fixture identities

- Exact-head RPC grant contract run `44247460864` passed anonymous RPC grant checks and clean PostgreSQL migration replay, then failed before GPS runtime authorization assertions because `tools/test_location_rpc_security.sql` inserted auth user UUIDs `150–153`, already used by `tools/test_dispatcher_release_assignment.sql` in the same disposable database.
- Updated the GPS runtime fixture to use UUIDs `250–253` and matching unique test phone/email values. No production GPS RPC, grant, RLS policy, or schema behavior changed.
- Commit: `b6c641557d31211d67e8d6a60bce16e5696cc9ef` — `Isolate GPS RPC test fixture identities`.
- Validation: fetched the committed test file and confirmed all four new UUIDs are present and none of the conflicting UUIDs remain. Exact-head CI must still prove the runtime authorization cases pass. No production database changes, deployment, APK distribution, or merge performed.



## 2026-11-23 — PR #342 Storage test-project drift investigation

- Re-verified PR #342 is open, mergeable, and not merged. Current branch head before this note: `25c7973d65a02c9f653623a7152997d00942c67a`.
- Re-checked the isolated Supabase project `TACResQ-Storage-Test` (`kyomsyprjdseaghdxhdr`) using read-only queries. The live `private.incident_chat_upload_policy` still has only `singleton`, `reservation_ttl_seconds`, `uploads_per_minute`, and `updated_at`; it is missing `reservation_enforcement_enabled`. The read-only schema contract therefore correctly fails closed.
- Supabase migration history reports the reservation migration as version `20261122145444` and also reports a later entry named `test_pr342_storage_policy_review_fixes` (version `20261122145856`). The checked PR branch's `supabase/migrations/` directory contains the tracked reservation migration but no migration with that test-only name. This establishes a migration-history/repository discrepancy alongside the schema mismatch; it does **not** establish exactly which operation caused the missing column.
- The project's Storage logs currently expose `appVersion=1.96.4` for a tenant-management PUT request, not a binary object upload. This is not sufficient evidence of the Storage binary upload implementation or deployed release behavior.
- No remote schema, migration history, bucket, object, or data was modified. No credentials or tokens were requested or exposed.
- **Disposition:** Keep PR #342 open. Clean PostgreSQL replay passes on the PR head, but it cannot repair or prove the state of this separate remote project. Do not apply ad-hoc SQL or reset the project. Before HTTP acceptance, reconcile the test project's migration history and actual schema using a reviewed, forward-only migration process, then use a dedicated synthetic test account/incident to test binary POST, finalization, authenticated download, duplicate/retry behavior, and size mismatch rejection.
- **Validation:** Read-only Supabase schema and migration-history queries; GitHub branch directory inspection; Storage log inspection. No HTTP upload/download acceptance, authenticated Android session, emulator UI workflow, production migration, deployment, or merge was performed.
- **Next action:** Prepare a reviewed, non-destructive test-project reconciliation plan that identifies the expected schema delta and migration-history discrepancy before any remote write is considered.



## 2026-11-23 — PR #342 authenticated Storage HTTP acceptance harness

- Added `tools/test_incident_chat_storage_http_acceptance.py`, a standard-library Python harness for the real Supabase HTTP flow used by Android: reserve RPC, authenticated binary Storage POST with `x-upsert: false`, image-message finalization, idempotent finalization retry, and authenticated binary GET with exact-byte comparison.
- The harness is hard-restricted to the dedicated non-production project ref `kyomsyprjdseaghdxhdr` and its default Supabase URL, requires the explicit `TACRESQ_ALLOW_REMOTE_STORAGE_TEST=1` opt-in, and accepts only a publishable/anon key plus a normal authenticated test-user access token. It does not use or accept a service-role key.
- It uploads a generated 1x1 synthetic PNG and writes one synthetic incident message/object. It intentionally does not delete either. An optional unrelated-account access token checks participant-only download denial.
- Added `docs/PR342_STORAGE_HTTP_ACCEPTANCE.md` with prerequisites, local-only environment setup, safety boundaries, expected coverage, and limitations. The remote schema/history mismatch must be reconciled first; a dedicated synthetic incident and account are required.
- Commits: `0c2998b6da3ec83f06ea9a04cd2b86dcd48a3bf2` (HTTP harness) and `9d6cb2402baf64976ff4a4b357ace7757ab74ac8` (acceptance guide). Earlier reconciliation plan: `138b5b80100d13417376267cd0db0ba9b672bd2a`.
- **Validation boundary:** Static source inspection confirms the harness uses the same authenticated Storage object endpoint and RPC parameter names as Android. It has not been run; no synthetic account/incident credentials were supplied. The test project remains unmodified. Exact-head RPC contract, frontend review, and TACResQ CI workflows were queued/in progress at time of note; do not claim them green until polled on the final head.
- **Disposition:** Keep PR #342 open and unmerged. The harness is a preparation step, not evidence that HTTP acceptance has passed. No remote schema, migration history, bucket, object, user data, production deployment, or strict-enforcement setting was changed.

## Admin portal usability and synchronization follow-up — 2026-11-23

- Simplified administrator-facing sign-in and workspace wording; replaced internal terminology with task-oriented labels (Security, Reports, Activity log) without changing authentication, MFA, or authorization behavior.
- Commits: `1760a8db6d0c29bf53a755b4650319560163ee40` and `2f05899021438c3e3933bef583222c57e910b574`.
- Re-synchronizing PR #344 against the latest `main` while preserving PR #342's newer CI and task-handoff changes. The website visual-foundation contract is retained in CI.
- Automated validation and browser/device acceptance must pass before merge; no production deployment or database changes are included.


## 2026-11-26 — Admin IPS security investigation lifecycle research (Section 44)

- **Scope:** Continued the Admin IPS blueprint on the existing docs/admin-ips-ui-master-plan branch and PR #370. Reviewed the current security-event coverage, trusted-denied-action evidence, authentication collection boundaries, Admin Security status component, Activity Log page, Laravel operation audit writer, and existing correlation/detection research (Sections 42–43).
- **Finding:** The repository supports protected browsing of a bounded set of event sources, but it does not establish a persistent security-investigation case registry, evidence vault, case assignment workflow, formal forensic chain of custody, or staffed escalation process. A source event or rule match must not be described as a confirmed security incident.
- **Change:** Added Section 44 to docs/TACRESQ_UI_FUNCTIONALITY_MASTER_PLAN.md, specifying the distinction between source events, detections, security investigation records, and TACResQ emergency incidents; proposed human triage dispositions and lifecycle; source-qualified evidence provenance; append-only decision history; explicit human authority/containment boundaries; investigation workspace composition; and closure/lessons learned.
- **Design invariants:** Keep one Admin portal role; preserve existing theme, eight-section information architecture, collapsible sidebar, current backend authorization and emergency workflows. No automated account/session/device/network enforcement, no new top-level navigation, and no case-management controls until persistence and authorization contracts exist.
- **Research basis:** NIST SP 800-61 Rev. 3 (2025), NIST Incident Response project, ACSC incident response planning guidance, and OWASP Logging Cheat Sheet.
- **Commits:** To be recorded after the sequential documentation updates complete.
- **Validation boundary:** Repository source and documentation review plus external standards research. No application code, database schema, RPC, grants, provider settings, notifications, production deployment, or merge changed. Exact-head CI must be rechecked after the documentation commits; existing workflow results are not transferable to a new head.
- **Next:** Verify the three updated documentation files and exact PR head/checks. Keep PR #370 open and unmerged until all required checks pass on one exact head and outstanding review/acceptance requirements are satisfied.


## 2026-11-26 — Admin IPS monitoring assurance and telemetry freshness research (Section 45)

- **Scope:** Continued on the existing docs/admin-ips-ui-master-plan branch and PR #370. Inspected the Admin Overview dependency-probe implementation and display, the data-free Supabase health RPC, Laravel dependency-health CLI tests, and existing security-event coverage and dependency-monitoring research.
- **Verified current implementation:** Admin Overview runs three bounded on-demand probes: Laravel /up, Supabase Auth /auth/v1/health, and the fixed data-free api_dependency_health() RPC using the current Admin session. The browser probes have a five-second bound and distinguish healthy, slow, and unavailable. The UI scopes its summary to those three checks and lists FCM, routing, Storage, end-to-end workflow, and persistent monitoring as outside coverage. The Laravel CLI separately supports configuration-only and explicit live read-only diagnostics.
- **Remaining evidence gap:** No reviewed repository evidence establishes durable probe history, scheduled monitoring, telemetry source heartbeats, ingestion/evaluation pipeline health, independent audit-sink failure monitoring, alert delivery monitoring, or complete emergency readiness. A quiet event stream cannot prove source health or absence of suspicious activity.
- **Change:** Added Section 45 to docs/TACRESQ_UI_FUNCTIONALITY_MASTER_PLAN.md. It defines four separate dimensions (dependency reachability, telemetry-source health, detection-pipeline health, and coverage/confidence), explicit freshness states and timestamps, source inventory requirements, independent monitor-of-monitor paths, liveness/readiness separation, sustained alert thresholds, operator UI semantics, and acceptance tests.
- **Design invariants:** Keep the current three-probe scope honest; do not introduce a global all-clear score, claim continuous monitoring, add a paid provider/credential/scheduler, or block SOS/dispatch on optional dependencies. Preserve the existing theme, eight-section IA, and collapsible sidebar.
- **Research basis:** NIST SP 800-137, NIST RMF Monitor step, ASD ACSC/CISA/FBI/NSA Best Practices for Event Logging and Threat Detection, and CISA LOTL joint guidance.
- **Validation boundary:** Repository source inspection, tests/source-contract inspection, and external standards research. No application code, database schema, RPC, grants, provider configuration, recurring job, notification destination, production deployment, or merge changed. CI must be checked on the final PR head; no prior-head result is transferable.
- **Next:** Verify all three documentation updates, comment on PR #370, and inspect exact-head CI. Keep the PR open and unmerged until required checks and review conditions are satisfied.


## 2026-11-26 — Admin IPS backup/recovery assurance and Data & Continuity UX research (Section 46)

- **Scope:** Continued on docs/admin-ips-ui-master-plan and PR #370. Inspected AdminConsole Data & Continuity and Security status UI, backup/restore research plan, PostgreSQL and Storage operator procedures, isolated restore checklist, local manifest tools, and Storage bucket inventory SQL.
- **Verified repository UI:** Data & Continuity already has Backup & recovery and Attachment safeguards views. The recovery view correctly states no live backup schedule, off-site copy, freshness record, or completed restore drill is connected. The attachment view correctly says live object inventory, byte-integrity scan, retention, upload/download telemetry, and current provider policy checks are not connected. Preserve this honest boundary; do not add duplicate navigation or a raw media browser.
- **Read-only connected infrastructure check (2026-11-26):** Supabase TACResQ-School-Project ref kttguamhznoflzagfutr is ACTIVE_HEALTHY on PostgreSQL 17.11 in ap-southeast-1. Project detail does not expose billing plan or backup/PITR entitlement. Render TACResQ service srv-db8s5kfavr4c73c5dft0 is a Free Docker web service in Singapore, one instance, branch main, auto-deploy on commit; returned service configuration has no persistent disk. No TACResQ Render Postgres service appeared in the workspace service inventory. No settings or data were changed.
- **Important new provider research:** Supabase docs updated 2026-11-23 describe beta Restore to a new project. For eligible paid-plan projects with physical backups enabled, it can transfer DB schema/data/roles and Auth user data/encryption root key. It does not transfer Storage object bytes/settings, Edge Functions, Auth settings/API keys, Realtime/database settings, integrations, or project add-ons. TACResQ eligibility remains unverified. This qualifies (but does not contradict) the CLI dump exclusion of managed auth/storage schemas: beta provider clone is a separate recovery path.
- **Change:** Added Section 46 to the canonical blueprint. It defines progressive recovery evidence levels, independent component tracking, measured RPO/RTO, isolated restore safety, existing two-view Admin UX contract, append-only trusted evidence feed, and Render recovery boundary. It records that manifests prove local integrity, not recoverability.
- **Design invariants:** Keep existing Data & Continuity two-view IA, current theme, one Admin portal role, and safe evidence-only UI. No backup job, off-site destination, new provider, paid plan, project clone, restore, persistent disk, credential, database/schema, or production setting is added or changed.
- **Research basis:** NIST SP 800-34 Rev. 1 contingency planning; current Supabase Database Backups and Restore to a new project beta docs; current Render Persistent Disks docs.
- **Validation boundary:** Source inspection, read-only Supabase/Render configuration inspection, and external provider documentation review. No actual backup/export, download, manifest creation, restore drill, Auth recovery, Storage copy, or production action was performed. PR remains open/unmerged pending exact-head CI and reviews.
- **Next:** Verify the documentation updates, add a PR #370 evidence note, and inspect checks on the final head.


## 2026-11-26 — Emergency-service continuity and graceful degradation research (Section 47)

- **Scope:** Continued on the existing Admin IPS research branch and PR #370. Inspected Android offline SOS resilience and read-sync research, end-to-end workflow review, encrypted SessionStore/TacRepository behavior, OfflineSyncPolicy and WorkManager scheduler, LocationProvider, FCM Android receiver, Laravel FCM dispatcher, FCM attempt-ledger migrations, and relevant tests.
- **Current safeguards verified in source:** pending SOS is encrypted, owner-bound, retains original request ID/payload through ambiguous failures, and clears only after confirmed success; requester status cache is owner-scoped/read-only with 24-hour expiry; automatic worker is constrained to authenticated read reconciliation and rejects emergency/other mutations; one-time GPS fix does not fall back to lastLocation and exposes permission/service/timeout/invalid/unavailable states; FCM worker has lease/backoff/attempt ledger and Android checks recipient identity before display; Realtime is documented as a refresh hint with inbox reads as source of truth.
- **Critical operational gap:** Existing end-to-end review says the canonical Render FCM dispatch command is not continuously run by service startup and the free-compatible Supabase Cron + pg_net trigger is not enabled in the connected project. Push scheduling is therefore not verified/active. FCM provider acceptance is not proof of device receipt/display; notification read is not responder acknowledgement or dispatch action.
- **Change:** Added Section 47 to the canonical blueprint. It defines dependency-specific failure domains, role-specific requester/responder/dispatcher/Admin degraded behavior, SOS submission state machine and truthful copy, FCM evidence distinctions, retry/idempotency classification, Admin monitoring scope, UI status semantics, and a detailed regression matrix.
- **Design/safety invariants:** No global online/all-clear score; no false empty states; no silent background SOS submission or replay of stale GPS; no blind retries for status/assignment/chat/location writes; no inference of help-on-the-way from a notification; no hotline invention; no production synthetic writes; preserve existing theme, IA, Admin-only portal role, Supabase/Render boundaries, and educational-prototype limitation.
- **External research:** Android offline-first architecture and location lifecycle guidance; Supabase session refresh/token behavior; Firebase Cloud Messaging delivery semantics and message lifespan; OWASP REST error handling. FCM acceptance is explicitly distinguished from delivery.
- **Validation boundary:** Repository source/docs/tests inspection and official documentation review only. No application code, database, RPC, scheduler/trigger, FCM destination, provider setting, live data, or deployment changed. Physical-device outage tests and actual provider delivery remain unverified.
- **PR/CI:** PR #370 remains open and unmerged. At initial check, branch head advanced independently to 51d0c18955f6b1bd21ea7b7ab972df55e03788b4; QA, RPC contract, CI, and security workflows were still in progress, while frontend review, read-only profile verification, automated code review, and Gradle dependency submission had completed successfully. Recheck exact head after documentation updates.
- **Next:** Verify Section 47, skill, and handoff updates; add a PR #370 comment; inspect final-head CI and continue with the next verified safety/reliability gap.


## 2026-11-26 — End-to-end workflow assurance and interaction correlation research (Section 49)

- **Branch/PR:** Continued on `docs/admin-ips-ui-master-plan`, PR #370. At start of this task the exact PR head was `76d1232a4b817aa87f708fd2031264966bed8845`; base/main was `b877cb3819484755145834f528252d13c04fb01b`. The branch already contained Section 48 (security verification strategy), so this work was numbered Section 49.
- **Repository evidence inspected:** Laravel `RequestCorrelationId` middleware, `SupabaseBridge`, `ApiOperationAuditLogger`, `api_operation_logs` migration, incident status trigger, assignment event history, app action audit migration and Admin investigation RPC, security event coverage review, and end-to-end emergency workflow review.
- **Verified correlation behavior:** Laravel generates a fresh server UUID per request, ignores caller-supplied `X-Request-ID`, returns its own header, and uses it in selected `api_operation_logs` rows. SupabaseBridge currently passes null provider request IDs for Auth/RPC and does not forward W3C `traceparent`. Domain incident-status and assignment events do not store this common request correlation ID. `app_action_audit_logs` defaults to its own random UUID, not proven to be propagated from the originating interaction. Direct Android/Admin-web Supabase calls bypass the Laravel logger. Admin investigation RPC does not currently include `api_operation_logs`.
- **External discovery:** Supabase Query Logs docs (updated 2026-11-18) document `sb-request-id` on API Gateway responses and W3C `traceparent` extraction into `trace_id`/`span_id` in project logs. Supabase Correlated Logs entered beta on 2026-11-10 and can connect log items by requestId, traceId, and userId. W3C Trace Context is a Recommendation; OWASP recommends interaction identifiers in event context. Beta availability and project-specific behavior remain to be verified.
- **Change:** Added Section 49 to canonical blueprint and Section 49 reference to the Admin IPS skill. It defines five distinct identifiers, a staged correlation contract, workflow proof chain, correlation confidence, Admin investigation UX, phased implementation, and tests. It explicitly avoids assuming full traces or a production-grade emergency workflow.
- **No code/config change:** No instrumentation SDK, logger, schema, RPC, migration, provider setting, paid service, production write, or UI integration changed. This is documentation/research only.
- **Evidence boundary:** No live trace/log query, synthetic SOS lifecycle, or cross-provider trace was executed. No direct-client trace header behavior or Supabase correlated log availability was confirmed for every API surface. Current exact-head CI must be checked after docs updates; PR remains unmerged.
- **Next:** Verify the four documentation files, post a PR #370 research note, and inspect CI for the final exact head.


## 2026-11-26 — Synthetic SOS lifecycle verification specification (Section 50)

- **Branch/PR:** Continued on `docs/admin-ips-ui-master-plan`, existing PR #370. PR head before this documentation update: `a2ac05ae3cd7a27f8b7448a540584e034066c999`.
- **Source audit:** Reviewed `docs/architecture/END_TO_END_EMERGENCY_WORKFLOW_REVIEW.md`, offline SOS resilience, FCM operations/runbook, incident/chat/notification docs, SOS idempotency migration, dispatcher team assignment and per-responder status migrations, field-unit claim migration, assignment event history, FCM delivery queue, chat, location writer, and related existing Python/PHP/SQL/Android tests.
- **Important correction:** There is no universal responder-acceptance event in the dispatcher-managed team path. Dispatcher team assignment and dispatcher-enabled field-unit claiming are distinct paths and must be tested on separate fixtures. Shared incident progress is dispatcher-managed; GPS sharing is a separate explicit action.
- **Added:** `docs/QA_SYNTHETIC_SOS_LIFECYCLE_VERIFICATION.md`, with synthetic identities, primary team-assignment workflow, separate claimable workflow, source-backed expected evidence, negative side-effect assertions, failure-injection matrix, existing test coverage/limitations, layered execution strategy, acceptance gate, and official references.
- **Blueprint and skill:** Added Section 50 to canonical master plan and corresponding skill summary.
- **Existing suite limitation:** `tools/test_workflow.py` defaults to a local deterministic prototype model; optional HTTP mode does not represent Android direct-Supabase E2E. Existing Python source-contract scripts and Laravel fake FCM tests are component evidence, not a full distributed execution.
- **Deployment blocker:** FCM dispatcher automatic execution is not configured in current Render Free web-service startup; reviewed Supabase project lacks the required cron/net trigger. Push stage remains Blocked/Not configured; no activation or production changes authorized.
- **No runtime changes:** No code, migration, RPC, schema, credentials, FCM send, worker, cron, Render plan, production data or service setting changed. No test workflow was executed; this is a test specification only.
- **Next:** Re-fetch and verify all four documentation files, post the PR #370 note, inspect checks on the exact final head. Do not merge until required checks and reviews are complete.


## 2026-11-26 — CI coverage audit against synthetic SOS lifecycle (Section 52)

- **Branch/PR:** Continued on `docs/admin-ips-ui-master-plan`, existing PR #370. The PR was open and mergeable at audit start; base `main` was `b877cb3819484755145834f528252d13c04fb01b`. The audited pre-repair head was `4db98b3a457ac5488033947913cc5ab377f48f05`.
- **Repository instructions re-read:** `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, current workflow inventory, the synthetic SOS test specification, and existing CI/test files.
- **CI coverage finding:** CI is not build-only. It runs extensive deterministic Python source/migration contracts; an isolated PostgreSQL migration replay/SQL authorization suite; Android JVM unit tests, instrumentation APK compilation, lint and APK builds; Laravel/PostgreSQL feature tests; a bounded local Laravel GET stress smoke; browser prototype capture; CodeQL/security and read-only profile contracts.
- **Important distinction:** The PostgreSQL service runs migration replay and SQL contracts, but the workflows do not invoke the full Supabase local stack with `supabase start` / `supabase test db`; no pgTAP suite was identified. Android PR CI runs `testDebugUnitTest` and `assembleDebugAndroidTest`, but not `connectedDebugAndroidTest`; a separate manual emulator workflow exists. Laravel feature tests are configured but are downstream of Source checks and were skipped on the audited failing head. Render live/authenticated bridge checks are manual or path-filtered. FCM is tested through fakes/SQL queue contracts, not a real provider-to-device test.
- **Exact-head evidence at SHA `4db98b3a457ac5488033947913cc5ab377f48f05`:** RPC grant/migration replay passed (run 45841649093); read-only profile passed (45841649063); QA local-stack stress and prototype capture passed (45841649111); Android debug/release build job passed, including JVM tests, instrumentation compilation and lint (run 45841649046); frontend review and automated code review passed (45841649185 / 45841648743). Security run 45841649065 had web/Actions CodeQL and security guardrails passed, Android CodeQL still running at initial inspection, and dependency review skipped because `DEPENDENCY_GRAPH_ENABLED` was not enabled.
- **CI failure diagnosis:** TACResQ CI run 45841649046 failed in `tools/test_admin_overview_analytics_separation_contract.py` because one expected Overview sentence was stale. Current UI copy says “Today's activity is the exact count returned by the protected investigation feed since Manila midnight”; the contract expected the older “an exact count from…” phrase. Subsequent source checks and downstream migration-replay/Laravel integration jobs were skipped.
- **Repair:** Updated only the stale expected string in `tools/test_admin_overview_analytics_separation_contract.py`, commit `23d6815a0d2c55b30469e526ac1fbe23a8801fa5`. No application behavior changed. The corrected test and all downstream jobs require a fresh exact-head run; no pass is claimed yet.
- **Documentation:** Added `docs/QA_CI_COVERAGE_MATRIX.md`; added Section 52 to `docs/TACRESQ_UI_FUNCTIONALITY_MASTER_PLAN.md`; updated the Admin IPS skill reference map. The matrix classifies lifecycle coverage and lists exact workflow/run IDs, known skipped/conditional layers, and safe improvement priorities.
- **External standards checked:** Supabase current testing guidance for pgTAP and CI, and Android guidance distinguishing JVM tests from instrumented device/emulator tests. These standards support future test design; they do not imply that TACResQ already implements those paths.
- **No operational side effects:** No live Supabase schema/data, Render configuration, worker schedule, FCM provider, credentials, paid service, production account, or real incident changed or exercised. No complete synthetic SOS was run.
- **Next:** Re-fetch the three updated documents and matrix; inspect the new exact PR head and all workflow results after the source-contract correction. Fix any subsequent genuine/stale contract failure, then verify that downstream Laravel integration and any CI database job actually execute. Do not merge until all required exact-head checks pass.


## 2026-11-26 — Follow-up CI contract correction: Admin recovery navigation

- **Latest audited head before repair:** `cca2ab1a32bd2184d3cbd0e10ce04d055b792665`; PR #370 remained open.
- **Run:** TACResQ CI `45842444081`.
- **Finding:** The corrected Admin Overview assertion passed. Source checks advanced to `tools/test_admin_recovery_workspace_contract.py` and failed because one assertion expected `Data &amp; continuity</strong>`, but the actual navigation markup is `<span>Data &amp; continuity</span>`. The route, section and navigation item are present; only the expected wrapper was stale.
- **Repair:** Updated that single test-only assertion to match the current semantic span markup. Commit: `b2c28dc80dc85ccaeacbaa3f8279e88d09a2028c`. No application UI or behavior changed.
- **Documentation:** Added this second failure/repair to `docs/QA_CI_COVERAGE_MATRIX.md` and Section 52 of the master plan.
- **Run state at diagnosis:** Source checks failed at the recovery contract; later steps and downstream Laravel/migration jobs were skipped. Android and RPC workflow results were still in progress at the last check. The latest source repair and documentation commits need another full exact-head CI run.
- **Next:** Inspect checks for the latest branch SHA; verify the two corrected contracts pass, then address any further stale assertions one at a time. Confirm Laravel integration and migration replay actually execute. Do not merge until all required exact-head checks are green.


## 2026-11-26 — Disposable local Supabase integration test design (Section 53)

- **Repository / PR:** Continued on `docs/admin-ips-ui-master-plan`, existing PR #370. Pre-documentation audit head was `0b36cf19fcee21368e522897541b3e9295150137`; latest docs commit is recorded by subsequent branch history.
- **Exact-head CI at pre-documentation head:** Source checks passed; Android debug/release build job passed including JVM unit tests, instrumentation APK compilation and lint; QA local stack stress and prototype capture passed; independent RPC grant contract passed; repository security guardrails and web/Actions CodeQL passed. Laravel/PostgreSQL integration and main CI migration replay had started but had not completed.
- **Important cancellation qualification:** The local Supabase design documentation then created new commits and changed the branch head. The previous run's Laravel feature-test step was cancelled as the old run was superseded; dashboard checks were skipped and the main migration replay had not completed. Do not carry those in-progress results forward as passes. Recheck fresh runs on the latest exact head.
- **CI graph finding:** Both Laravel/PostgreSQL integration and the main clean-PostgreSQL migration replay depend on `source-checks`. GitHub Actions skips dependent jobs if a prerequisite fails/skips unless explicitly overridden. This fail-fast gate is appropriate; do not add `always()` to force them to run after failed source contracts.
- **Local Supabase feasibility:** No `supabase/config.toml` or `supabase/config.jsonc` exists in the repo. Existing migration replay builds minimal stand-in Auth/Storage schemas; it is not the full Supabase stack. Android `AppConfig.kt` hard-codes the hosted Supabase URL/key, so a dedicated local integration build needs a release-safe endpoint override.
- **Documentation:** Added `docs/QA_LOCAL_SUPABASE_INTEGRATION_PLAN.md`; added Section 53 to `docs/TACRESQ_UI_FUNCTIONALITY_MASTER_PLAN.md`; extended `docs/QA_CI_COVERAGE_MATRIX.md`; updated the Admin IPS skill reference.
- **Recommended implementation order:** First pin the Supabase CLI and add one canonical local config; verify `supabase db reset --local` on clean actual Supabase services; preserve existing PostgreSQL replay; add focused pgTAP tests; then synthetic local Auth/JWT/RPC/Storage/Realtime harness; only then a separate debug/test-only Android endpoint override and optional emulator lane.
- **Safety:** No CLI config/dependency, migration, pgTAP suite, workflow, Android endpoint override, credential, hosted Supabase project, Render production service, FCM provider, real user or production incident changed or exercised. This turn was research/documentation only.
- **Next:** Inspect new exact-head runs after documentation commits. Verify CI Source, Android, Security, QA and RPC jobs; then ensure Laravel integration and migration replay both finish successfully. Do not merge until required checks pass on the latest SHA. After that, begin Stage A as a separately scoped implementation only if all migration/config assumptions are confirmed.


## 2026-11-26 — RPC authorization regression follow-up (Sections 54–55)

- **Concurrent branch update observed:** PR branch advanced to `2f6b940fe2b60a02e333c8683eaa4958918e59c3` with commit `docs(security): reconcile live public RPC authorization` (Section 54). Preserved this commit and built the next test-design work on top of it.
- **Section 54 findings reviewed:** 24 public SECURITY DEFINER API entry points, 14 public SECURITY INVOKER API entry points, 13 authenticated-callable private SECURITY DEFINER helpers; all eight Admin wrappers call the shared active Admin + AAL2/TOTP helper; one narrow active-account inconsistency remains in live `api_mark_notification_read(bigint)`; `api_unregister_fcm_token(text)` intentionally allows suspended users to remove only their own exact token; `api_admin_list_audit_logs(integer)` remains a compatibility surface requiring caller trace; trigger-only location function must not be directly callable while its trigger remains operational.
- **Source/migration cross-check:** `20261105235009_persistent_in_app_notifications.sql` contains the caller profile lookup and caller-owned recipient predicate for mark-read without an active-status check. `20261125020846_account_lifecycle_fcm_token_guards.sql` explicitly implements the suspended self-unregister exception and active-only token SELECT/INSERT/UPDATE policies.
- **New documentation:** Added `docs/QA_RPC_AUTHORIZATION_RUNTIME_MATRIX.md`; added Section 55 to the master plan; extended `docs/QA_LOCAL_SUPABASE_INTEGRATION_PLAN.md` with same-valid-JWT suspension, notification ownership, FCM cleanup, trigger-only grant and Admin MFA cases; updated the Admin IPS skill reference.
- **Important test semantics:** Suspend a synthetic local profile while retaining the same valid JWT, then replay that token against PostgREST. Sign-out or a fresh JWT does not prove suspended-session enforcement. Test FCM unregister as a deliberate narrow exception, not a failure of the active-profile guard.
- **Cross-reference correction:** Section 54.7's trigger-only location reference now points to the Section 30 finding rather than Section 53, because Section 53 is the new local Supabase plan.
- **Safety:** No mark-read migration, grant change, policy, hosted RPC, live Auth user, token, notification, production database, Render service or FCM provider was changed or exercised. This is test design only.
- **Next:** Recheck the latest PR head after the Section 54/55 docs commits. Confirm current CI/CodeQL/QA/RPC runs, then add the proposed mark-read migration and runtime tests only as a separate, explicitly reviewed implementation after the local Supabase Stage A is proven. Do not deploy live changes without explicit approval.
