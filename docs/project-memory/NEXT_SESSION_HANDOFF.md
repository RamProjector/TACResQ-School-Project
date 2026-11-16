# TACResQ Next-Session Handoff

**Snapshot:** 2026-11-16  
**Main after PR #313 merge:** `62b04f719c79af21a4e4812fb5f30331da5ba964`

Re-fetch PR heads and exact-head checks before continuing. This page is a snapshot, not a live dashboard.

## Recently merged

### PR #312 — Durable incident chat text retries
- URL: https://github.com/RamProjector/TACResQ-School-Project/pull/312
- Squash commit: `28d4e4f24ab1c202c066257068b23979fadca4e6`.
- Adds encrypted, account-bound text outbox persistence and restores the original client message ID across screen/process data-layer recreation.
- Exact-head Android debug/release builds, source checks, clean PostgreSQL migration replay, Laravel/dashboard integration, security guardrails, and individual Android/Web CodeQL checks passed. Aggregate CodeQL was neutral because a workflow configuration was not found; CodeRabbit skipped because manual triggering is required.
- Instrumentation tests were compiled but not executed on an emulator or physical device. Device validation remains required.
- Image attachment retry persistence is deliberately separate follow-up work.

### PR #313 — Dispatcher units RPC search-path hardening
- URL: https://github.com/RamProjector/TACResQ-School-Project/pull/313
- Squash commit: `62b04f719c79af21a4e4812fb5f30331da5ba964`.
- Adds a forward-only migration pinning `public.api_units()` to `search_path = pg_catalog`.
- Preserves SECURITY DEFINER behavior, dispatcher authorization, function body, and existing grants.
- Exact-head migration replay, Laravel/dashboard integration, Android debug/release builds, source checks, security review, repository guardrails, UI review, RPC grant contract, and individual CodeQL Android/Web checks passed. Aggregate CodeQL was neutral; CodeRabbit skipped.
- The migration is committed but has **not** been applied to the live Supabase project.

## Active PR at snapshot

### PR #314 — Project memory and documentation reconciliation
- URL: https://github.com/RamProjector/TACResQ-School-Project/pull/314
- Branch: `docs/project-memory-and-baseline`
- Head at last poll: `18c60a551e74c24955164ca90cb9389ead334476`
- Status: open, not merged; checks were running/queued.
- Adds durable decisions, a 2020–2029 research evidence register, implementation baseline, and next-session handoff.
- Reconciles the stale `PROJECT_ANALYSIS.md`, corrects the backend routing provider documentation, and links memory from the root README.
- It also records the fused-location provenance limitation and the current Supabase performance-advisor findings.

## Next safe actions

1. Re-fetch PR #314's current head SHA and check runs. Review its final documentation diff and merge only after required exact-head checks pass.
2. Do not apply PR #313's migration to live Supabase without the appropriate operator approval; keep the checked-in migration and live migration ledger clearly distinguished.
3. Investigate the four missing FK indexes using the exact child columns and delete/update/query patterns. The inspected tables had only 0–2 live rows, so don't claim a measured speedup or remove indexes based on current advisor counts.
4. Review the six RLS initplan policies and consider `(select auth.uid())` / `(select helper())` only where the expression is row-independent and authorization semantics remain identical. Keep grants and RLS as separate controls.
5. Implement durable image attachment retry as a separate PR after text outbox device validation.
6. Reconcile GPS provider provenance only after confirming which metadata Android actually exposes. A fused API label is not proof of GNSS/Wi-Fi/cellular source.
7. Continue physical-device QA with synthetic accounts and locations. CI does not prove GPS, Doze, permission, OEM background, route, or real notification behavior.

## Operational restrictions

- No paid services or OpenAI API keys without explicit approval.
- No production FCM Cron activation, pg_cron/pg_net activation, Vault secret creation, Render environment change, live FCM send, or infrastructure deployment without explicit approval.
- No production data deletion, destructive migration, or schema rewrite.
- Keep the in-app notification inbox authoritative and FCM supplementary.
- Do not represent TACResQ as an operational emergency-service provider.
