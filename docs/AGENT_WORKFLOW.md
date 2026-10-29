# TACResQ autonomous implementation workflow

This workflow supports continuous implementation, automated validation, pull requests, and merging eligible changes. Read `AGENTS.md` and `CURRENT_TASK.md` first. The user's latest explicit instruction takes precedence.

## Delivery contract

- Inspect the latest `main`, relevant source/tests, current open PRs, and CI before editing.
- Work on a task-scoped branch from `main`; never commit feature changes directly to `main`.
- Prefer one coherent feature PR. When a user asks for a consolidated update, inventory existing branches/PRs first to avoid duplicate implementation and consolidate related work where practical.
- Keep credentials, private personal data, real emergency records, and actual incident coordinates out of source, fixtures, screenshots, logs, commits, and PR descriptions.
- Preserve role authorization, location consent, data minimization, truthful status/location reporting, and the educational-prototype boundary.
- Never introduce paid services or switch stacks without a documented reason and explicit authorization where required.

## Continuous improvement loop

Every run must make forward progress when a safe, actionable task is available. Do not respond with only a plan or a generic status report.

1. Re-read `AGENTS.md`, `CURRENT_TASK.md`, and the current remote repository state.
2. Check open PRs and recent commits to avoid duplicate work; inspect current CI on the exact relevant commit.
3. Select the highest-priority verified defect or incomplete feature using the ordering in `AGENTS.md`.
4. State measurable acceptance criteria, then implement the complete fix across the affected layers.
5. Add focused regression tests and meaningful negative-path coverage.
6. Run focused checks, then all applicable automated CI checks. Diagnose and fix failures rather than suppressing them.
7. Inspect the full diff for regressions, security/privacy problems, unrelated edits, secret exposure, and unsafe production side effects.
8. Open/update a focused PR, record exact validation evidence, and merge only when eligible under the policy below.
9. Update `CURRENT_TASK.md` with verified progress and the next concrete task so a later run can continue without repeating work.

If blocked by missing access, unavailable credentials, an external service, or a check that cannot be run, document the precise blocker and continue with another independent safe task where possible. Never invent successful results.

## Manual UI and emulator checks

Manual UI review, screenshots, visual sign-off, emulator runs, and physical-device checks are **not required blockers** when the user has instructed the agent to skip them. Do not spend the task on these checks or claim they passed. Record them as skipped/not run and continue using automated evidence. Automated source checks and automated visual regression tests that already run in CI remain in scope unless the user specifically asks to skip those too.

## Implementation and automated validation

1. Read the relevant implementation, tests, migrations, and docs before making changes.
2. Implement the requested behavior and focused automated tests.
3. Run relevant checks available to the environment/CI, such as:
   ```bash
   python3 tools/check_android_source.py
   python3 tools/test_workflow.py
   python3 tools/test_prototype.py
   python3 tools/test_location_contract.py
   python3 tools/test_stress_test.py
   python3 tools/visual_regression.py
   ```
4. For web/dashboard changes, run the documented frozen-lockfile install and build. For backend/database changes, run the applicable integration suite and isolated migration replay. Never apply migrations to the live project without explicit authorization.
5. For Android changes, use CI debug/release builds and applicable source/contract tests. Emulator and device validation may be skipped under the user's instruction.
6. Report every check as **passed**, **failed**, **skipped**, or **not run**. Static checks and builds do not prove live-backend or physical-device behavior.

## Pull requests and merging

- Update `CURRENT_TASK.md` during each meaningful work session.
- PR descriptions must summarize the problem, scope, implementation, security/privacy impact, automated validation, skipped checks, and known limitations. Screenshots are optional and not a merge prerequisite when manual UI review is waived.
- Inspect the exact head SHA's required CI/workflow results before merging.
- **Merge an eligible PR when required automated checks pass**, it is mergeable, and no known unresolved critical security/privacy defects remain. Do not force-merge failed checks or conflicts. Fix and rerun checks first.
- Do not deploy, modify live data, apply live migrations, provision real accounts, change production configuration, or alter repository security settings without explicit authorization.
- If branch protection or repository policy blocks a merge, report the blocker; do not bypass it.

## Handoff

Record the objective, completed changes, branch/commit/PR links, validation results, skipped checks, blockers, and next action in `CURRENT_TASK.md`. When operating through GitHub APIs only, report verified remote state and do not invent local checkout details.
