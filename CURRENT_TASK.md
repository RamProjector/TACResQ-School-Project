# Current task handoff

## Latest continuation update — 2026-10-22

- Navigation merge `11424fce2243f505fc77f691014c5db929a0b022` is the verified merge commit for PR #71. CI was verified on its exact PR head before merge; a separate post-merge run was not returned by the PR-triggered workflow lookup.
- [PR #71](https://github.com/RamProjector/TACResQ-School-Project/pull/71) was merged as `11424fce2243f505fc77f691014c5db929a0b022` after the exact head commit `5db1b626749775cfd056b33368d9c235298d699b` passed all five jobs in TACResQ CI run [39688792855](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39688792855) and the RPC grant contract run [39688792868](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39688792868). The five-destination navigation source-contract test, Android debug/release builds, visual/browser checks, clean migration replay, and Laravel/dashboard integration passed. The release artifact remains unsigned unless signing secrets are configured.
- PR #71 initial CI exposed two issues: the Android source validator still expected the old responder label/map-item signature, and one earlier visual job hit a transient Playwright browser-download failure. The validator was aligned to the responsive label and function signature; a subsequent run on code commit `10cf17ea154df02807f480a1aaa686f6231cce20` passed all five CI jobs, including Android debug/release builds, source checks, visual/browser checks, clean migration replay, and Laravel/dashboard integration. Run: [39688410426](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39688410426).
- The navigation labels now remain `Current call` on wider layouts and become `Call` only below 400 dp; the center action shows `GPS Map` on wider layouts and `Map` on compact layouts. Physical-device visual QA remains unverified.
- Superseded PR #67 was closed unmerged after PR #71 merged. The merge is UI-only; no Supabase changes, live data changes, account creation, or deployments were made.
- This continuation used GitHub remote operations only; no local working-tree state was inspected. PR #71 was merged and superseded PR #67 was closed. No Supabase changes, live data changes, account creation, or deployments were made.

### Immediate next actions

1. Test navigation on a physical Android device at narrow widths and larger font scale before claiming device-level visual fit.
2. Continue physical-device QA for GPS fix acquisition, OpenStreetMap tile loading, location-sharing lifecycle, sign-in/out, and the synthetic requester → dispatcher → responder/driver workflow.
3. Recheck Supabase leaked-password protection in the project dashboard; it was previously reported disabled and cannot be enabled through the available connector.
4. Keep using synthetic incidents and test locations only. Do not claim device-level or production emergency-service readiness based on CI.


**Last updated:** 2026-10-21  
**Repository:** `RamProjector/TACResQ-School-Project`  
**Base branch:** `main` includes navigation merge `11424fce2243f505fc77f691014c5db929a0b022`  
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
