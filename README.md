# TACResQ

TACResQ is a role-based emergency response coordination system for requesters, field responders, drivers, and dispatchers.

The repository contains a Kotlin/Jetpack Compose Android application with a Gradle build, a Supabase-backed data contract, a TACRU dispatcher dashboard, and documented mobile response workflows.

> **Safety boundary:** TACResQ is an educational and engineering project, not an emergency-services provider. Do not use it as a substitute for local emergency services or real emergency dispatch.

[![CI](https://github.com/RamProjector/TACResQ-School-Project/actions/workflows/ci.yml/badge.svg)](https://github.com/RamProjector/TACResQ-School-Project/actions/workflows/ci.yml)

## Product flows

### Requester

Submit an incident, review the location-sharing explanation, optionally share one current GPS fix, add a landmark, send an SOS, and monitor the request status.

### Responder or driver

Set duty status, review an assigned incident, open external turn-by-turn navigation, and advance the response through the allowed status sequence.

### Dispatcher

Review the incident queue, inspect available field units, confirm assignments, and review confirmed incident coordinates through the TACRU operations dashboard.

### GPS Map

Every authenticated role has a centered **GPS Map** destination in the mobile navigation shell. The map automatically frames valid incident pins when they first become available, and **Show incident pins** lets users reframe one pin or all available pins on demand. **Locate me** requests permission only after a tap, centers on one fresh device fix, and shows a device-only marker; that preview is not uploaded or continuously tracked. The map does not yet show live field-unit markers.

## Screens and wireframes

The [TACRU mobile wireframe gallery](docs/wireframes/screenshots/README.md) documents role-specific screens, consent states, safety confirmations, GPS states, dispatcher workflows, and map destinations.

The [GPS map UI recommendation](docs/MOBILE_GPS_MAP_UI_RECOMMENDATION.md) explains the centered map entry point and the boundary between confirmed coordinates and future live tracking.

## Current implementation status

| Area | Status |
|---|---|
| Android role-based Compose UI | Implemented; debug/release builds run in CI |
| Supabase Auth and Postgres RPC | Implemented |
| Requester SOS with one-time GPS sharing | Implemented |
| No-GPS and stale-GPS safety states | Implemented |
| Dispatcher queue and assignment confirmation | Implemented |
| Responder status progression | Implemented |
| TACRU web dispatcher dashboard | Implemented |
| Centered role-aware GPS Map destination | Implemented |
| Foreground responder location sharing | Implemented in source; approximate, consent-gated, and stops when the app leaves the foreground; not device-validated |
| Background responder tracking | Not implemented |
| In-app map tiles and live fleet markers | OpenStreetMap tiles and incident pins implemented; dispatcher map can show fresh (≤2 min) consented en-route unit reports from the existing Supabase RPC. Physical-device/network behavior remains unverified |
| FCM push notifications | Not yet implemented |
| Offline write synchronization | Not yet implemented |

## Android setup

The repository includes a complete Gradle project and the Gradle Wrapper. Use JDK 17 and install Android SDK Platform 37.0 plus Android SDK Build-Tools 36.0.0. Android Studio can open the repository root directly.

Build from the repository root:

```bash
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon test
./gradlew --no-daemon assembleRelease
```

On Windows, use `gradlew.bat` instead of `./gradlew`. APK outputs are written to `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`. The GitHub Actions artifact `tacresq-android-apks` publishes three distinctly named files: `tacresq-demo-debug.apk` (in-memory demo backend), `tacresq-supabase-debug.apk` (configured real Supabase backend), and `tacresq-release.apk` (unsigned unless signing secrets are configured). Both debug APKs use the same Android application ID, so installing one replaces the other on a device. The wrapper pins Gradle 9.3.1 and validates the distribution checksum; `gradle/wrapper/gradle-wrapper.jar.sha256` records the upstream wrapper-JAR checksum.

### Optional release APK signing

The Actions workflow always builds the debug APK and release variant. Without signing credentials, the release output is **unsigned** and is not ready for distribution. Debug APKs are automatically signed with Android's debug key and are suitable for testing only.

To have CI produce a signed release APK, first create and safely back up your own Android upload/release keystore. Then add these four repository **Actions secrets** under **Settings → Secrets and variables → Actions**:

- `ANDROID_SIGNING_KEYSTORE_BASE64` — the keystore file encoded as one Base64 string
- `ANDROID_SIGNING_KEY_ALIAS` — the alias inside that keystore
- `ANDROID_SIGNING_KEY_PASSWORD` — that key's password
- `ANDROID_SIGNING_STORE_PASSWORD` — the keystore password

Do not commit the keystore or paste passwords into source code, issues, or chat. Keep a separate encrypted backup: losing the keystore may prevent future updates signed with the same key. If all four secrets are present, CI decodes the keystore into the runner's temporary directory, signs the release APK, and verifies its signature. If none are present, CI continues without signing; if only some are present, CI fails rather than silently producing an unexpected artifact.

No OpenAI key, Render API key, or Supabase service-role key is required for APK compilation or release signing. Signing credentials are separate from backend connectivity.

The main Android manifest contains the app entry point, internet access, and the location-button permissions used by the current requester flow. The debug manifest allows cleartext traffic only in debug builds; release remains HTTPS-only by default. The CI workflow installs Android API 37 and runs both debug and release builds whenever the wrapper is present.

## Backend configuration

The checked-in Android configuration uses Supabase Auth and Postgres RPC. The optional Laravel adapter remains available, but the temporary Render database is not required by the current Supabase path.

For Supabase:

1. Apply the migrations in [`supabase/migrations/`](supabase/migrations/).
2. Follow [`docs/SUPABASE_BACKEND.md`](docs/SUPABASE_BACKEND.md).
3. Set `USE_FAKE_API = false` and `USE_SUPABASE_API = true`.
4. Use only a Supabase publishable client key in Android code. Never use a service-role or secret key in the client.
5. Provision responder, driver, and dispatcher accounts through a trusted administrator. Public registration creates requester accounts only.

The Supabase publishable key is client configuration, not a server secret. Database access must remain protected by authentication, Row Level Security, and the RPC role checks in the migrations.

For the Laravel adapter and API contract, see [`backend/README.md`](backend/README.md) and [`docs/API.md`](docs/API.md).

## Test accounts and backend modes

Debug APKs use the in-memory fake backend by default, with four role shortcuts on the sign-in screen. Its users and incidents are fictional and data resets when the app process restarts. Release builds always use Supabase; debug shortcuts are not included in release builds.

To test a configured Supabase project from a debug build instead, build with:

```bash
./gradlew --no-daemon assembleDebug -PuseSupabase=true
```

This changes only that debug build. It does not create Supabase Auth users; provision real test identities separately through a trusted administrator. Never use real emergency records for QA.

The documented fake accounts use password `demo123`:

| Email | Role |
|---|---|
| `requester@demo.com` | Emergency requester |
| `responder@demo.com` | Field responder |
| `driver@demo.com` | Driver |
| `dispatcher@demo.com` | Dispatcher |

Do not use fake accounts, real personal data, or real emergency data in public screenshots or deployed environments.

## TACRU dispatcher dashboard

The responsive dispatcher console is bundled into the Laravel backend from [`backend/web/`](backend/web/README.md) and served at `/dispatch/`. It uses Supabase Auth and role-checked RPC calls. The dashboard supports queue filters, field-unit availability, confirmed-coordinate review, and explicit assignment confirmation.

## Validation

Run the source-pack and workflow checks from the repository root:

```bash
python3 tools/check_android_source.py
python3 tools/test_workflow.py
python3 tools/test_prototype.py
python3 tools/visual_regression.py
```

The CI workflow type-checks and builds the dashboard, runs Laravel integration checks, validates the Android wrapper checksum, and attempts real Android debug and release builds. The source checker distinguishes missing build tooling from successful APK compilation.

For the complete operator runbook covering debug/release builds, web navigation, all-state screenshots, bounded stress tests, and static/AI-assisted security review, see [`docs/QA_WORKFLOWS.md`](docs/QA_WORKFLOWS.md).

## Repository guide

- [`app/`](app/) — Android Kotlin and Jetpack Compose application
- [`backend/`](backend/) — Laravel API and TACRU web dashboard
- [`supabase/migrations/`](supabase/migrations/) — schema, RLS, and RPC migrations
- [`docs/wireframes/screenshots/`](docs/wireframes/screenshots/) — mobile workflow screenshots
- [`docs/API.md`](docs/API.md) — API contract
- [`docs/SUPABASE_BACKEND.md`](docs/SUPABASE_BACKEND.md) — Supabase setup and provisioning
- [`docs/GPS_LOCATION_NOTES.md`](docs/GPS_LOCATION_NOTES.md) — location privacy and Android behavior
- [`docs/MOBILE_GPS_MAP_UI_RECOMMENDATION.md`](docs/MOBILE_GPS_MAP_UI_RECOMMENDATION.md) — centered GPS Map navigation
- [`PROJECT_ANALYSIS.md`](PROJECT_ANALYSIS.md) — project analysis and implementation context
- [`docs/QA_WORKFLOWS.md`](docs/QA_WORKFLOWS.md) — actionable build, browser, screenshot, stress, and security workflows
- [`CURRENT_TASK.md`](CURRENT_TASK.md) — current cross-chat task handoff and resume state
- [`AGENTS.md`](AGENTS.md) — repository continuity and safety instructions for agents and contributors

## Task continuity

Work that may be resumed by another chat is documented in [`CURRENT_TASK.md`](CURRENT_TASK.md). Read it before starting repository work and update it before stopping. The handoff never contains credentials, tokens, personal information, or real emergency data.

## Contributing and security

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request. Security reports should follow [`SECURITY.md`](SECURITY.md). Please do not include real emergency information, credentials, access tokens, or personal data in issues, screenshots, tests, or pull requests.

## License

TACResQ is licensed under the [MIT License](LICENSE).
