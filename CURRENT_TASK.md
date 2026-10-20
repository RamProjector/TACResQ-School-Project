# Current task handoff

**Last updated:** 2026-10-20
**Repository:** `RamProjector/TACResQ-School-Project`
**Branch:** `main`
**Current commit:** `6874a94 Plan dispatcher responder tracking workflow`
**Working tree:** clean and synchronized with `origin/main`

## Objective

Maintain TACResQ as a clean, publicly presentable project with a role-based Android client source pack, Supabase-backed workflows, TACRU dispatcher dashboard, documented GPS boundaries, and resumable work across chats.

## Completed

- Added the public-project README and product positioning.
- Added the MIT license.
- Added `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, and `SUPPORT.md`.
- Added issue templates, issue configuration, and a pull-request template.
- Added public GitHub description and topics.
- Added centered, role-aware GPS Map navigation to the Android source pack.
- Added the full-screen confirmed-coordinate GPS Map destination.
- Added wireframe and screenshot documentation for requester, responder, and dispatcher flows.
- Removed stale merged branches and local generated build artifacts.
- Removed the misleading dispatcher self-location route planner; the web dashboard keeps incident-coordinate links, while responder navigation remains in the assigned responder client.
- Added an explicit responder confirmation before advancing an assigned incident to `en_route`; the confirmation states that live responder GPS is not shared by the current build.
- Wrote `plan.md` with the agreed implementation plan: dispatcher-only responder tracking while en route, explicit responder start/stop lifecycle, incident-bound backend authorization, freshness states, realtime fallback, notifications, offline behavior, audit history, and deferred requester-facing GPS.

## Current implementation boundaries

- Requester GPS is one-time, user-initiated sharing.
- The GPS Map shows confirmed incident coordinates and timestamps only.
- Continuous/background responder tracking is not implemented.
- In-app map tiles, live fleet markers, FCM push notifications, and offline write synchronization are not implemented.
- The web route planner does not write browser coordinates to Supabase or present them as responder/unit tracking; it is a local navigation handoff only. Google Maps selects the available route and traffic may change.
- The dispatcher dashboard does not request or track dispatcher browser location.
- Responder navigation already opens the confirmed incident coordinate locally; responder live-location upload is not implemented yet.
- The repository is an Android source pack without a checked-in Gradle wrapper.

## Validation

The last validation passed:

```text
python3 tools/check_android_source.py
python3 tools/test_workflow.py
python3 tools/test_prototype.py
pnpm --dir backend/web build
```

The Android and workflow checks passed again after the web change and responder confirmation update. `git diff --check` also passed. The Phase 1 responder confirmation is validated locally and is ready to commit.

## Resume checklist

1. Read `AGENTS.md` and this file.
2. Run `git status --short --branch` and `git log -5 --oneline --decorate`.
3. Fetch and inspect the latest `main` and pull requests before editing.
4. Confirm whether the user wants Android source, wireframes, backend, Supabase, dashboard, or public-repository work.
5. Continue Phase 1 from `plan.md`: add server-backed assignment/status hardening and incident detail/history before implementing responder location ingestion.

## Next recommended work

- Keep the README and public community files synchronized with the actual implementation.
- Add a real in-app map provider only after selecting and documenting the provider/API-key boundary.
- Implement responder/requester live-location lifecycle only with explicit consent, freshness, offline, and privacy handling.
- Add a Gradle wrapper and complete Android build project when the Android project is ready to be checked in.
