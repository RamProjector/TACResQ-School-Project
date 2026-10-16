# TACResQ Android starter (Kotlin + Jetpack Compose)

Source files to drop into a fresh Android Studio project. It runs on a built-in fake backend, so it works with no server, paid API, subscription, or cloud account.

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

Try it end to end: sign in as the responder, tap **Available**, log out. Sign in as the requester, send an SOS. Sign in as the dispatcher to see the pending queue and manually assign the available unit. Sign back in as the responder and step it through en route, on scene, resolved. The requester's list updates on its own. Fake auto-dispatch is off by default; set `FAKE_AUTO_DISPATCH = true` in `AppConfig.kt` only when you specifically want the automatic demo behavior.

## Switching to the real Laravel API

The repository now includes a Render-ready Laravel + Sanctum backend and PostgreSQL schema in [`backend/`](backend/README.md). Follow that guide to deploy the API and connect the app; the offline fake backend remains the easiest way to demo without hosting.

Edit `AppConfig.kt`:

- `USE_FAKE_API = false`
- `BASE_URL`: emulator `http://10.0.2.2:8000/api/`; real phone `http://<your-PC-LAN-IP>:8000/api/` (run `php artisan serve --host=0.0.0.0`)

The routes and JSON the app expects are in `docs/API.md`. Plain `http://` only works in debug builds (`app/src/debug/AndroidManifest.xml`); use HTTPS for release.

## What is in the code

```
com.tacresq.app
  AppConfig.kt             fake/real switch, base URL
  AppContainer.kt          wires everything (no Hilt)
  data/model/Models.kt     Role, DutyStatus, Incident, ... and the status rules
  data/remote/TacApi.kt    Retrofit interface = the API contract
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

## If Gradle sync or the build fails

This was written without the Android SDK available, so it has not been compiled. Paste the first error and it can be fixed quickly.
