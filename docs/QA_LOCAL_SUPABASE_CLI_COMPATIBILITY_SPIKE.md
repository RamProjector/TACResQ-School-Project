# Local Supabase CLI compatibility spike — 2026-11-27

**Repository:** RamProjector/TACResQ-School-Project  
**PR:** https://github.com/RamProjector/TACResQ-School-Project/pull/370

## 1. Exact-head CI recheck

At commit 4ebe120bfa7bb02a7655f7f686bfe3c5aeabaa3e, all six PR workflow groups have completed successfully:

- TACResQ CI: source checks, Android debug/release builds, clean PostgreSQL migration replay, and Laravel/dashboard integration.
- RPC grant contract: passed.
- TACResQ Security: repository guardrails and CodeQL Android plus Web/GitHub Actions.
- TACResQ QA: advisory prototype checks and source/QA checks.
- Frontend Review: passed.
- Read-only profile verification contract: passed.

The combined commit-status endpoint returned no legacy status entries; workflow-run and job records are the evidence. This is not manual Admin UI acceptance, hosted migration validation, or device-verifier runtime proof. The PR remains open/unmerged. Any later commit needs fresh exact-head checks.

## 2. CLI release decision

Official Supabase release information shows CLI v3.0.0 as the current GA major line. CLI v3.4.0, released 2026-11-24, introduced config.jsonc as the default generated project config. The release page currently shows v3.5.0 beta and v4 prereleases, not a later GA stable release. Supabase documents that CLI v3.1.0+ reads JSONC and v3.2.0+ supports config validation.

**Proposed first pinned baseline: Supabase CLI 3.4.0.** It is a GA release, supports JSONC, and provides config validation. Do not use floating latest, beta, next, or v3.stable in the first reproducibility spike. Reassess the pin after the first stable local-stack run.

For GitHub Actions, supabase/setup-cli@v3 supports an explicit version input; specify 3.4.0. Do not omit the version because the action otherwise resolves from root lockfiles or falls back to latest. The repository currently has no root package manifest/lockfile that pins Supabase CLI.

## 3. Config format and safety

Use one canonical supabase/config.jsonc generated/validated by the pinned CLI, rather than hand-authoring guessed ports or service options. The repo currently has neither supabase/config.toml nor supabase/config.jsonc.

The local config must:
- use a unique TACResQ local project identifier;
- enable only services needed for Stage A and the Auth → Edge Runtime → PostgREST proof;
- use local-only Auth settings and synthetic fixtures;
- avoid any remotes mapping, project link, hosted URL, or production secret;
- be validated with supabase config validate and reviewed against the actual config schema;
- be exercised with explicit local-target commands only.

CLI v3's local-by-default behavior is an additional safeguard, not a substitute for a loopback URL/project guard. Do not run supabase link, supabase db push --remote, supabase functions deploy, remote reset, or any hosted command in the test workflow. The harness must fail closed if the API URL is not loopback/local or if a hosted project reference is detected.

## 4. Stage A implementation sequence

1. Add the canonical config with the pinned CLI and validate it.
2. Start the full local stack on an isolated GitHub Actions runner; record exact CLI, Docker, and service versions.
3. Wait for Auth, PostgREST, database, and Functions health before continuing.
4. Run an explicit local migration reset and compare migrations, triggers, policies, extensions, grants, and RPC contracts with the existing PostgreSQL replay.
5. Resolve migration incompatibilities before adding JWT propagation tests; do not weaken or remove the existing PostgreSQL 16/17 checks.
6. Add the temporary test-only SQL claim probe and temporary Edge Function outside production migrations/deployment paths.
7. Create two synthetic users through local Auth Admin API; sign in through local Auth; verify each incoming JWT returns its own auth.uid() and session_id from PostgREST.
8. Test missing, malformed, expired, anonymous-only, forged-body-ID, and concurrent requests. Never log JWTs or secrets.
9. Always stop the local stack and remove volumes, even on failure. Upload only sanitized logs.

Keep the new lane advisory/manual initially. Do not add Android emulator endpoint overrides until Stage A–C are repeatable.

## 5. Decision / blockers

No config or workflow was added in this spike because no local Supabase CLI/Docker runtime is exposed through the repository tools, and the actual TACResQ migration set has not yet been replayed on the full Supabase stack. Choosing a pin is research, not proof of compatibility.

**Next concrete task:** implement the isolated Stage A config/CI spike on PR #370 using the pinned 3.4.0 CLI, then inspect the first local-stack migration failure (if any) from sanitized CI logs. Do not proceed to trusted device challenge writes until the JWT-context proof passes.

## 6. Official references

- CLI v3 behavior: https://supabase.com/changelog/supabase-cli-v3
- CLI JSONC default and v3.4.0 release: https://supabase.com/changelog/supabase-cli-config-jsonc-default
- Config formats and minimum CLI versions: https://supabase.com/docs/guides/local-development/cli/config-formats
- Local config management: https://supabase.com/docs/guides/local-development/managing-config
- CLI setup action and explicit version pinning: https://github.com/supabase/setup-cli/blob/main/docs/index.md
- Local reset behavior: https://supabase.com/docs/reference/cli/supabase-db-reset

## 7. Advisory workflow added to the existing CI

The first compatibility lane is now wired into the existing TACResQ CI workflow rather than a newly introduced workflow file. This matters because GitHub Actions may not execute a newly added PR workflow until its definition exists on the default branch. The job is therefore part of the already-established PR CI definition.

- Job: `local-supabase-migration-compatibility` in `.github/workflows/ci.yml`.
- It runs only when migration/function/config-plan/CI workflow paths change, or when manually dispatched.
- It is explicitly advisory with job-level `continue-on-error: true`; existing source checks, PostgreSQL 16/17 replay, Android builds, Laravel integration, and security gates remain unchanged.
- It uses the SHA-pinned `supabase/setup-cli` action and exact CLI version 3.4.0.
- On the fresh runner it creates or validates one local config, starts local Supabase, performs `supabase db reset --local`, and always stops the stack with `--no-backup`.
- It does not receive Supabase credentials, does not link a project, does not invoke remote commands, and redacts local JWT/key output from startup logs.
- The standalone workflow prototype was removed and its job folded into the existing CI workflow to ensure the PR can actually exercise it.

**First run status:** the workflow change is on PR #370 at head `5bb0116ea22454d45076dd125cef43309d3237d1`. The six existing PR workflow groups were queued/in progress at the last read; no result is yet claimed for the new advisory job. Inspect the exact job and sanitized startup/migration output after the run completes.

