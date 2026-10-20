# Contributing to TACResQ

Thank you for helping improve TACResQ. Contributions should preserve the project's role-based workflows, privacy boundaries, and operationally cautious language.

For agent-managed development and resumable work, follow [`AGENTS.md`](AGENTS.md), [`CURRENT_TASK.md`](CURRENT_TASK.md), and the [review-first autonomous coding workflow](docs/AGENT_WORKFLOW.md).

## Before opening a pull request

1. Read the agent instructions and current task handoff.
2. Create a focused branch from the latest `main`. Do not commit feature changes directly to `main`.
3. Keep the task/PR limited to one coherent feature, bug fix, or maintenance objective.
4. Do not use real emergency records, personal data, phone numbers, credentials, or live incident coordinates.
5. Keep Supabase service-role keys, Render credentials, Laravel secrets, and database passwords out of the repository.
6. Update the relevant documentation and wireframe screenshots when a user-facing flow changes.
7. Run the checks that apply to your change:

```bash
python3 tools/check_android_source.py
python3 tools/test_workflow.py
python3 tools/test_prototype.py
python3 tools/visual_regression.py
```

The location contract and stress utility tests are also available:

```bash
python3 tools/test_location_contract.py
python3 tools/test_stress_test.py
```

For dashboard changes, also run:

```bash
pnpm --dir backend/web install --frozen-lockfile
pnpm --dir backend/web build
```

## Pull requests

Describe the user-facing change, affected roles, backend or migration impact, privacy implications, and test results. Clearly distinguish passed, failed, skipped, and not-run checks. Include screenshots for visual changes. Keep pull requests small enough to review and use the pull-request template.

The default agent delivery is an open pull request for review. Do not merge, enable auto-merge, deploy, apply a production migration, or change repository security settings unless the user explicitly authorizes that separate action.

Changes to Supabase migrations, access policies, role checks, location handling, authentication, or deployment configuration require additional security review. Use an isolated test/staging project before database migration changes.

## Design and product boundaries

- Keep requester SOS separate from the centered GPS Map destination.
- Do not imply continuous GPS tracking when only a one-time fix exists.
- Show stale, unavailable, or missing coordinates explicitly.
- Keep role permissions enforced by the backend and database, not only by the UI.
- Preserve confirmation gates for emergency submission and incident assignment.
- TACResQ is an educational project, not an emergency-services provider. Do not imply operational emergency-service reliability without evidence.

## Reporting issues

Use the issue templates for bugs and feature requests. Do not publish security vulnerabilities or sensitive operational details in a public issue; follow [`SECURITY.md`](SECURITY.md) instead.
