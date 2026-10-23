# Current task handoff

## Active continuation — 2026-10-23

- **Priority:** TACResQ Android app and real-backend verification. Keep changes scoped, reviewable, and evidence-backed. Preserve the existing five-button navigation and GPS/map as a primary feature; do not redesign or replace it without an explicit request.
- **Current main:** `236bbc54acb21bc117a78a4397962785ec452bad` (PR #105 merged).
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
- **Next steps:** install the Supabase-backed APK on a test phone; record app commit and Android version; run the issue #24 checklist; capture exact errors and sanitized screenshots. Then fix observed defects in focused branches, rerun CI, and merge only when the current PR head and required checks are verified. No physical device was available to this workflow, so these tests remain pending.

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
