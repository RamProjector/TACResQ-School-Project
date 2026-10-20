# Contributing to TACResQ

Thank you for helping improve TACResQ. Contributions should preserve the project's role-based workflows, privacy boundaries, and operationally cautious language.

## Before opening a pull request

1. Create a focused branch from `main`.
2. Do not use real emergency records, personal data, phone numbers, credentials, or live incident coordinates.
3. Keep Supabase service-role keys, Render credentials, Laravel secrets, and database passwords out of the repository.
4. Update the relevant documentation and wireframe screenshots when a user-facing flow changes.
5. Run the checks that apply to your change:

```bash
python3 tools/check_android_source.py
python3 tools/test_workflow.py
python3 tools/test_prototype.py
python3 tools/visual_regression.py
```

For dashboard changes, also run:

```bash
cd backend/web
pnpm install --frozen-lockfile
pnpm build
```

## Pull requests

Describe the user-facing change, affected roles, backend or migration impact, privacy implications, and test results. Include screenshots for visual changes. Keep pull requests small enough to review and use the pull-request template.

Changes to Supabase migrations, access policies, role checks, location handling, authentication, or deployment configuration require additional security review.

## Design and product boundaries

- Keep requester SOS separate from the centered GPS Map destination.
- Do not imply continuous GPS tracking when only a one-time fix exists.
- Show stale, unavailable, or missing coordinates explicitly.
- Keep role permissions enforced by the backend and database, not only by the UI.
- Preserve confirmation gates for emergency submission and incident assignment.

## Reporting issues

Use the issue templates for bugs and feature requests. Do not publish security vulnerabilities or sensitive operational details in a public issue; follow [`SECURITY.md`](SECURITY.md) instead.
