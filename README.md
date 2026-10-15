# TACResQ Android starter (Kotlin + Jetpack Compose)

Source files to drop into a fresh Android Studio project. It supports an offline fake backend and a Supabase-backed mode for shared accounts and persistent dispatch data. Supabase's free tier is suitable for a school prototype, subject to the provider's current limits.

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
6. Emulator GPS: `...` (Extended controls) > Location > pick a point > Send. The SOS needs a location.

## Demo accounts (fake backend, password `demo123`)

| Email | Role |
|---|---|
| requester@demo.com | Emergency requester |
| responder@demo.com | Field responder |
| driver@demo.com | Driver |
| dispatcher@demo.com | Dispatcher (placeholder screen) |

Try it end to end: sign in as the responder, tap **Available**, log out. Sign in as the requester, send an SOS. Sign in as the dispatcher to see the queue, available units, and manual assignment controls. Sign back in as the responder and step it through en route, on scene, resolved. The requester's list updates on its own.

## Supabase backend

The app is configured to use the TACResQ Supabase project by default. It contains only a **publishable** Supabase key; never put a service-role key in Android code. The database schema and security policies are in `supabase/migrations/202610150001_tacresq_core.sql` and have been applied to the connected project.

The Android adapter uses Supabase Auth and narrowly scoped Postgres RPC functions. Row Level Security is enabled, with role checks and incident-status rules enforced in the database. Set `AppConfig.USE_FAKE_API = true` for the self-contained offline demo.

### Setting up a different Supabase project
1. Apply the SQL migration in `supabase/migrations/` to your project.
2. Copy its Project URL and **publishable** key into `AppConfig.kt`. Do not use a service-role/secret key in the client.
3. Choose whether Supabase Auth requires email confirmation. If enabled, confirm email before login; signup without a session displays a confirmation message.
4. Register requester accounts in the app. Public registration is limited to requesters. Provision responders, drivers, and dispatchers through a trusted administrator in the Supabase SQL editor, setting `role` and `duty_status` together. Example:
   ```sql
   update public.profiles
   set role = 'responder', duty_status = 'off_duty'
   where email = 'responder@example.com';
   ```
5. Run on a device with internet and location permission. SOS captures a one-time GPS location.

`docs/API.md` retains the old Laravel REST contract for reference; it is not used by the current Supabase backend. The database has audit history and location-log storage, but continuous/background GPS, the dispatcher web dashboard/map, FCM push notifications, and offline synchronization are not yet implemented in the Android client. Current screens poll for updates. This remains a prototype, not an operational emergency-dispatch service.

## What is in the code

```
com.tacresq.app
  AppConfig.kt             fake/Supabase switch and project config
  AppContainer.kt          wires everything (no Hilt)
  data/model/Models.kt     Role, DutyStatus, Incident, ... and the status rules
  data/remote/TacApi.kt    Retrofit interface = the API contract
  data/remote/SupabaseTacApi.kt Supabase Auth + Postgres RPC adapter
  data/remote/FakeTacApi   in-memory backend for demos
  data/local/SessionStore  keeps the login (DataStore)
  data/repo/TacRepository  one place for errors and session state
  location/LocationProvider  one GPS fix for the SOS
  ui/                      AppRoot (routes by role), auth, requester, responder, dispatcher
```

## Deferred after the free UI prototype

- FCM push notifications (replaces the 8-second polling in `PollWhileStarted`)
- Live GPS tracking for responders (foreground service) and map screens (Maps SDK + API key)
- Offline queue that syncs GPS and status updates when signal returns
- Dispatcher web dashboard (Laravel)
- Encrypting the saved token (Android Keystore), HTTPS-only release config

The current dispatcher console is intentionally inside the Android prototype so the complete workflow can be demonstrated before a separate Laravel web dashboard is built. See `docs/PROTOTYPE_SCOPE.md` for the requirements matrix and test checklist.

## Automated workflow smoke test

Run this from the repository root:

```bash
python3 tools/test_workflow.py
```

It uses only Python's standard library and runs fully offline. It verifies the complete prototype flow:

`requester submits SOS → dispatcher sees and assigns → responder updates status → requester sees resolved`

If a compatible Laravel API is running, point the same test at it:

```bash
python3 tools/test_workflow.py --base-url http://127.0.0.1:8000/api/
```

## Validation notes

The offline smoke test runs without Android Studio or network access. This repository is a source pack, not a complete Gradle project; Android compilation requires merging it into the fresh project described above.
