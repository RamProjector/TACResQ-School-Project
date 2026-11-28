# Disposable local Supabase integration test plan

**Research date:** 2026-11-26  
**Repository:** `RamProjector/TACResQ-School-Project`  
**Related PR:** [#370](https://github.com/RamProjector/TACResQ-School-Project/pull/370)  
**Companion documents:** `docs/QA_SYNTHETIC_SOS_LIFECYCLE_VERIFICATION.md`, `docs/QA_CI_COVERAGE_MATRIX.md`

## 1. Decision

Add a **separate, disposable local Supabase integration layer** in stages. Do not replace the existing isolated PostgreSQL migration replay, do not point tests at the connected/production Supabase project, and do not turn on FCM, Render production workers, or external routing.

The immediate next step is a configuration-and-migration compatibility spike, not a full Android E2E workflow. The repository currently has `supabase/migrations/` and one Edge Function, but no checked-in `supabase/config.toml` or `supabase/config.jsonc`. The current `tools/test_supabase_migration_replay.sh` creates minimal stand-in `auth` and `storage` schemas/functions and applies migrations to plain PostgreSQL. It is a useful database regression suite, but it cannot prove that the same migrations work against the actual Supabase-managed Auth, Storage, Realtime, API gateway, and local configuration.

## 2. What the local stack should add

Use the Supabase CLI to run an isolated local stack with real local Supabase services:
- PostgreSQL with the actual migration set and Supabase-managed schemas/extensions;
- Auth signup/sign-in and JWT issuance;
- PostgREST RPC invocation under real JWT claims and database roles;
- Storage API and object-policy enforcement;
- Realtime subscription and reconnect behavior;
- Edge Functions only if an isolated function test is explicitly needed.

Supabase's current CLI guide describes running the full stack locally and in CI, with Docker/container runtime support. It creates a local project configuration file; current documentation defaults to `supabase/config.jsonc` and also supports TOML. Pin a compatible CLI version before committing configuration. A local project is not linked to the hosted project unless someone explicitly links it.

## 3. Repository-specific prerequisites and constraints

### 3.1 Configuration gap

- No `supabase/config.toml` or `supabase/config.jsonc` exists at the audited head.
- No root package manifest exists to pin the Supabase CLI as a repository dev dependency.
- The existing workflow uses Node/pnpm for the Admin web build, but that alone does not install or pin the Supabase CLI.
- Add one canonical CLI config and a reproducible CLI version/install mechanism as a separately reviewed change. Do not generate a speculative config and assume the migrations work.

### 3.2 Android endpoint is currently fixed

`app/src/main/java/com/tacresq/app/AppConfig.kt` currently hard-codes the hosted Supabase URL and publishable key. The Supabase-backed debug build uses this config, while demo mode uses an in-memory API. Therefore an emulator cannot safely target local Supabase just by starting Docker.

A later implementation needs a **debug/test-only endpoint override**:
- allow a local Supabase URL and its local publishable/anon key to be injected at build time for the dedicated integration variant;
- emulator uses the host gateway address (commonly `10.0.2.2`) to reach the host-published local Supabase API port;
- do not change release constants, production build behavior, or normal debug defaults;
- do not place the local service-role key in Android, APK resources, build artifacts, screenshots, logs, or test reports;
- add a build/source contract that prevents a local override from being enabled in release variants.

The publishable/anon key is a client key, not an administrative secret. The local service-role key is privileged and must remain server-side in the test runner only.

### 3.3 Identity and role setup

Use synthetic Auth users created through the local Auth Admin API using the local-only service-role key. Let the existing Auth signup trigger create each profile. Then, only inside the disposable local database, assign the required operational role and duty state through a controlled fixture setup step. Do not grant roles through public signup metadata and do not directly insert rows into `auth.users`.

Keep Supabase Auth/profile IDs separate from Laravel Sanctum users. Never match or join test identities by email across the two systems.

### 3.4 External side effects excluded

The routine local integration suite must not:
- call the connected project `kttguamhznoflzagfutr` or any hosted Supabase URL;
- call Render production APIs;
- send FCM messages to Google or real devices;
- invoke the legacy `tacresq-notify` Edge Function with FCM service-account secrets;
- enable or schedule the Render FCM worker, Supabase Cron, or pg_net;
- call public geocoding/routing providers or use real GPS coordinates/people;
- use real emergency reports, user contact details, or production tokens.

Test notification rows and Realtime are local database behavior. FCM provider acceptance and Android device receipt remain separate controlled staging tests and are not part of this local suite.

## 4. Staged architecture

### Stage A — CLI/configuration and migration compatibility

1. Select and pin one Supabase CLI version compatible with the repository's migration syntax and CI runtime.
2. Add a single canonical Supabase project config (`config.jsonc` or `config.toml`) with local ports and required Auth/Storage/Realtime settings only.
3. Run the full local stack on an isolated runner.
4. Execute `supabase db reset --local` and capture the exact migration failure, if any.
5. Compare the resulting schema, functions, grants, RLS policies, triggers, and Storage buckets with the existing PostgreSQL replay expectations.
6. Keep `tools/test_supabase_migration_replay.sh` as a faster independent contract suite; do not remove or silently replace it.
7. Do not run `supabase link`, `supabase db push`, `supabase functions deploy`, or any hosted-project command in this workflow.

**Exit criteria:** clean local reset succeeds; all repository migrations apply; critical schema, RPC signatures, grants, RLS and trigger contracts are verified; local credentials and URLs are generated per run; teardown deletes the disposable stack/volumes.

### Stage B — Database-native pgTAP coverage

Add focused pgTAP suites under the Supabase CLI's expected test directory after Stage A is stable. Cover:
- role/grant and RLS positive/negative cases;
- requester ownership and active-incident uniqueness;
- durable submission key and same-payload retry idempotency;
- dispatcher team assignment and assignment-history integrity;
- separate claimable incident path with concurrent single-winner semantics;
- allowed dispatcher-managed status transitions and post-close rejection;
- field-unit location writer scope and assignment-bound access;
- chat participant authorization and upload reservation/object path policies;
- notification inbox ownership and caller-scoped read state;
- account suspension blocking relevant mutations and preserving audit integrity; notification mark-read denial for a suspended caller with the same valid JWT; and the narrow FCM self-unregister exception for suspended accounts.

Use explicit `SET LOCAL ROLE`, JWT claim settings, transaction rollback/isolated fixtures, and deterministic IDs. Do not combine dispatcher-managed team assignment and claimable incident claiming into one fixture. Preserve the current distinction between source event, database record, FCM provider acceptance, device delivery, and user action.

### Stage C — Local Auth and REST/RPC integration

A runner-side fixture harness should:
1. create unique synthetic requester, dispatcher, responder, and driver Auth users;
2. confirm the signup trigger creates the corresponding profiles;
3. set operational roles only in the local disposable DB;
4. sign in each actor through local Auth and retain only that actor's short-lived access token in process memory;
5. invoke real PostgREST RPCs using the local publishable key and that actor's bearer token;
6. verify returned data and database side effects using the local database;
7. test negative cross-role/ownership cases and stale/expired credentials;
8. clean up only the fixture IDs created by the current run, then destroy the local stack.

The first runtime journey should be narrow: requester creates an SOS, retries with the same submission key and identical payload, and the database proves there is exactly one incident. Next add dispatcher team assignment and a separate claimability concurrency scenario. Then extend to progress, chat, Storage, notification inbox and Realtime.

### Stage D — Android emulator connection (separate lane)

Only after Stage A–C pass:
- introduce a debug/test-only Supabase endpoint override in Gradle/AppConfig;
- start local Supabase before the emulator test and wait for Auth, REST and Storage health;
- configure the emulator to reach the host's local API endpoint;
- run a small number of instrumented tests against the actual Supabase-backed Android API implementation;
- validate login, session restore/sign-out, requester SOS submit/retry, dispatcher queue/assignment, and one role-protected read;
- keep GPS permission/foreground-service and device-notification behavior in separate instrumented/manual scenarios because host GPS and FCM do not become realistic merely by running the local stack;
- use a disposable emulator and synthetic accounts only.

This lane is resource-heavy. Start as manual or advisory CI; promote it to a required PR gate only after stable, repeatable, bounded runtime and cleanup are demonstrated.

## 5. Suggested test topology

```text
GitHub Actions isolated runner
  ├── Supabase CLI (pinned)
  ├── Supabase local stack
  │    ├── Postgres + migrations + RLS/RPC
  │    ├── Auth (synthetic users)
  │    ├── PostgREST
  │    ├── Storage
  │    └── Realtime
  ├── Fixture harness (local service-role key; server-side only)
  ├── pgTAP + HTTP RPC/Auth/Storage tests (publishable key + actor JWT)
  └── Optional emulator lane
       └── Android debug integration variant → local Supabase host gateway
```

The Render API and external FCM/routing providers are intentionally outside this topology. Test the Render bridge separately against disposable backend services; do not conflate it with Android's direct Supabase transport.

## 6. CI workflow recommendation

Keep the current fast source-check gate and independent PostgreSQL migration replay. Add a separate `local-supabase-integration` job after Stage A is proven locally:
- install the pinned CLI;
- start local services;
- wait for health;
- reset/replay migrations;
- run pgTAP;
- run local Auth/RPC/Storage integration tests;
- collect sanitized logs on failure;
- always stop Supabase and remove its volumes.

Initially make it manual/advisory while image pulls, startup, runtime, and teardown are measured. Once stable, decide whether it is a required PR job. Do not use `always()` to bypass the existing source-check prerequisite for backend/migration jobs: GitHub documents that dependent jobs are skipped when a prerequisite fails unless explicitly overridden. That fail-fast dependency is appropriate for the current safety gate.

If the Android emulator lane is added, keep it separate from the local Supabase backend integration job so a flaky emulator cannot hide database-level test results.

## 7. Coverage and proof boundaries

| Claim | Acceptable evidence |
|---|---|
| Migrations work against Supabase-managed local services | `supabase db reset --local` succeeds on a clean stack |
| Database authorization works | pgTAP/runtime SQL tests under intended roles and JWT claims |
| Auth lifecycle works | Local Auth API signup/sign-in/refresh/sign-out with generated test users |
| RPC authorization works | HTTP RPC call with actor JWT plus database side-effect assertions |
| Storage policy works | Authenticated Storage API upload/download/delete and negative cross-user tests |
| Realtime works | Local subscription receives a committed authorized change and recovers after reconnect |
| Android can use local backend | Instrumented app test with debug-only local endpoint override |
| FCM provider accepted a message | Separate controlled provider test; never inferred from local inbox row |
| Android device received/displayed a push | Dedicated test device evidence; not proven by local Supabase or FCM fake |
| A responder acknowledged/responded | Explicit application event/UI action; not inferred from provider/device receipt |

## 8. Acceptance checklist for implementation

- [ ] One canonical, pinned Supabase CLI config/version is checked in.
- [ ] Clean local Supabase startup and teardown are repeatable in CI.
- [ ] All migrations replay on the actual Supabase local stack.
- [ ] pgTAP tests cover critical RLS, RPC grants, ownership, and state transitions.
- [ ] Auth fixtures are synthetic and created through local Auth API.
- [ ] Role assignment occurs only in disposable local fixture setup.
- [ ] Local service-role key never enters Android or uploaded artifacts.
- [ ] Local test harness fails closed if configured with any hosted URL/project ref.
- [ ] Android endpoint override is debug/test-only and impossible in release.
- [ ] Separate team-assignment and claimability paths remain separate.
- [ ] FCM, Render production, routing providers, and production data are excluded.
- [ ] Cleanup proves all run-owned fixtures are removed and the local stack is destroyed.
- [ ] CI output reports exact commit, test stage, environment, and executed-versus-skipped status.

## 9. Official references

- Supabase CLI local development: https://supabase.com/docs/guides/local-development/cli/getting-started
- Supabase CLI testing and linting: https://supabase.com/docs/guides/local-development/cli/testing-and-linting
- Supabase automated database testing in GitHub Actions: https://supabase.com/docs/guides/deployment/ci/testing
- GitHub Actions job dependencies and fail-fast behavior: https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-jobs

---

**Implementation status:** design only. No Supabase CLI configuration, package dependency, migration, pgTAP test, emulator endpoint override, workflow, credentials, production project, or external provider has been changed or exercised.


### Additional RPC authorization regressions from the live callable audit

Include these cases in the first runtime test batches:
- `api_mark_notification_read`: active owner can mark own row; cross-owner ID cannot mutate; suspended user with the same pre-suspension valid JWT must be denied after the proposed active-profile correction.
- `api_unregister_fcm_token`: suspended user can remove only their own exact token; cannot list/register/update tokens or remove another user's token. Preserve the deliberate cleanup-only helper and transaction-local exception.
- `tacresq_require_incident_location()`: direct client EXECUTE is denied, but real trigger-driven DML still invokes the trigger and enforces the location invariant.
- Admin wrappers: AAL1, missing TOTP, non-Admin, and suspended Admin with same valid AAL2/TOTP JWT are denied; active Admin with required MFA succeeds.
- Legacy `api_admin_list_audit_logs(integer)`: preserve the compatibility grant until all supported clients are traced.
- Grant inventory: assert exact API signatures, security modes, search paths, and anon/authenticated EXECUTE rather than schema-wide function counts.

The live mark-read correction is not implemented by this test plan. A forward migration and explicit deployment approval are separate prerequisites.
