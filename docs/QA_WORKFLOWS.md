# TACResQ QA workflows

This repository has two kinds of client artifacts: an Android source pack and a browser-based TACRU dispatcher dashboard. The commands below are intentionally safe for demo data only. TACResQ is not an emergency-services provider; never point stress tests, screenshots, or fake accounts at a live emergency system.

## Fast pre-commit gate

Run from the repository root:

```bash
python3 tools/check_android_source.py
python3 tools/test_workflow.py
python3 tools/test_prototype.py
python3 tools/test_location_contract.py
pnpm --dir backend/web install --frozen-lockfile
pnpm --dir backend/web build
python3 tools/ai_security_review.py
```

The Android command is a source-contract check until a complete Gradle project and wrapper are checked in.

## Android debug and release builds

GitHub Actions runs `assembleDebug` and `assembleRelease` automatically when `gradlew` is present. The current repository is still an Android source pack, so CI falls back to `tools/check_android_source.py` and does not pretend that an APK was built.

When the Android project is merged, validate locally with:

```bash
./gradlew --no-daemon clean assembleDebug
./gradlew --no-daemon test
./gradlew --no-daemon assembleRelease
```

For a device/emulator validation pass, use a disposable demo account and check: sign-in for every role, SOS consent accepted/denied, stale/unavailable GPS, approximate permission revoked, responder foreground stop/resume, status transitions, reassignment, sign-out, and no location upload while not `en_route`.

## Web navigation and functionality

`tools/test_prototype.py` launches the checked-in interactive wireframe and verifies requester SOS creation, dispatcher assignment, responder status progression, all primary navigation destinations, and history. The existing `ci.yml` runs this in Chromium.

The dashboard production build is checked with:

```bash
pnpm --dir backend/web install --frozen-lockfile
VITE_BASE_PATH=/dispatch/ pnpm --dir backend/web build
```

The Laravel integration job starts `backend/compose.yaml`, checks `/up`, runs PHP feature tests, checks the `/dispatch/` document and static assets, and verifies that `config.json` is private and non-cacheable.

## Screenshot every activated state

The screenshotter drives the full deterministic prototype flow and captures all 42 activated screens/states, including role navigation, GPS consent/errors, assignment, offline status, history, and map states:

```bash
python3 -m pip install playwright pillow
python3 -m playwright install chromium
python3 tools/capture_wireframe_screenshots.py --output artifacts/screenshots
```

The generated `artifacts/screenshots/README.md` is a browsable gallery. CI uploads the entire gallery as `tacresq-screen-gallery`; map tiles are external and therefore visual regression excludes them.

## Stress smoke test

The safe stress test performs only GET requests against `/up`, `/dispatch/`, and `/dispatch/config.json`. It has explicit request and concurrency limits and refuses localhost unless opted in:

```bash
python3 tools/stress_test.py \
  --base-url http://127.0.0.1:8000 \
  --allow-localhost \
  --requests 120 \
  --concurrency 8
```

The `quality-assurance.yml` manual workflow starts an isolated Docker Compose stack and runs the same test. Use `workflow_dispatch` for larger staging tests only after reviewing the target and limits; the default is 120 requests.

## Security and AI-assisted review

`tools/ai_security_review.py` always runs deterministic guardrails for likely secrets, service-role keys in client code, unsafe HTML injection, disabled TLS verification, and production debug settings. It fails closed when a finding is present.

The optional AI pass is evidence-based and bounded. It requires `OPENAI_API_KEY` and can use `OPENAI_API_BASE` and `AI_SECURITY_MODEL`; it never reads `.env`, dependencies, binaries, or image files. Run locally only with a deliberately configured key:

```bash
OPENAI_API_KEY=... python3 tools/ai_security_review.py --ai
```

GitHub Actions also runs CodeQL and dependency review. Dependabot is configured in [`.github/dependabot.yml`](../.github/dependabot.yml) for GitHub Actions, the dashboard’s pnpm dependencies, the backend Docker image, and the future Android Gradle project. The optional AI review is available from `workflow_dispatch` or the weekly schedule. Treat model output as triage, not proof: reproduce every finding with a test, code review, or an authorized staging check. For Supabase changes, add role-based SQL tests for RLS, RPC authorization, malformed/future/stale coordinates, direct insert denial, and dispatcher-only reads before applying a migration to a real project.

## GitHub workflows

- `ci.yml`: source contracts, dashboard build, Android debug/release when available, prototype navigation/visual regression, and Laravel integration.
- `quality-assurance.yml`: full screen gallery artifact, bounded local-stack stress smoke test, CodeQL, dependency review, and optional AI-assisted security review.

All generated screenshots and test data must remain fictional. Do not commit credentials, service-role keys, access tokens, real personal information, or real emergency data.
