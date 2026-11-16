# TACResQ Next-Session Handoff

**Snapshot:** 2026-11-16  
**Base `main` at branch creation:** `ee1f999632b43b6d65ed1b3b1f359fef6c737fcb`

This page is a handoff snapshot. Re-fetch the PRs and exact-head checks before continuing; do not assume the statuses below are still current.

## Active PRs at snapshot

### PR #312 — Durable incident chat text retries
- URL: https://github.com/RamProjector/TACResQ-School-Project/pull/312
- Branch: `agent/durable-chat-text-outbox`
- Head: `6997e6ea42cd18854bcd4704e842212d47b9c5c3`
- Status: open, not merged.
- At the last check: migration replay, Laravel/dashboard integration, static security review, CodeQL Android, CodeQL Web/Actions, repository security guardrails, source checks, static UI/UX review, advisory checks, and RPC grant contract had passed. Android debug/release builds were still running. CodeRabbit skipped; manual trigger is required.
- Follow-up added instrumentation tests for outbox restore/account isolation/replacement rejection/matching-ID clear. Exact-head CI must compile those tests. The current workflow compiles instrumentation tests but does not execute them on a device.
- Do not merge until all required checks finish on the latest exact head and the diff is reviewed. Image attachment retry persistence remains separate follow-up work.

### PR #313 — Dispatcher units RPC search-path hardening
- URL: https://github.com/RamProjector/TACResQ-School-Project/pull/313
- Branch: `agent/pin-api-units-search-path`
- Head at snapshot: `a6947ae46f83c67c5bdf459306130dba6de5346d`
- Status: open, not merged; checks were queued/running.
- Adds `ALTER FUNCTION public.api_units() SET search_path = pg_catalog`.
- Preserves the SECURITY DEFINER function, dispatcher check, function body, and existing grants.
- Adds a regression contract and migration-history entry.
- No live migration was applied.

## Next safe actions

1. Re-fetch PR #312 and #313 current head SHAs and check runs. Never use this handoff's old SHA if the branch has advanced.
2. Review the complete exact-head diffs and any review comments.
3. Wait for required Android builds, source checks, migration replay, security checks, and CodeQL to complete. Do not merge on a static review alone.
4. If checks fail, inspect the failing job logs and fix only the demonstrated issue; then re-run checks on the new head.
5. After the current security migration PR is validated, continue the remaining Supabase advisor review without broad grants or policies. Investigate the four missing FK indexes and six RLS init-plan warnings with query-plan evidence before changing indexes/policies.
6. Implement durable image attachment retries as a separate PR after the text outbox is reviewed and tested.
7. Reconcile the GPS provenance gap only after deciding what provider metadata Android actually exposes. Do not invent satellite/Wi-Fi/cellular attribution.
8. Continue physical-device QA using synthetic accounts and locations. CI does not prove GPS, Doze, permission, OEM background, or route behavior.

## Operational restrictions

- No paid services or OpenAI API keys without explicit approval.
- No production FCM Cron activation, pg_cron/pg_net activation, Vault secret creation, Render environment change, live FCM send, or infrastructure deployment without explicit approval.
- No production data deletion, destructive migration, or schema rewrite.
- Keep the in-app notification inbox authoritative and FCM supplementary.
- Do not represent TACResQ as an operational emergency-service provider.
