# Current task handoff

**Last updated:** 2026-10-21  
**Repository:** `RamProjector/TACResQ-School-Project`  
**Base branch:** `main`; PR #61 merge commit is `5c25a20d408f5363c5966add275f809ec080b930`  
**Current task:** finish live Supabase security verification and physical-device QA.  
**Do not create a duplicate requester account:** the user's original verified requester profile remains present.

## Completed and verified

- [PR #58](https://github.com/RamProjector/TACResQ-School-Project/pull/58) merged; its anonymous table/sequence privilege migration was applied to Supabase project `kttguamhznoflzagfutr`.
- Live catalog checks verified `anon` has no SELECT/INSERT/UPDATE/DELETE privileges on `profiles`, `incidents`, `incident_status_logs`, `location_logs`, or `fcm_tokens`; no USAGE/SELECT/UPDATE on the five matching identity sequences; and no EXECUTE on the audited public application RPCs. Expected authenticated table access and active RPC grants remain.
- The live migration ledger records the migration as version `20261021113046`, name `revoke_anon_table_privileges`. The source migration is `supabase/migrations/20261021120000_revoke_anon_table_privileges.sql`; the remote migration tool assigned its own version timestamp.
- Four profile roles exist: one `requester`, one `dispatcher`, one `responder`, and one `driver`. Responder and driver are `off_duty`. The user's original verified requester account remains unchanged.
- [PR #59](https://github.com/RamProjector/TACResQ-School-Project/pull/59) refreshed earlier handoff documentation.
- [PR #60](https://github.com/RamProjector/TACResQ-School-Project/pull/60) expanded `tools/test_rpc_grants.py` to cover active client RPCs across checked-in migrations, including unit lookup, v2 incident creation, and responder-location functions. Merged commit: `1f114b5d9f047eba37d6946c32a387981481b14a`.
- [PR #61](https://github.com/RamProjector/TACResQ-School-Project/pull/61) refreshed this handoff. Its CI run [39597655013](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39597655013) passed all four jobs: source checks, Android debug/release builds, prototype/navigation/visual checks, and Laravel/dashboard integration. PR #61 merged as `5c25a20d408f5363c5966add275f809ec080b930`.
- Android APK artifacts are available from the successful [CI run](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39597655013), including `tacresq-demo-debug.apk`, `tacresq-supabase-debug.apk`, and `tacresq-release.apk`. The release APK may be unsigned unless signing secrets are configured. The debug APKs share the same application ID, so installing one replaces the other.
- [Issue #24](https://github.com/RamProjector/TACResQ-School-Project/issues/24) tracks physical-device validation of requester SOS, responder location lifecycle, and safe fallback behavior.

## Current live security finding

Supabase Security Advisor still reports `auth_leaked_password_protection`: leaked-password protection is disabled. Enable it manually in the Supabase Dashboard under **Authentication → Settings → Password Security**, then re-run the advisor. Available tools do not expose a safe direct toggle for this Auth setting.

## Remaining verification gaps

- Real sign-in and the full requester → dispatcher assignment → responder/driver status flow have not been tested on a physical phone against the live project.
- Physical GPS fix acquisition, OpenStreetMap tile loading on the user's network, current responder location reporting, and sign-out have not been device-verified.
- CI builds and offline contract tests do not prove live end-to-end behavior.
- This is an educational prototype, not an emergency-services provider. Use only clearly labeled synthetic incidents and test locations during QA; never use real emergency reports or another person's location/contact data.

## Next actions

1. Enable leaked-password protection in the Dashboard and re-run the Supabase Security Advisor.
2. Install `tacresq-supabase-debug.apk` from the latest successful CI artifact above.
3. Test with the existing requester credentials and separately provisioned QA role accounts. Do not request or store passwords in chat.
4. Create one clearly labeled synthetic incident; test dispatcher assignment and responder/driver status progression; verify updates across roles.
5. Test GPS permission denial, successful current fix, map tile loading, consent-gated foreground sharing, stale/unavailable states, and sign-out on a physical device.
6. Record pass/fail outcomes, Android version, tested app commit, and sanitized error messages in issue #24 before making further code changes.

## Safety and repository rules

- Never commit secrets, service-role keys, passwords, tokens, real emergency details, or precise personal location data.
- Keep privileged roles administrator-provisioned; public signup must only create requester profiles.
- Preserve RLS, least-privilege grants, role/status checks, explicit location consent, and truthful GPS states.
- Before further implementation, inspect the latest `main`, this handoff, and relevant code. Work on a task-scoped branch and validate changes before opening a focused PR. Do not merge or deploy by default unless the user explicitly authorizes it.
