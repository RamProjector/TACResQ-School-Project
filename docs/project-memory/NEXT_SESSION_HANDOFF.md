# TACResQ Next-Session Handoff

**Snapshot:** 2026-11-16  
**Main after PR #312 merge:** `28d4e4f24ab1c202c066257068b23979fadca4e6`

Re-fetch PR heads and exact-head checks before continuing. This page is a snapshot, not a live dashboard.

## Recently merged

### PR #312 — Durable incident chat text retries
- URL: https://github.com/RamProjector/TACResQ-School-Project/pull/312
- Squash commit: `28d4e4f24ab1c202c066257068b23979fadca4e6`.
- Adds encrypted, account-bound text outbox persistence and restores the original client message ID across screen/process data-layer recreation.
- Exact-head Android debug/release builds, source checks, clean PostgreSQL migration replay, Laravel/dashboard integration, security guardrails, and individual Android/Web CodeQL checks passed. Aggregate CodeQL was neutral because a workflow configuration was not found; CodeRabbit skipped because manual triggering is required.
- Instrumentation tests were compiled but not executed on an emulator or physical device. Device validation remains required.
- Image attachment retry persistence is deliberately separate follow-up work.

## Active PRs at snapshot

### PR #313 — Dispatcher units RPC search-path hardening
- URL: https://github.com/RamProjector/TACResQ-School-Project/pull/313
- Branch: `agent/pin-api-units-search-path`
- Head: `a6947ae46f83c67c5bdf459306130dba6de5346d`
- Status: open, not merged.
- Adds `ALTER FUNCTION public.api_units() SET search_path = pg_catalog`.
- Preserves SECURITY DEFINER behavior, dispatcher check, function body, and existing grants.
- At the last exact-head poll, migration replay, Laravel/dashboard integration, Android debug/release, source checks, security review, repository guardrails, UI review, and RPC grant contract passed. CodeQL Android was still running; aggregate CodeQL was neutral; CodeRabbit skipped.
- No live migration was applied.

### PR #314 — Project memory and documentation reconciliation
- URL: https://github.com/RamProjector/TACResQ-School-Project/pull/314
- Branch: `docs/project-memory-and-baseline`
- Head before this handoff update: `7c95f2d3d84a98e5b28a20967e1689b751171c3d`; the handoff update will advance it.
- Status: open, not merged.
- Adds durable decisions, a 2020–2029 research evidence register, implementation baseline, and next-session handoff.
- Reconciles the stale `PROJECT_ANALYSIS.md`, corrects the backend routing provider documentation, and links memory from the root README.
- At the last poll, static security review, static UI/UX review, and RPC grant contract passed; source checks, Android builds, migration replay, backend integration, and CodeQL were running or queued. CodeRabbit skipped.

## Next safe actions

1. Re-fetch PR #313 and #314 current head SHAs and check runs.
2. Wait for all required checks on exact heads; inspect any failing job logs and fix only demonstrated issues.
3. Review and merge PR #313 if all required checks pass and no blocking review findings appear.
4. Review and merge PR #314 if its documentation claims and CI pass; verify its handoff snapshot is still accurate at merge time.
5. After security hardening, investigate the remaining Supabase advisor findings with query-plan and permission evidence. Do not add broad RLS policies or drop indexes just to silence warnings.
6. Implement durable image attachment retry as a separate PR after text outbox device validation.
7. Reconcile GPS provider provenance only after confirming which metadata Android actually exposes. Do not invent satellite/Wi-Fi/cellular attribution.
8. Continue physical-device QA with synthetic accounts and locations. CI does not prove GPS, Doze, permission, OEM background, route, or real notification behavior.

## Operational restrictions

- No paid services or OpenAI API keys without explicit approval.
- No production FCM Cron activation, pg_cron/pg_net activation, Vault secret creation, Render environment change, live FCM send, or infrastructure deployment without explicit approval.
- No production data deletion, destructive migration, or schema rewrite.
- Keep the in-app notification inbox authoritative and FCM supplementary.
- Do not represent TACResQ as an operational emergency-service provider.
