# TACResQ agent instructions

These instructions apply to any agent or chat working in this repository. Read this file and [`CURRENT_TASK.md`](CURRENT_TASK.md) before every task, then follow [`docs/AGENT_WORKFLOW.md`](docs/AGENT_WORKFLOW.md). If instructions conflict, the user's latest explicit request takes precedence, followed by this file and then the workflow guide.

## Task continuity

- Inspect the latest `main`, relevant source/tests, open PRs, and CI before implementation. Treat older handoff entries as history until verified against current repository state.
- After every meaningful session, update `CURRENT_TASK.md` with objective, changes, branch/PR/commit, actual validation results, blockers, and next concrete action.
- When working only through GitHub APIs, report verified remote facts; never invent a local working-tree status.
- Resume from the newest verified handoff and current repository state; do not redo completed work unless new evidence justifies it.
- Do not stop after merely describing a plan. When tools permit, implement the next safe, highest-priority task, validate it, and leave a reviewable result.

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

## Continuous quality loop

Treat every run as one iteration of a longer engineering program, not as a standalone answer. Continue in priority order across subsequent authorized runs.

1. **Establish facts:** inspect current `main`, recent commits, open PRs, CI, `CURRENT_TASK.md`, and relevant implementation/tests. Identify what is implemented, what is broken, and what is only assumed.
2. **Choose the highest-impact task:** prioritize safety/security/privacy and data integrity first; then crashes and broken emergency/GPS workflows; then backend/auth/persistence; then performance/reliability; then accessibility/navigation/UI; then missing features and refactoring.
3. **Define acceptance criteria before editing:** specify observable expected behavior, failure behavior, regression coverage, and what evidence can realistically be produced.
4. **Implement end-to-end:** trace UI → state/view-model → repository/API → authorization/database where applicable. Avoid UI-only patches when the root cause is deeper in the stack.
5. **Cover negative paths:** consider denied/revoked permissions, GPS disabled, stale or inaccurate fixes, empty/error/loading states, timeouts, cancellation, offline/slow network, duplicate requests, process death, background/resume, malformed responses, expired sessions, unauthorized access, and migration compatibility as relevant.
6. **Validate and repair:** run focused tests first, then all applicable checks. When a check fails, inspect logs, determine root cause, fix the defect, and rerun the affected checks. Do not silence, skip, weaken, or delete a meaningful test merely to get a green result.
7. **Review for regressions:** inspect the complete diff and existing flows, permissions, API contracts, navigation, accessibility, resource usage, privacy, and dependency/license/cost implications. Keep each PR coherent and avoid unrelated churn.
8. **Report honestly:** distinguish passed, failed, skipped, and not-run checks. State exact limitations. A green build, static scan, or deterministic reviewer is not proof that the whole product is bug-free.
9. **Hand off and continue:** update `CURRENT_TASK.md` with verified commit/PR/CI state, completed work, remaining risks, and one concrete next task. On the next run, resume from that handoff and re-check remote state before acting.

## Product acceptance criteria

TACResQ is an emergency-response GPS app. Never introduce blood-donor matching content, blood-donation iconography, or unrelated product concepts.

For relevant changes, check:
- GPS/map remains a clear core capability; location status, permission rationale, stale/unavailable location, map/network failures, and recovery paths are truthful and understandable.
- Emergency actions provide clear confirmation, prevent accidental duplicate submissions, and never claim success before the backend confirms it.
- Requester, responder, and dispatcher journeys remain role-appropriate and consistent with server-side authorization.
- Loading, empty, error, offline, retry, and success states are distinct; failures do not masquerade as empty data or success.
- Android lifecycle, navigation, cancellation, process recreation, and foreground/background work are handled appropriately.
- Backend contracts, RLS/RPC grants, input validation, data minimization, and location-consent rules remain secure.
- UI changes improve hierarchy, consistency, accessibility, touch targets, and performance without breaking existing workflows.
- Planned features are connected to real project architecture, not left as placeholders or mock-only flows.

Do not claim the app is fully complete or production-ready until agreed release criteria have evidence, critical journeys have appropriate integration/device validation, and unresolved release-blocking defects are absent. Keep a prioritized backlog of unverified behavior and known limitations.

## Validation and reporting

- Run all relevant automated checks available in CI and inspect their results on the exact PR head SHA before merging.
- Report each check as **passed**, **failed**, **skipped**, or **not run**. Never describe skipped emulator/UI/device review as passed.
- A passing build or static test does not prove physical-device behavior, live Supabase behavior, or production readiness.
- Review diffs for accidental secrets, unrelated edits, and weakened safety/security behavior.
- Keep the PR and handoff factual, concise, and specific about limitations.
