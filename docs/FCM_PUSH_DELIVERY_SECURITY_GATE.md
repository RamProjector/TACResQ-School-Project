# FCM push delivery — implementation and security gate

## Current verified architecture

- PostgreSQL/Supabase is the source of truth for TACResQ incidents and persistent in-app notifications.
- Android currently uses the app's existing Supabase API stack. Its Gradle dependencies do not currently include Firebase Messaging, and the Android manifest does not register an FCM messaging service.
- The Render service `TACResQ-School-Project` builds `backend/Dockerfile`, which hosts the Laravel dispatcher console and browser-safe Supabase runtime configuration. Its documented role is not currently an FCM sender or a mobile API.
- The current notification implementation creates database-backed inbox records. It does not deliver FCM pushes.
- Render free services may spin down. A sleeping Render process must not be treated as a guaranteed emergency-notification delivery path.

## Environment variable contract

The Firebase Admin service-account JSON must remain server-side. A proposed canonical variable name is `FIREBASE_SERVICE_ACCOUNT_JSON`; the application must explicitly read and validate this exact name before it can use the value. If the Render variable was configured under a different name, either align the dashboard name or deliberately update the code contract in a reviewed change.

- Never put the service-account JSON, private key, or a Firebase Admin credential in Android resources, `BuildConfig`, the web bundle, GitHub Actions logs, or repository files.
- Do not paste the credential into chat, issues, or PRs.
- The service account must have only the Firebase permissions needed to send messages. Do not grant broad project-owner/editor access.
- The exposed previous key must be revoked; a replacement key does not retroactively make the old key safe.
- Do not log environment values or dump the credential object in diagnostics.

## Required implementation sequence

1. **Confirm Firebase Android app registration.** Register the intended Android application ID in Firebase and obtain its matching `google-services.json`. Debug variants use separate application IDs, so QA builds may require corresponding Firebase app registrations. The file contains client configuration, not the service-account private key; review it for the correct package/project before adding it.
2. **Add Android FCM support.** Add the Firebase Messaging dependency and required Google Services configuration, a notification channel, Android 13+ notification-permission flow, token refresh handling, and a messaging service. Permission denial must leave the in-app inbox fully usable.
3. **Persist device tokens with ownership.** Add a migration and narrowly scoped RPCs for registering/removing the caller's own token. Never accept a target user ID from the client as authority. Store token metadata, deduplicate tokens, support rotation, and remove tokens on logout or invalid-token responses.
4. **Choose a trusted event delivery path.** The sender must consume committed notification/outbox events, not trust a client telling it which user to notify. Define retries, idempotency, invalid-token cleanup, bounded payloads, and observable failure states. Avoid synchronous network calls inside the database transaction that creates the incident/notification.
5. **Implement the server sender.** Parse the service-account JSON from the server environment, validate required fields without logging values, obtain short-lived OAuth credentials, and call FCM HTTP v1. Fail closed if configuration is missing or malformed. Never send to arbitrary tokens or accept arbitrary message bodies from public callers.
6. **Keep payloads privacy-safe.** Push bodies should be generic (for example, “TACResQ has an update”). Do not include names, phone numbers, incident descriptions, blood/medical details, or precise coordinates. A notification ID is only a navigation hint; the app must fetch current role-authorized data before displaying incident details.
7. **Validate before rollout.** Test token ownership isolation, revoked/rotated tokens, denied notification permission, logout, duplicate/out-of-order events, expired credentials, FCM errors, retry limits, app killed/background/foreground behavior, and the requester/responder/driver/dispatcher role matrix. Confirm the real Firebase project and Render environment without printing secrets.

## Acceptance gates

- Android debug and release builds pass on the exact PR head.
- Database migration replay and grants/RLS tests pass.
- Tests prove one user cannot register/remove another user's token or cause a push to an arbitrary recipient.
- Sender tests cover missing/invalid environment configuration and FCM error handling without exposing credential values.
- Physical-device tests confirm permission, token refresh, foreground/background delivery, and privacy-safe notification rendering.
- Push delivery remains supplemental. The persistent in-app inbox is authoritative; a push notification is not proof that an emergency was seen, accepted, or handled.

## Current status

**Planning/security gate only. FCM is not implemented.** The environment variable is configuration, not a working integration until application code consumes it and the Android client is registered/configured. No live push, database migration, Render deployment, or credential access was performed while writing this document.
