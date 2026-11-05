# TACResQ Firebase Cloud Messaging setup

The Android client now contains the FCM registration lifecycle, but push delivery remains disabled until the deployment supplies a Firebase project configuration and a trusted server sender. No Firebase credential is committed to this repository.

## Client configuration

1. Create or select a Firebase project owned by the TACResQ deployment operator.
2. Register the Android application with the exact application ID from `app/build.gradle.kts`.
3. Supply the generated `google-services.json` through the private Android build/CI secret mechanism. Do not commit it or print it in logs.
4. Add the Google Services Gradle plugin only in the deployment build configuration if the project uses it to generate Firebase resources; the app deliberately remains buildable without Firebase configuration for local/demo builds.
5. Build and install on an Android 13+ device. After an authenticated session is available, TACResQ requests the `POST_NOTIFICATIONS` runtime permission once for that app session. The app creates an `Incident alerts` channel and only displays generic alert text. Android's FCM guidance requires this runtime grant before an app can show notifications on Android 13 and later. See the [official Android FCM setup guide](https://firebase.google.com/docs/cloud-messaging/android/get-started).

When Firebase is not configured, `PushTokenCoordinator` safely becomes a no-op. This keeps local/demo builds and non-Firebase environments functional.

## Registration lifecycle

After an authenticated session is restored or created, the app obtains the FCM token and calls the existing authenticated Supabase RPC `api_register_fcm_token`. A token refresh registers the new token before removing the previous token. This matters because Firebase documents token changes after restore to another device, uninstall/reinstall, app-data clearing, or token security events. Explicit sign-out calls `api_unregister_fcm_token` before clearing the local token state.

The token is stored only in app-private preferences. It is never logged, included in incident payloads, or exposed to the UI. Supabase RLS and the RPC's authenticated-user check remain authoritative for ownership.

## Server delivery still required

FCM registration alone does not send a notification. A trusted server-side worker must:

- watch the existing notification/event creation path;
- select only currently authorized, non-revoked device tokens;
- send a minimal data message through Firebase Admin SDK or the FCM HTTP v1 API;
- remove invalid/unregistered tokens; and
- never put incident descriptions, coordinates, phone numbers, or access tokens in the push payload.

The Android service intentionally renders a generic message and opens TACResQ, where the authenticated app fetches the notification inbox. Do not add a Firebase service-account key to the Android app or repository. A server sender must use FCM HTTP v1 with server-side credentials or application-default credentials; Firebase documents the `firebase.messaging` OAuth scope and the `Authorization: Bearer` request pattern in its [HTTP v1 sending guide](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

## Verification limits in this environment

The sandbox has no Android SDK or physical device, so APK compilation and end-to-end delivery cannot be run here. The offline source contract is `python3 tools/test_fcm_registration_contract.py`. CI/device validation must verify token registration, Android 13 permission denial/grant behavior, token rotation, sign-out removal, and a synthetic non-sensitive notification.
