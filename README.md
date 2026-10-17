# TACResQ Android starter (Kotlin + Jetpack Compose)

Source files to drop into a fresh Android Studio project. Choose between an offline in-memory demo backend, a Render-hosted Laravel API, or Supabase Auth + Postgres RPC. The app defaults to the offline demo; configure one live backend when needed.

## Setup (about 10 minutes)

1. **Android Studio** (latest) > New Project > **Empty Activity**.
   - Name: `TACResQ`
   - Package name: `com.tacresq.app` (must match exactly)
   - Language: Kotlin, Build configuration: Kotlin DSL
   - Minimum SDK: **API 26**
2. Unzip this pack into the project root, so `app/` merges into your `app/`. Say yes to replace `MainActivity.kt` and `Theme.kt`.
3. In `app/build.gradle.kts`, add inside `dependencies { }`:

```kotlin
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
implementation("androidx.datastore:datastore-preferences:1.1.1")
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.retrofit2:converter-gson:2.11.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
implementation("com.google.android.gms:play-services-location:21.3.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
```

4. In `app/src/main/AndroidManifest.xml`:
   - Add above `<application>`:
     ```xml
     <uses-permission android:name="android.permission.INTERNET" />
     <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
     <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
     ```
   - Add `android:name=".TacResqApp"` to the `<application ...>` tag.
5. **Sync Gradle**, then run on an emulator (use an image with Google Play or Google APIs).
6. Emulator GPS: `...` (Extended controls) > Location > pick a point > Send. SOS requires a location no older than two minutes.

## Backend selection

Edit `AppConfig.kt`. Pick exactly one mode:

| Mode | `USE_FAKE_API` | `USE_SUPABASE_API` | Additional setting |
|---|---:|---:|---|
| Offline demo (default) | `true` | `false` | None |
| Render/Laravel | `false` | `false` | Set `BASE_URL` to `https://<service>.onrender.com/api/` |
| Supabase | `false` | `true` | Set `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` |

`AppContainer` wires the selected implementation: `FakeTacApi`, the Laravel Retrofit `ApiClient`, or `SupabaseTacApi`. The fake auto-dispatch option is disabled by default; set `FAKE_AUTO_DISPATCH = true` only to demonstrate automatic dispatch rather than dispatcher assignment.

### Render-hosted Laravel API

The repository includes a Dockerized Laravel + Sanctum API and PostgreSQL schema in [`backend/`](backend/README.md). Deploy the `backend` directory as a Render Docker Web Service, attach a Render PostgreSQL database, and configure the required server environment variables in that guide. After deployment, use the service URL for `BASE_URL` and rebuild the Android app. The app uses HTTPS for the hosted service.

The Android app itself does not need the database URL, database password, Laravel `APP_KEY`, or any server secret. Those belong only in Render’s server environment. See [`docs/API.md`](docs/API.md) for this API contract.

### Supabase backend

The Android adapter uses Supabase Auth and narrowly scoped Postgres RPC functions. Row Level Security is enabled, with role checks and incident-status rules enforced in the database. The SQL schema and security policies are in [`supabase/migrations/`](supabase/migrations/), and setup/provisioning steps are in [`docs/SUPABASE_BACKEND.md`](docs/SUPABASE_BACKEND.md).

Set `USE_FAKE_API = false` and `USE_SUPABASE_API = true`, then put your Supabase project URL and **publishable** key in `AppConfig.kt`. Never put a service-role/secret key in Android code. Public sign-up creates requesters only; provision responders, drivers, and dispatchers through a trusted administrator. The configured Supabase URL/key in the source are publishable client settings, not server secrets.

## Demo accounts (fake backend, password `demo123`)

| Email | Role |
|---|---|
| requester@demo.com | Emergency requester |
| responder@demo.com | Field responder |
| driver@demo.com | Driver |
| dispatcher@demo.com | Dispatcher |

Demo data is in memory and resets when the app process restarts. Try it end to end: sign in as the responder, tap **Available**, log out. Sign in as the requester, send an SOS. Sign in as the dispatcher to see the pending queue and manually assign the available unit. Sign back in as the responder and step it through en route, on scene, resolved. The requester's list refreshes automatically while the screen is active.

## What is in the code

```
com.tacresq.app
  AppConfig.kt             backend selection, URLs and publishable client config
  AppContainer.kt          wires the selected backend
  data/model/Models.kt     Role, DutyStatus, Incident, ... and status rules
  data/remote/TacApi.kt    shared Retrofit interface/API contract
  data/remote/SupabaseTacApi.kt Supabase Auth + Postgres RPC adapter
  data/remote/FakeTacApi  in-memory fake backend
  data/local/SessionStore  keeps the login (DataStore)
  data/repo/TacRepository one place for errors and session state
  location/LocationProvider one recent GPS fix for the SOS
  ui/                     role-based auth, requester, responder and dispatcher screens
```

## Deferred after the free UI prototype

- FCM push notifications (currently screens poll every 8 seconds)
- Live GPS tracking for responders (foreground service) and map screens (Maps SDK + API key)
- Offline queue that syncs GPS and status updates when signal returns
- Encrypting the saved token (Android Keystore)
- Production operational monitoring, abuse controls and emergency-service readiness review

This remains a school prototype, not an operational emergency-dispatch service. It does not yet implement continuous/background responder tracking, dispatcher map/fleet display, FCM push, or offline write synchronization.

## Automated workflow smoke test

Run this from the repository root:

```bash
python3 tools/test_workflow.py
```

It uses only Python's standard library and runs fully offline. It verifies the modeled flow:

`requester submits SOS → dispatcher sees and assigns → responder updates status → requester sees resolved`

If a compatible Laravel API is running with test users provisioned, point the same test at it:

```bash
python3 tools/test_workflow.py --base-url http://127.0.0.1:8000/api/
```

The offline smoke test is a Python workflow model; it does not compile or exercise the Android app. This repository is a source pack, not a complete Gradle project; Android compilation requires merging it into the fresh project described above.


## TACRU web dispatch dashboard

A responsive dispatcher console is bundled into the Laravel backend from [`backend/web/`](backend/web/README.md) and served by that backend at `/dispatch/`. The website still uses the existing Supabase Auth/RPC contract (`api_me`, `api_all_incidents`, `api_units`, and `api_assign_incident`) for authentication and data, preserving database role checks and Row Level Security. No schema migration or service-role key is required.

The console is dispatcher-only; dispatcher accounts remain administrator-provisioned. It supports queue search/status filters, field-unit availability, and an explicit confirmation before a pending incident assignment is written. Configure `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` in the backend runtime as documented in [`backend/README.md`](backend/README.md). It is still a school prototype, not a real emergency-service system; it does not add push notifications, live fleet tracking, or offline write synchronization.
