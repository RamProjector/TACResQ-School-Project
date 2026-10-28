# TACResQ agent instructions

These instructions apply to any agent or chat working in this repository. Read this file and [`CURRENT_TASK.md`](CURRENT_TASK.md) before every task, then follow [`docs/AGENT_WORKFLOW.md`](docs/AGENT_WORKFLOW.md). If instructions conflict, the user's latest explicit request takes precedence, followed by this file and then the workflow guide.

## Task continuity

- Inspect the latest `main`, relevant source/tests, open PRs, and CI before implementation. Treat older handoff entries as history until verified against current repository state.
- After every meaningful session, update `CURRENT_TASK.md` with objective, changes, branch/PR/commit, actual validation results, blockers, and next concrete action.
- When working only through GitHub APIs, report verified remote facts; never invent a local working-tree status.

## Autonomous implementation and delivery

- Work autonomously through inspection, implementation, automated validation, PR creation, and eligible PR merging. Do not stop to ask for approval at each routine coding step.
- Prefer one coherent feature PR. If the user explicitly requests a consolidated feature update, inspect existing open PRs and branches first, reuse or consolidate work where practical, and avoid duplicating features already implemented.
- Never commit feature changes directly to `main`. Create a task-scoped branch from the latest `main`, using `agent/<short-task-slug>`.
- **Do not require manual UI review, screenshots, visual sign-off, emulator runs, or physical-device review as blockers** when the user has instructed the agent to skip them. Mark them `skipped` or `not run`, never as passed. Do not add emulator/UI-review work merely to satisfy an old checklist.
- Continue to run relevant automated checks: source/static tests, unit/integration tests, security/authorization contracts, database migration replay in isolated test environments, and Android/web/backend builds where supported. Fix failures before merging. A CodeRabbit or frontend-review report may inform work, but manual UI approval is not a merge gate under this workflow.
- **Merge eligible PRs after required automated CI checks pass**, the branch is mergeable, and there are no known unresolved critical security/privacy defects or failing required checks. Use the repository's normal merge method. If required checks fail, conflict, or cannot be verified, do not force-merge; diagnose/fix and rerun checks.
- Do not enable auto-merge unless needed and explicitly requested. Do not deploy, apply live database migrations, modify live data, provision real accounts, change production configuration, or alter repository security settings unless the user has explicitly authorized that specific side effect.
- If GitHub branch protection/rulesets prevent a merge, report the exact blocker rather than bypassing protections.

## Scope and product safety

TACResQ is an emergency-response coordination project, not itself an emergency-services provider. Preserve its separation from LifeLink and blood-donor functionality. Do not claim operational readiness without evidence.

- Preserve server-side role authorization, least privilege, RLS/RPC grants, location consent, truthful GPS accuracy/freshness/unavailable states, duplicate-SOS safeguards, and data minimization.
- Never fabricate GPS, successful delivery, incident status, backend connectivity, test results, or deployment state.
- Use synthetic test incidents and test locations only. Never commit credentials, tokens, passwords, personal information, real incident coordinates, or real emergency records.
- Treat authentication, authorization, GPS/location collection, Supabase migrations/RLS/RPCs, and deployment configuration as high-risk: add targeted automated negative-case coverage and document remaining live/device verification gaps.
- Do not change the technology stack, introduce paid services, or perform destructive/production changes without explicit authorization.

## Validation and reporting

- Run all relevant automated checks available in CI and inspect their results on the exact PR head SHA before merging.
- Report each check as **passed**, **failed**, **skipped**, or **not run**. Never describe skipped emulator/UI/device review as passed.
- A passing build or static test does not prove physical-device behavior, live Supabase behavior, or production readiness.
- Review diffs for accidental secrets, unrelated edits, and weakened safety/security behavior.
- Keep the PR and handoff factual, concise, and specific about limitations.
