# TACResQ Firebase Cloud Messaging setup

The Android client contains the FCM registration lifecycle, and the backend now contains a credential-free source implementation for trusted Firebase HTTP v1 delivery. Push delivery remains disabled until deployment supplies private Firebase and Supabase server configuration. No Firebase credential is committed to this repository.

## Client configuration

1. Create or select a Firebase project owned by the TACResQ deployment operator.
2. Register the Android application with the exact application ID from `app/build.gradle.kts`.
3. Supply the generated `google-services.json` through the private Android build/CI secret mechanism. Do not commit it or print it in logs.
4. Add the Google Services Gradle plugin only in the deployment build configuration if the project uses it to generate Firebase resources; the app remains buildable without Firebase configuration for local/demo builds.
5. Build and install on an Android 13+ device. After an authenticated session is available, TACResQ requests the `POST_NOTIFICATIONS` runtime permission once for that app session. The app creates an `Incident alerts` channel and only displays generic alert text. Android's FCM guidance requires this runtime grant before an app can show notifications on Android 13 and later. See the [official Android FCM setup guide](https://firebase.google.com/docs/cloud-messaging/android/get-started).

When Firebase is not configured, `PushTokenCoordinator` safely becomes a no-op. This keeps local/demo builds and non-Firebase environments functional.

## Registration lifecycle

After an authenticated session is restored or created, the app obtains the FCM token and calls the existing authenticated Supabase RPC `api_register_fcm_token`. A token refresh registers the new token before removing the previous token. This matters because Firebase documents token changes after restore to another device, uninstall/reinstall, app-data clearing, or token security events. Explicit sign-out calls `api_unregister_fcm_token` before clearing the local token state.

The token is stored only in app-private preferences. It is never logged, included in incident payloads, or exposed to the UI. Supabase RLS and the RPC's authenticated-user check remain authoritative for ownership.

## Trusted server sender

The backend now contains `php artisan tacresq:dispatch-fcm`. It reads unread `user_notifications` rows and the recipient's registered `fcm_tokens` through the Supabase REST API using the server-only `SUPABASE_SERVICE_ROLE_KEY`. It sends data-only HTTP v1 messages through `google/auth` ADC, records one delivery attempt per notification/token pair in `fcm_delivery_attempts`, and removes tokens when Firebase reports `UNREGISTERED` or `INVALID_ARGUMENT`. The command prints counts only; it never prints tokens, provider response bodies, incident narratives, or coordinates.

Configure the worker environment privately with `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `FCM_PROJECT_ID`, and an ADC mechanism. On a non-Google host, Firebase documents using `GOOGLE_APPLICATION_CREDENTIALS` to point to a protected service-account JSON file. The file must be mounted or injected by the deployment secret manager, outside the repository and outside the Android artifact. Run the command from a scheduler or worker at a short interval, for example `php artisan tacresq:dispatch-fcm --limit=100`. Do not expose this command as a public HTTP route.

### Render placement

For Render, add the Firebase service-account JSON under the worker service's **Environment → Secret Files**, for example with the filename `firebase-service-account.json`. Render mounts Docker secret files at `/etc/secrets/<filename>`, so set this environment variable on the same worker service:

```env
GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/firebase-service-account.json
```

Add `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` (or the current Supabase server secret supplied through that compatibility variable), `FCM_PROJECT_ID`, `FCM_BATCH_LIMIT`, and `FCM_HTTP_TIMEOUT` under that service's private **Environment Variables**. The file must be a Google service-account JSON containing `type: service_account`; the Android `google-services.json` is not an ADC credential. Attach the secret file and variables to the Render Cron Job or worker that runs the command, not only to the public dispatcher web service. A Cron Job command can be:

```sh
php artisan tacresq:dispatch-fcm --limit=100
```

Render's [environment and secret-file guide](https://render.com/docs/configure-environment-variables) documents the `/etc/secrets/` path, and its [Cron Job guide](https://render.com/docs/cronjobs) documents the scheduled command and service-specific environment variables.

The Android service intentionally renders a generic message and opens TACResQ, where the authenticated app fetches the notification inbox. Do not add a Firebase service-account key to the Android app or repository. A server sender must use FCM HTTP v1 with server-side credentials or application-default credentials; Firebase documents the `firebase.messaging` OAuth scope and the `Authorization: Bearer` request pattern in its [HTTP v1 sending guide](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

The Render web service currently has only browser-safe Supabase configuration and does not run this worker automatically. Adding the private service-role key, Firebase project ID, ADC secret, or a scheduler is a separate deployment change and has not been performed by this implementation task.

## Verification limits in this environment

The sandbox has no Android SDK, PHP runtime, physical device, or Firebase project credentials, so APK compilation, PHP execution, and end-to-end delivery cannot be run here. The offline contracts are:

```sh
python3 tools/test_fcm_registration_contract.py
python3 tools/test_fcm_server_contract.py
```

CI/device/deployment validation must verify Firebase configuration, Android 13 permission denial/grant behavior, token rotation, sign-out removal, the command's ADC authentication, invalid-token cleanup, duplicate suppression, and a synthetic non-sensitive notification.
