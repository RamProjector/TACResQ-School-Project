# TACResQ agent instructions

These instructions apply to any agent or chat working in this repository.

## Task continuity is required

Before changing code, documentation, infrastructure, or repository settings, read [`CURRENT_TASK.md`](CURRENT_TASK.md). Treat it as the current handoff for work that may be resumed by another chat.

After every meaningful work session, update `CURRENT_TASK.md` before stopping. The update must record the objective, completed work, current repository state, files changed, validation results, blockers, and the next concrete actions.

If a task stops midway, leave the repository in a safe state and document exactly where the next chat should resume. Never claim a task is complete when `CURRENT_TASK.md` still lists unfinished required actions.

## Handoff rules

- Keep the handoff factual and concise.
- Include commit IDs and deployment IDs when relevant.
- Record commands and validation results, not private credentials.
- Never put API keys, passwords, service-role keys, tokens, personal data, or real emergency data in the handoff.
- If the handoff conflicts with the current user request, follow the current user request and update the handoff.
- Check `git status`, current branch, and recent commits before writing the handoff.
- A clean working tree is required before declaring repository work complete unless the user explicitly asked to leave changes uncommitted.

## Repository safety

TACResQ is not an emergency-services provider. Preserve the project's privacy, role-authorization, location-consent, and no-fabricated-GPS boundaries. Run the relevant checks before handoff.
