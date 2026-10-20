# Review-first autonomous coding workflow

This workflow is for an agent or developer implementing TACResQ one feature at a time. It is designed for incremental work, reviewable changes, and honest reporting of validation limits.

## Delivery contract

- **Scope:** one specific feature, bug, or maintenance task per task/PR. Split unrelated requests into separate tasks.
- **Autonomy:** the agent may inspect the code, plan, implement, test, and prepare a pull request without asking for approval at each low-risk step.
- **Review boundary:** the default delivery is a pull request. Do not merge, enable auto-merge, deploy, run a production migration, change live data, or change repository security settings without explicit authorization for that action.
- **Base branch:** `main`. Never commit feature changes directly to `main`.
- **Safety:** preserve role authorization, location consent, data minimization, stale/unavailable labels, and the project's educational-prototype boundary. Do not use real emergency data.

## Start-of-task checklist

1. Read `AGENTS.md` and `CURRENT_TASK.md`.
2. Check the latest `main` commit, branch list, relevant open issues/PRs, and current CI status.
3. Read the relevant source, tests, migrations, and documentation before editing.
4. State the goal, scope, affected roles/modules, acceptance criteria, risks, and validation plan.
5. Create a feature branch from the latest `main`, named `agent/<short-task-slug>`. If the branch cannot be created from the latest base, stop before writing and reconcile the state.

Do not assume an old conversation, handoff note, or README reflects the current code until checked against the current branch.

## Implementation loop

1. Make the smallest cohesive change that satisfies the acceptance criteria.
2. Keep public interfaces and existing behavior compatible unless the task explicitly calls for a breaking change.
3. Update focused tests and user-facing documentation with behavior changes. UI changes should update relevant visual baselines/screenshots when the existing harness applies.
4. For authorization, authentication, GPS/location, Supabase RLS/RPC, schema, or deployment changes, add specific negative-case coverage and call out any tests that require staging or device access.
5. Avoid mixing unrelated dependency updates, cleanup, redesigns, or refactors into the feature branch.
6. Never place secrets, tokens, personal information, real incident coordinates, or real emergency records in source, fixtures, screenshots, logs, commits, or PR descriptions.

## Validation

Run the checks relevant to the touched area. Start with the project's source/workflow tests:

```bash
python3 tools/check_android_source.py
python3 tools/test_workflow.py
python3 tools/test_prototype.py
python3 tools/test_location_contract.py
python3 tools/test_stress_test.py
python3 tools/visual_regression.py
```

For dispatcher-dashboard changes:

```bash
pnpm --dir backend/web install --frozen-lockfile
pnpm --dir backend/web build
```

For backend changes, use the documented Laravel/Compose integration checks. For database changes, use an isolated test/staging project first and include unauthorized-role, invalid-input, and lifecycle tests. Do not apply migrations to the live project without explicit approval.

For Android changes, run debug/release Gradle builds and device checks once a complete Gradle project and Android toolchain are available. The current repository may be only a source pack; source/static checks are not a substitute for compilation or device validation.

Report each check as **passed**, **failed**, **skipped**, or **not run**. Include links to CI runs where available. Never describe a skipped build as a pass. Never claim a device, database, visual, security, or deployment test happened unless the result was actually observed.

## Pull request requirements

Before opening the PR:

- Confirm the base is the latest `main` and the branch contains only the scoped task.
- Review the diff for secrets, accidental generated files, unrelated edits, and unsafe weakening of authorization or privacy boundaries.
- Run `git diff --check` when working in a local checkout.
- Update `CURRENT_TASK.md` with the task, changed files, validations, unresolved gaps, and next action.
- Confirm the latest CI results for the proposed commits where available.

The PR description should include:

1. **Problem and intended result**
2. **Scope and affected roles/components**
3. **Implementation summary**
4. **Security/privacy and data-impact assessment**
5. **Validation:** explicit passed/failed/skipped/not-run status
6. **Known limitations and manual checks still required**
7. **Screenshots** for meaningful UI changes

Leave the PR open for user review. Do not merge it or enable auto-merge by default.

## Handoff and continuity

At the end of each meaningful session, update `CURRENT_TASK.md` to record:

- Current objective and status.
- Branch, latest relevant commit, and PR link/number if one exists.
- Files changed and completed work.
- Tests actually executed and their results.
- Blockers, external actions still needed, and the next concrete step.

Do not invent local working-tree status when operating only through GitHub APIs. Report remote branch/commit facts instead. Keep credentials and sensitive operational information out of the handoff.

## Repository-level enforcement

Agent instructions are guidance, not a server-side access control. If `main` is unprotected, tell the user that review-before-merge is not technically enforced. GitHub branch protection or rulesets, required status checks, and required PR reviews must be configured separately by an authorized repository administrator. Do not claim those settings are enabled unless they have been verified.
