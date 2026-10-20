# Current task handoff

**Last updated:** 2026-10-20
**Repository:** `RamProjector/TACResQ-School-Project`
**Base branch:** `main` at `320ddec6db46f7909c6ddf4c1493f4915f212702` (verified before this task)
**Active task branch:** `agent/setup-coding-agent-workflow`
**Latest observed task-branch commit before this handoff update:** `03398157a18ad5c85d4f766598047686364c61a6`
**Working-tree note:** Work was performed through the GitHub connector; no local checkout/status was available to inspect. Do not infer a local working-tree state from remote branch commits.

## Current objective

Set up TACResQ for autonomous, feature-by-feature development with validation and pull requests prepared for human review. Do not merge or deploy as part of the default workflow.

## Current session — in progress

- Inspected repository metadata, default branch, top-level directory structure, agent instructions, handoff, project analysis, plan, contributing guide, CI workflow, latest commits, branches, open PRs, and current GitHub Actions state.
- Verified `main` was at `320ddec6db46f7909c6ddf4c1493f4915f212702`; it is the only existing branch and is not protected according to GitHub's branch metadata.
- Latest main CI run checked: [run 39402253764](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39402253764), successful. Source checks, prototype/visual checks, and Laravel/dashboard integration passed. Android debug/release build steps were skipped because no Gradle wrapper is checked in.
- No open pull requests were present at inspection time.
- Created the task branch `agent/setup-coding-agent-workflow` from `main`.
- Updated `AGENTS.md` with review-first feature work, task-scoped branches, explicit non-merge/non-deploy defaults, validation honesty, and side-effect boundaries. Commit: `0cbc0e139efdbd25d65424de0bbce7a6ed2870f8`.
- Added `docs/AGENT_WORKFLOW.md` describing task intake, implementation loop, validation, PR requirements, handoff, and repository-level enforcement limits. Commit: `f895a8080eae5b0fb7d17f6b189983ee0848c5c3`.
- Updated `CONTRIBUTING.md` to link the workflow and align contribution/PR guidance. Commit: `03398157a18ad5c85d4f766598047686364c61a6`.

**Not yet done:** update the handoff for this session is being written now; verify latest CI on the branch/PR; open a focused PR and leave it unmerged.

## Project structure

- `app/`: Kotlin/Jetpack Compose Android source pack with requester, responder/driver, dispatcher, auth, GPS Map, API, repository, and location code.
- `supabase/migrations/`: Postgres schema, RLS/RPC and incident/location changes.
- `backend/src/`: Laravel API adapter.
- `backend/web/`: React/TypeScript dispatcher dashboard, served by Laravel at `/dispatch/`.
- `tools/`, `tests/`, `.github/workflows/`: source checks, workflow/prototype tests, visual regression, security/stress helpers, and GitHub Actions.

## Implementation and safety boundaries

- The Android code is currently documented as a source pack, not a complete Gradle project; debug/release compilation and device behavior remain unverified.
- Requester GPS is one-time and user initiated.
- Responder location sharing is foreground-only, consent-gated, approximate, and implemented in source but not device-validated. No background tracking is implemented.
- Embedded map tiles/live fleet markers, FCM push notifications, and offline write synchronization are not implemented.
- The repository is an educational prototype, not an emergency-services provider.
- Preserve role authorization, explicit location consent, minimized location access, truthful stale/unavailable states, and no fabricated GPS. Never put secrets, personal information, or real emergency data in source, tests, screenshots, commits, or handoffs.
- The responder-location migration `202610200002_responder_location_tracking.sql` was previously applied to the explicitly approved Supabase project `TACResQ-School-Project` as migration version `20261020062532`. Do not reapply it there.

## Validation and evidence

- The latest observed main CI run above completed successfully for the checks described. This is evidence about the main commit only; the agent workflow branch has not yet had PR CI validation.
- Android debug and release build steps were skipped, not passed.
- Prior repository notes record passing source/workflow/prototype/location-contract/stress/dashboard checks for earlier feature work. Those historical results do not substitute for checks on new changes.
- This session used GitHub connector reads and commits; no local shell, Android SDK, emulator, or test-device run occurred.
- Do not claim the workflow branch is protected or that PR review is enforced by GitHub: main branch protection was absent at inspection, and the available integration does not expose branch-protection write settings.

## Next concrete actions

1. Finish this handoff update and verify the task branch's changed files/commit history.
2. Open a focused PR from `agent/setup-coding-agent-workflow` to `main` with a summary of the agent workflow and the latest CI outcomes.
3. Inspect the PR's Actions checks and report passed/failed/skipped status. Leave it open for user review; do not merge.
4. After the workflow is accepted, the next recommended separately scoped engineering task is to make the Android source pack a complete buildable Gradle project, then validate debug/release builds and device behavior. Treat this as a separate task/PR.
