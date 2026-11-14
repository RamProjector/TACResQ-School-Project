# TACResQ Firebase Cloud Messaging setup

The Android client contains the FCM registration lifecycle, and the backend contains a trusted Firebase HTTP v1 sender. The Render deployment already has a Firebase service-account Secret File and a `GOOGLE_APPLICATION_CREDENTIALS` path configured. Preserve that existing file and path; do not recreate, duplicate, expose, or rotate the credential as part of routine dispatcher work.

**Important status distinction:** sender credentials and code exist, but the deployed Render web-service startup does not automatically run `php artisan tacresq:dispatch-fcm`. Automatic queue processing and physical-device delivery are therefore not yet verified. See [the Render execution audit](FCM_RENDER_EXECUTION_AUDIT.md). FCM is supplemental; the persistent in-app inbox remains authoritative.

## Client configuration

1. Create or select the Firebase project owned by the TACResQ deployment operator.
2. Register the Android application with the exact application ID from `app/build.gradle.kts`.
3. Supply the generated `google-services.json` through the private Android build/CI secret mechanism. Do not commit it or print it in logs.
4. Add the Google Services Gradle plugin only in the deployment build configuration if the project uses it to generate Firebase resources; the app remains buildable without Firebase configuration for local/demo builds.
5. Build and install on an Android 13+ device. After an authenticated session is available, TACResQ requests the `POST_NOTIFICATIONS` runtime permission once for that app session. The app creates an `Incident alerts` channel and only displays generic alert text. Android's FCM guidance requires this runtime grant before an app can show notifications on Android 13 and later. See the [official Android FCM setup guide](https://firebase.google.com/docs/cloud-messaging/android/get-started).

When Firebase is not configured, `PushTokenCoordinator` safely becomes a no-op. This keeps local/demo builds and non-Firebase environments functional.

## Registration lifecycle

After an authenticated session is restored or created, the app obtains the FCM token and calls the existing authenticated Supabase RPC `api_register_fcm_token`. A token refresh registers the new token before removing the previous token. This matters because Firebase documents token changes after restore to another device, uninstall/reinstall, app-data clearing, or token security events. Explicit sign-out calls `api_unregister_fcm_token` before clearing the local token state.

The token is stored only in app-private preferences. It is never logged, included in incident payloads, or exposed to the UI. Supabase RLS and the RPC's authenticated-user check remain authoritative for ownership.

## Trusted server sender

The backend contains `php artisan tacresq:dispatch-fcm`. It calls the server-only `api_claim_fcm_delivery_batch` RPC using `SUPABASE_SECRET_KEY`; the RPC atomically reserves due notification/token attempts from `fcm_delivery_attempts`. The claim query considers only notifications that are still unread, so a notification already opened in the app does not generate a later push. The delivery ledger—not the inbox read flag—tracks each notification/token pair's claim, sent state, retry schedule, and permanent failure. A sent-but-unread pair is excluded by its ledger record and cannot be repeatedly claimed. This is the distinction between inbox visibility and delivery bookkeeping.

Claims use a 10-minute lease, retryable failures use exponential backoff with jitter and the FCM `Retry-After` header, and each token pair is capped at eight attempts. Permanent failures are retained for diagnosis. The sender uses Google Application Default Credentials (ADC) via `google/auth` and sends data-only HTTP v1 messages. It deletes a token for FCM `UNREGISTERED`; for `INVALID_ARGUMENT`, it deletes only when Firebase returns the typed `google.firebase.fcm.v1.FcmError` detail with `errorCode: INVALID_ARGUMENT` and no typed `google.rpc.BadRequest` field-violation detail. A `BadRequest` means the request/payload must be corrected and the token is retained. The sender constructs a fixed, bounded data-only payload using string identifiers. The command prints aggregate counts only; it must never print tokens, provider response bodies, incident narratives, or coordinates.

The dispatcher requires `SUPABASE_URL`, server-only `SUPABASE_SECRET_KEY`, `FCM_PROJECT_ID`, and ADC credentials. On Render, ADC reads the service-account JSON file named by the existing `GOOGLE_APPLICATION_CREDENTIALS` environment variable. Preserve the current Render Secret File and exact path; do not substitute Android `google-services.json`, add a second credential variable, or copy the JSON into source control. The command accepts a maximum batch size of 50, enforced both by the PHP dispatcher and the database RPC. Do not expose this command as a public HTTP route.

### Render execution status

The existing Render web service currently starts the PHP web server only. Its startup command does not invoke the FCM dispatcher, a scheduler, or a worker. A Secret File and `GOOGLE_APPLICATION_CREDENTIALS` make the credential available to the process; they do not run the queue command.

Render Free Web Services can spin down when idle. A background loop inside that same service is not a dependable emergency-delivery mechanism while the service is asleep. Render Cron Jobs and Background Workers are not available on the Free instance tier. Do not change the plan or create a paid service without explicit approval.

A private worker/Cron Job is the cleanest isolated execution model if a paid tier is later approved. The example at [render-fcm-cron.example.yaml](../render-fcm-cron.example.yaml) is illustrative only and must not be applied automatically. Any future worker must receive the server-only Supabase key and ADC file through its own protected environment, use a batch limit no greater than 50, and pass a synthetic device test before recurring execution is enabled.

### Safe validation

Run this command in the deployed environment only when an authorized operator can execute it safely:

```sh
php artisan tacresq:dispatch-fcm --check-config
```

It validates required Supabase/FCM configuration and that ADC can mint an FCM-scoped access token. It does not claim queue access or send a notification. A successful preflight is not proof of automatic dispatch or physical-device receipt.

The Android service intentionally renders a generic message and opens TACResQ, where the authenticated app fetches the notification inbox. Do not add a Firebase service-account key to the Android app or repository. Firebase documents the `firebase.messaging` OAuth scope and the `Authorization: Bearer` request pattern in its [HTTP v1 sending guide](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

## Verification limits

CI can validate source contracts, migration replay, and application tests. It cannot validate the live Render credential mount, automatic command execution, live Firebase authentication, or physical-device delivery from the repository alone. Before operational delivery is claimed, verify the deployed preflight, automatic invocation, a synthetic non-sensitive push, and foreground/background/closed-app receipt on a dedicated test device.