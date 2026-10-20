# TACResQ agent instructions

These instructions apply to any agent or chat working in this repository.

## Task continuity is required

Before changing code, documentation, infrastructure, or repository settings, read [`CURRENT_TASK.md`](CURRENT_TASK.md). Treat it as the current handoff for work that may be resumed by another chat.

After every meaningful work session, update `CURRENT_TASK.md` before stopping. The update must record the objective, completed work, current repository state, files changed, validation results, blockers, and the next concrete actions.

If a task stops midway, leave the repository in a safe state and document exactly where the next chat should resume. Never claim a task is complete when `CURRENT_TASK.md` still lists unfinished required actions.

## Review-first autonomous coding workflow

Follow [`docs/AGENT_WORKFLOW.md`](docs/AGENT_WORKFLOW.md) for the standard feature lifecycle.

- Handle one coherent, user-visible feature or maintenance task at a time. Do not bundle unrelated refactors, dependency upgrades, or drive-by cleanup.
- Inspect the latest `main`, the relevant source, current issues/PRs, and this handoff before deciding how to implement a task.
- **Never commit task changes directly to `main`.** Create a task-scoped branch from the latest `main`, using `agent/<short-task-slug>` for agent-managed work. If a safe branch cannot be created, do not write to `main`.
- Validate the change, summarize limitations honestly, and prepare a focused pull request with the scope, impact, security/privacy implications, and test results.
- **Do not merge a pull request, enable auto-merge, or deploy changes unless the user explicitly authorizes that separate action.** The default delivery state is a reviewable PR.
- Repository instructions do not enforce GitHub settings. If `main` is unprotected, report that limitation; do not claim review is technically enforced until branch protection or a ruleset requires it.

## Handoff rules

- Keep the handoff factual and concise.
- Include commit IDs and pull-request/deployment URLs when relevant.
- Record commands and validation results, not private credentials.
- Never put API keys, passwords, service-role keys, tokens, personal data, or real emergency data in the handoff.
- If the handoff conflicts with the current user request, follow the current user request and update the handoff.
- Check `git status`, current branch, and recent commits before writing the handoff when a local checkout is available. When work uses only the GitHub connector, state the remote branch/commit facts you verified and do not invent a local working-tree status.
- A clean working tree is required before declaring local repository work complete unless the user explicitly asked to leave changes uncommitted.

## Security and side-effect boundaries

TACResQ is not an emergency-services provider. Preserve the project's privacy, role-authorization, location-consent, and no-fabricated-GPS boundaries. Run the relevant checks before handoff.

- Never publish credentials, personal information, real emergency data, or actual incident coordinates.
- Treat authentication, authorization, location collection, Supabase migrations/RLS/RPCs, and deployment configuration as high-risk surfaces. Add targeted validation and clearly document any coverage gap.
- Do not apply a migration to a live Supabase project, change live data, deploy, or modify repository security settings without explicit user authorization for that specific action.
- Never claim a build, test, device run, security review, or deployment succeeded unless its result was actually observed. Clearly distinguish skipped checks from passed checks.
