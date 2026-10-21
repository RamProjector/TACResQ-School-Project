# TACResQ QA workflows

This repository contains a Kotlin/Jetpack Compose Android application and a browser-based TACRU dispatcher dashboard. The commands below are intentionally safe for demo data only. TACResQ is not an emergency-services provider; never point stress tests, screenshots, or fake accounts at a live emergency system.

## Fast pre-commit gate

Run from the repository root:

```bash
python3 tools/check_android_source.py
python3 tools/test_workflow.py
python3 tools/test_prototype.py
python3 tools/test_location_contract.py
python3 tools/test_stress_test.py
pnpm --dir backend/web install --frozen-lockfile
pnpm --dir backend/web build
python3 tools/ai_security_review.py
```

The Android source check also validates the checked-in Gradle project and verifies the Gradle Wrapper JAR checksum.

## Android debug and release builds

GitHub Actions installs Android SDK Platform 37 and Build-Tools 36.0.0, verifies the wrapper checksum, then runs `assembleDebug` and `assembleRelease`. If the wrapper is absent, CI falls back to source-contract validation and clearly reports that no APK was built.

The debug APK is automatically signed with Android's debug key for testing. A release APK is unsigned unless all four repository Actions secrets are configured: `ANDROID_SIGNING_KEYSTORE_BASE64`, `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD`, and `ANDROID_SIGNING_STORE_PASSWORD`. Create and securely back up your own keystore before configuring them; never commit it or paste its passwords into chat. With all four secrets, CI decodes the keystore into runner temporary storage and verifies the signed APK. With none, CI reports an unsigned release; a partial configuration fails instead of silently producing an unsigned artifact. For routine phone testing, use the debug APK artifact.

Validate locally with:

```bash
./gradlew --no-daemon clean assembleDebug
./gradlew --no-daemon test
./gradlew --no-daemon assembleRelease
```

For a device/emulator validation pass, install the debug APK and use the four demo-account shortcuts on the sign-in screen. The default debug build uses an in-memory fake backend; demo accounts and incidents are fictional and reset when the app process restarts. Check sign-in for every role, SOS consent accepted/denied, stale/unavailable GPS, approximate permission revoked, responder foreground stop/resume, status transitions, reassignment, sign-out, and no location upload while not `en_route`.

To validate against configured Supabase from a debug build, use `./gradlew --no-daemon assembleDebug -PuseSupabase=true`. This does not provision users; create disposable test identities through a trusted Supabase administrator. Release builds always select Supabase and never enable the fake backend.

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

The generated `artifacts/screenshots/README.md` is a browsable gallery. CI uploads the entire gallery as `tacresq-screen-gallery`; map tiles are external and therefore visual regression excludes them. The stable visual gate accepts only sub-0.05% raster noise from browser/Pillow anti-aliasing changes; larger layout or content changes still fail.

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

GitHub Actions also runs CodeQL and dependency review. Dependabot is configured in [`.github/dependabot.yml`](../.github/dependabot.yml) for GitHub Actions, the dashboard’s pnpm dependencies, and the backend Docker image. The Android Gradle project and wrapper are now checked in; Dependabot can be configured to update Gradle/AGP once the initial wrapper build is confirmed green. The optional AI review is available from `workflow_dispatch` or the weekly schedule. Treat model output as triage, not proof: reproduce every finding with a test, code review, or an authorized staging check. For Supabase changes, add role-based SQL tests for RLS, RPC authorization, malformed/future/stale coordinates, direct insert denial, and dispatcher-only reads before applying a migration to a real project.

## GitHub workflows

- `ci.yml`: source contracts, dashboard build, Android debug/release when available, prototype navigation/visual regression, and Laravel integration.
- `quality-assurance.yml`: full screen gallery artifact, bounded local-stack stress smoke test, CodeQL, dependency review, and optional AI-assisted security review.

All generated screenshots and test data must remain fictional. Do not commit credentials, service-role keys, access tokens, real personal information, or real emergency data.
