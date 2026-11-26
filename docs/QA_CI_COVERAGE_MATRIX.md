# TACResQ CI coverage matrix — synthetic SOS lifecycle

**Audit date:** 2026-11-26  
**Repository:** `RamProjector/TACResQ-School-Project`  
**Branch / PR:** `docs/admin-ips-ui-master-plan` / [PR #370](https://github.com/RamProjector/TACResQ-School-Project/pull/370)  
**Audited workflow snapshot:** PR head `4db98b3a457ac5488033947913cc5ab377f48f05` before the source-contract repair recorded below.  
**Purpose:** Distinguish tests that execute in CI from source-only checks, manual workflows, provider checks, and unimplemented end-to-end evidence. This matrix supplements `docs/QA_SYNTHETIC_SOS_LIFECYCLE_VERIFICATION.md`; it does not claim that the synthetic lifecycle has been executed.

## 1. Executive finding

TACResQ has meaningful automated coverage across source contracts, isolated PostgreSQL migration replay, Android JVM tests, Laravel feature tests, and bounded local-stack smoke checks. The suite is stronger than a build-only pipeline.

It is **not** yet a complete requester-to-dispatcher-to-field-unit end-to-end test. The normal PR pipeline does not run a real Android client against a disposable Supabase Auth/Realtime/Storage/Edge stack, does not verify a real FCM device receipt, and does not prove production scheduling or delivery. The existing synthetic SOS lifecycle document remains a test specification.

## 2. Workflow-level coverage

| Workflow / job | Trigger and execution | What it actually proves | What it does not prove |
|---|---|---|---|
| `.github/workflows/ci.yml` — Source checks | Every PR, push to main, manual dispatch; no path filter | Runs many deterministic Python source/migration-contract scripts, including durable SOS idempotency, requester retry/cache, team assignment, field-claim exclusivity, claimability lifecycle, dispatcher freshness, role boundaries, FCM sender contract, Realtime/notification contract, Admin contracts | Python source assertions do not execute all Android/API/database paths. The job stops at its first failing step; later steps are skipped |
| `ci.yml` — Android debug and release builds | Same CI workflow | Debug/fake-backend and Supabase-mode APK builds; Android JVM unit tests via `testDebugUnitTest`; instrumentation APK compilation via `assembleDebugAndroidTest`; debug lint; release build | Does not run instrumented tests on an emulator in this job; build success does not prove live Supabase behavior |
| `ci.yml` — Advisory prototype checks | Same CI workflow | Runs `tools/test_prototype.py` against the browser prototype test harness | Not native Android app acceptance and not Android↔Supabase E2E |
| `ci.yml` — Replay Supabase migrations on clean PostgreSQL | Same CI workflow, downstream job | Replays repository Supabase SQL migrations against an isolated PostgreSQL service and runs SQL integration/privilege contracts | PostgreSQL-only test service is not the full Supabase local stack (Auth, Realtime, Storage, Edge Functions, API gateway); it is not a hosted-project test |
| `ci.yml` — Laravel and dashboard integration | Same CI workflow, downstream job | Starts disposable Laravel + PostgreSQL Compose services, runs Laravel migrations, PostgreSQL-backed Sanctum lifecycle test, full Laravel PHPUnit feature suite, and dashboard HTTP/static checks | Does not prove deployed Render configuration, scheduled worker execution, or FCM device receipt |
| `.github/workflows/rpc-grant-contract.yml` | Every PR, push to main, manual dispatch | Independent PostgreSQL service; runs RPC grant and account-lifecycle contracts, `tools/test_supabase_migration_replay.sh`, and SQL privilege/authorization checks | Not Supabase Auth/Realtime/Storage/Edge integration or production grants; tests are isolated |
| `.github/workflows/quality-assurance.yml` — Local stack stress | Every PR, manual dispatch, weekly schedule | Builds a disposable local Laravel/Postgres stack and sends bounded GET requests to the local API | Not an SOS workflow, load test of Supabase, or production capacity proof |
| `quality-assurance.yml` — Prototype screen capture | Every PR, manual dispatch, weekly schedule | Captures the browser prototype gallery with Playwright | Not native Android UI testing or an acceptance gate |
| `.github/workflows/security.yml` | Every PR, push to main, manual, weekly schedule | Android CodeQL build/analysis, web/Actions CodeQL, deterministic repository security guardrails | Dependency Review is conditional on repository variable `DEPENDENCY_GRAPH_ENABLED=true`; it was skipped in the audited run |
| `.github/workflows/read-only-profile-verification.yml` | Every PR, push to main, manual | Read-only profile RLS source/database contract | Does not validate every RLS policy or all authenticated app flows |
| `.github/workflows/android-emulator-manual.yml` | Manual `workflow_dispatch` only | Runs `connectedDebugAndroidTest` on an API 36 emulator | Not part of ordinary PR CI unless manually dispatched |
| `.github/workflows/render-live-smoke.yml` | Manual dispatch, or PR/push touching specific backend deployment files | Checks deployed Render health, runtime Supabase configuration shape, and unauthenticated bridge rejection | Not an authenticated user workflow or a synthetic SOS; docs-only PRs do not trigger it by path |
| `.github/workflows/render-authenticated-bridge-smoke.yml` | Manual dispatch only; role-specific synthetic credentials required | Authenticates through the live Render bridge for requester/responder/driver/dispatcher accounts | Not Android direct-Supabase flow, and not run on every PR |
| `.github/workflows/build-real-backend-apk.yml` and `build-test-apk.yml` | Manual dispatch only | Produce debug APKs configured for Supabase mode or fake-backend mode | Build artifacts only; no user journey is executed |

## 3. Lifecycle-stage matrix

Status labels: **Executed in PR CI** = a corresponding automated step ran successfully on the audited head; **Exists, but conditional/manual** = workflow/test exists but was not part of the ordinary PR run; **Static contract only** = source/migration text assertions, not runtime execution; **Not implemented / not proven** = no matching end-to-end evidence found.

| Synthetic lifecycle stage | Existing evidence | Coverage classification | Remaining gap |
|---|---|---|---|
| Requester SOS request identity and durable idempotency | `tools/test_durable_sos_idempotency.py`, `tools/test_laravel_incident_idempotency_contract.py`; migration replay includes database-level concurrent idempotency scenarios | Static contract + isolated PostgreSQL execution | No Android client process-death/timeout/retry against live Supabase Auth and RPC in one E2E test |
| Requester offline pending envelope and status cache | `tools/test_pending_sos_verification_state.py`, `tools/test_pending_sos_retry_label.py`, `tools/test_requester_offline_cache.py`, Kotlin JVM tests, Android persistence instrumented test source | Source contract + JVM tests; instrumented test source exists | Instrumented persistence test is not run by normal PR CI; compile-only instrumentation is not execution |
| Dispatcher queue visibility and freshness | Dispatcher filter/freshness/history contracts; Android unit/source contracts | Mostly static/source contract | No durable “dispatcher viewed queue” event exists; do not invent one. No end-to-end app session proves queue visibility |
| Dispatcher-managed team assignment | `tools/test_dispatch_team_assignment.py`, migration replay SQL tests, Laravel assignment feature tests | Static contract + isolated database/runtime backend tests | No full Android dispatcher-to-field-unit E2E |
| Claimable incident path | `tools/test_dispatch_claimability_lifecycle.py`, `tools/test_field_claim_exclusivity.py`, SQL migration replay claim tests | Static contract + isolated database tests | No full Android claim UI-to-Supabase E2E |
| Assignment history and status progression | assignment-event source contracts, SQL migration replay, Laravel assignment/progress feature tests | Mixed static + isolated backend execution | No one test verifies persisted status, event history, app rendering and notifications together |
| GPS sharing | Location/RPC security SQL contracts, source contracts for assignment and freshness, Android location unit/source tests | Mixed static + isolated DB; selected JVM coverage | No instrumented real permission/foreground-service test in PR pipeline; no full incident-bound location round trip |
| Incident chat and attachments | Kotlin fake API chat tests, image fingerprint/source contracts, SQL Storage policy tests in migration replay, Laravel component tests where applicable | Component tests + isolated DB/Storage-policy SQL | No end-to-end Android upload, Storage object authorization, message persistence and recipient rendering in one run |
| Persistent in-app notifications and Realtime | Notification source contracts, notification RPC security SQL tests, FCM queue SQL tests, Android notification unit/source contracts | Mixed static + isolated SQL/JVM | No end-to-end Realtime reconnect plus authoritative inbox refresh in Android |
| FCM provider acceptance | Laravel fake FCM dispatcher tests exist in backend test suite; FCM queue/attempt SQL tests run in isolated PostgreSQL suite | Test-double/component + isolated DB | Laravel feature suite was skipped on audited PR head because an earlier Source checks failure stopped downstream CI jobs; no real provider send is part of normal PR CI |
| Device receipt/display/open/read and human response | Android notification handling unit/instrumented test source; manual emulator workflow | Conditional/manual component evidence | No controlled staging-device delivery test was run; provider acceptance must not be described as device receipt, read, acknowledgement, or response |
| Closure and post-close denial | SQL migration/runtime contracts and source contracts for status/assignment/location | Partial static + isolated database checks | No full app-to-database terminal-state E2E |
| Teardown and cross-account isolation | ownership/authorization contracts and isolated SQL privilege tests | Partial | No single synthetic run manifest proves only its own fixtures were cleaned up and all cross-role negative cases passed |

## 4. Exact audited PR run snapshot

All rows below are tied to head `4db98b3a457ac5488033947913cc5ab377f48f05`, not a later or different branch head.

| Workflow | Run | Result at inspection |
|---|---:|---|
| TACResQ Frontend Review | [45841649185](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/45841649185) | Passed |
| TACResQ quality assurance | [45841649111](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/45841649111) | Passed — local stack stress and prototype capture |
| RPC grant contract | [45841649093](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/45841649093) | Passed — migration replay and SQL contracts |
| TACResQ Security | [45841649065](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/45841649065) | In progress at inspection; web/Actions CodeQL and security guardrails passed, Android CodeQL still running; dependency review skipped because the repository variable was not enabled |
| Read-only profile verification | [45841649063](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/45841649063) | Passed |
| TACResQ CI | [45841649046](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/45841649046) | Failed overall: Source checks failed at Admin Overview/Analytics separation contract; downstream migration-replay and Laravel integration jobs were skipped. Android build/test/lint/APK job passed; advisory prototype checks passed |
| TACResQ Automated Code Review | [45841648743](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/45841648743) | Passed |
| Automatic Dependency Submission (Gradle) | [45841641144](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/45841641144) | Passed |

### Failure diagnosis and repair

The failed source step was `tools/test_admin_overview_analytics_separation_contract.py`. Inspection found its expected sentence was stale: the Admin Overview currently says “Today's activity is the exact count returned by the protected investigation feed since Manila midnight,” while the contract expected the older phrase “Today's activity is an exact count from the protected investigation feed.” This was a test-literal mismatch, not evidence of a broken Overview feature.

A test-only correction was committed as `23d6815a0d2c55b30469e526ac1fbe23a8801fa5`, updating the expected copy to match the current source. It does not change application behavior. **The corrected contract and the full CI suite must rerun on the new exact head; no pass is claimed for that repair yet.**

The next PR run (45842444081) passed the repaired Overview assertion and advanced to `tools/test_admin_recovery_workspace_contract.py`, where a second stale expectation assumed the Data & continuity navigation label was wrapped in `<strong>`; the current semantic navigation markup uses `<span>`. A test-only correction was committed as `b2c28dc80dc85ccaeacbaa3f8279e88d09a2028c`, updating the assertion to match the existing `<span>Data &amp; continuity</span>`. This does not alter application behavior. The workflow must rerun again on the latest exact head; downstream Laravel and migration jobs remain unverified until they execute.

## 5. Database testing distinction

The repository's `rpc-grant-contract.yml` starts a disposable PostgreSQL service and runs `tools/test_supabase_migration_replay.sh`. That script replays the repository's SQL migrations and runs many SQL contracts, including SOS idempotency/concurrency, assignment, claim, FCM queue, notification security, Storage policy and privilege checks. This is real SQL execution against isolated PostgreSQL.

It is not the same as starting the complete Supabase local stack. The audited workflows do not invoke `supabase start` plus `supabase test db`, and no pgTAP test suite was identified under the repository's Supabase test layout. Therefore describe this as **isolated PostgreSQL migration replay and SQL contract testing**, not as full Supabase integration testing or pgTAP.

Supabase documents pgTAP through `supabase test db` and recommends running it in CI against a local Supabase environment. This is a possible future improvement, not evidence that TACResQ currently uses it.

## 6. Recommended safe coverage improvements

Priority order:

1. **Restore CI green first.** Verify the corrected Admin source contract on the new PR head; then inspect the next exact-head failure rather than assuming all downstream jobs pass.
2. **Keep migration replay mandatory.** Preserve the independent PostgreSQL replay and privilege suite. Add any missing database assertions to that isolated environment, not the live project.
3. **Add a deterministic app-to-local-Supabase integration layer.** Use a disposable local Supabase stack with synthetic Auth users and the repository migrations. Drive the actual client/API boundaries where feasible; verify ownership, idempotency, assignment, status, chat, notification inbox and Storage authorization. No production credentials or live notifications.
4. **Add pgTAP only where it improves clarity.** Migrate or supplement SQL assertions with transaction-isolated pgTAP tests for RLS, grants, RPC functions and integrity. Avoid duplicating existing checks without a clear benefit.
5. **Run Android JVM tests on every PR.** Already present; preserve the `testDebugUnitTest` step.
6. **Keep emulator instrumentation opt-in or a separate advisory lane.** Normal PR CI currently compiles the instrumentation APK but does not execute it. A separate manual workflow exists. Do not label compile success as instrumented test success.
7. **Keep Laravel feature tests mandatory.** They are defined in CI but downstream of Source checks; ensure the repaired source job lets the PostgreSQL-backed Laravel job execute. Keep FCM provider behavior behind fake clients in routine CI.
8. **Create a controlled staging FCM verification only with explicit approval.** Use synthetic accounts and a dedicated test device/token; capture provider acceptance separately from app receipt/display/open/read. Never send test notifications to real users.
9. **Do not activate production worker scheduling as a test shortcut.** The previously audited Render/Supabase scheduling gap remains separate from test coverage and requires explicit operational approval.
10. **Keep release evidence granular.** Report each stage as executed, static-only, manual, blocked or not implemented. A collection of green component tests is not a single successful synthetic lifecycle.

## 7. Commands and evidence to retain

- Android JVM tests: `./gradlew --no-daemon testDebugUnitTest`
- Instrumentation compilation: `./gradlew --no-daemon assembleDebugAndroidTest`
- Actual emulator instrumentation: `./gradlew --no-daemon connectedDebugAndroidTest` (manual workflow)
- Supabase SQL migration replay: `bash tools/test_supabase_migration_replay.sh` in the PostgreSQL service environment
- Laravel feature suite: `php artisan test` inside the disposable backend Compose service
- Local stack smoke: `python3 tools/stress_test.py --base-url http://127.0.0.1:8000 --allow-localhost --requests 120 --concurrency 8`

For every test record the exact commit SHA, workflow/run URL, job and step, environment, test identities/fixture manifest (non-secret only), result, cleanup outcome, and known limits.

## 8. External references

- Supabase Testing Overview — pgTAP, application-level tests, and CI guidance: https://supabase.com/docs/guides/local-development/testing/overview
- Supabase CLI testing and linting — `supabase test db` runs pgTAP: https://supabase.com/docs/guides/local-development/cli/testing-and-linting
- Supabase automated database testing with GitHub Actions: https://supabase.com/docs/guides/deployment/ci/testing
- Android test types — local JVM versus instrumented device/emulator tests: https://developer.android.com/studio/test/test-in-android-studio
- Android command-line test tasks — `test` versus `connectedAndroidTest`: https://developer.android.com/studio/test/command-line

---

**Safety boundary:** This audit and matrix are documentation and a narrowly scoped test-contract correction only. No production data, live Supabase schema, Render configuration, worker schedule, FCM provider, paid service, credentials, real account, or real incident was changed or exercised.


## 10. Follow-up exact-head verification and local Supabase feasibility

### Fresh rerun after the two test-contract repairs

At branch head `0b36cf19fcee21368e522897541b3e9295150137`, the Source checks job passed, allowing both downstream jobs to start. The Android debug/release job passed its full listed steps, including JVM unit tests, instrumentation APK compilation, Android Lint, demo/Supabase debug APKs and release APK. The QA workflow passed both prototype capture and local Laravel/PostgreSQL GET stress. The separate RPC grant workflow passed. Web/Actions CodeQL and repository security guardrails passed.

The Laravel/PostgreSQL integration and the main CI clean-PostgreSQL migration replay were still running. A later commit adding the local Supabase plan changed the branch head to `63cc2e01c98d751288b21377d8bbaeca687dd048`, which superseded the previous in-progress run. Therefore:
- the Laravel feature-test step was cancelled as the prior run was superseded, and dashboard checks were skipped;
- the main CI migration replay had not completed;
- do not claim either downstream job passed on that superseded head.

Fresh exact-head runs are required for the latest branch. Current run links and results are available on PR #370.

### Local Supabase integration feasibility

The audited repository tree has neither `supabase/config.toml` nor `supabase/config.jsonc`. The Android `AppConfig.kt` hard-codes the hosted Supabase URL and publishable key. The existing migration replay script uses minimal stand-in Auth and Storage schemas, not the full Supabase stack. Consequently a full local integration test requires a configuration and client-targeting stage before a workflow can be safely added.

The staged plan is in `docs/QA_LOCAL_SUPABASE_INTEGRATION_PLAN.md` and Section 53 of the master plan. It proposes:
1. Pin CLI and add one canonical local config; prove all migrations replay with `supabase db reset --local`.
2. Add focused pgTAP coverage for RLS, grants, ownership, SOS, assignment, claimability, chat, Storage and notifications.
3. Create synthetic local Auth users and test actual RPC/Storage/Realtime behavior with per-role JWTs.
4. Add a separate debug/test-only Android local endpoint override and emulator lane only after backend integration is stable.
5. Exclude hosted Supabase, Render production, FCM provider, real GPS/user data, and external routing from ordinary CI.

The current CI's `needs: source-checks` dependency on Laravel and migration jobs is intentional fail-fast behavior. GitHub documents that dependent jobs are skipped when a prerequisite fails or is skipped unless explicitly overridden. Keep this gate; do not use `always()` just to force integration jobs past a failed source-contract suite.


## 11. RPC authorization runtime follow-up (Section 54–55)

The live callable audit added a specific runtime-test gap beyond the generic lifecycle matrix:
- `api_mark_notification_read(bigint)` remains caller-owned but lacks an active-account check in the reviewed live body. A suspended account with a still-valid JWT can therefore still mutate its own notification read marker. The correction and regression must prove denial using the same pre-suspension JWT.
- `api_unregister_fcm_token(text)` is an intentional cleanup exception: a suspended user can remove only their own exact token. Runtime tests must preserve this while denying list/register/update and cross-owner delete.
- `public.tacresq_require_incident_location()` is trigger-only. Direct client EXECUTE must be denied while actual trigger-driven DML continues to enforce the invariant.
- Admin wrapper tests must include AAL1, missing TOTP, non-Admin, and suspended Admin with same valid AAL2/TOTP JWT.
- The legacy `api_admin_list_audit_logs(integer)` grant must not change until all supported callers are traced.

The test-by-test expectations are documented in `docs/QA_RPC_AUTHORIZATION_RUNTIME_MATRIX.md`. The new cases should run against the disposable local Supabase environment described in `docs/QA_LOCAL_SUPABASE_INTEGRATION_PLAN.md`; they were not executed against the hosted project. No migration or grant change was made.
