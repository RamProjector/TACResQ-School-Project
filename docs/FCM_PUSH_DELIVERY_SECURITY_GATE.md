# FCM push delivery — implementation and security gate

## Current verified architecture

- PostgreSQL/Supabase is the source of truth for TACResQ incidents and persistent in-app notifications.
- Android includes Firebase Messaging, registers tokens through authenticated Supabase RPCs, handles token rotation, and presents generic alerts through the messaging service.
- The backend contains a trusted Firebase HTTP v1 sender and `php artisan tacresq:dispatch-fcm`. The sender uses ADC, a server-only Supabase secret, a deduplicated delivery ledger, bounded retries, and invalid-token cleanup.
- The connected TACResQ Supabase project contains `user_notifications` and `fcm_delivery_attempts`; the inbox remains the source of truth. The claim RPC selects unread notifications, while the delivery ledger tracks each notification/token pair's claims and outcomes independently of the read flag.
- Render already has the Firebase service-account Secret File and `GOOGLE_APPLICATION_CREDENTIALS` path configured. Preserve the existing file and path.
- The live Render web-service startup does not invoke the dispatcher. Automatic queue execution and physical-device delivery remain rollout gates.
- Render Free Web Services may spin down. A sleeping Render process must not be treated as a guaranteed emergency-notification delivery path.

## Credential contract

The implemented sender uses Google Application Default Credentials (ADC), not a `FIREBASE_SERVICE_ACCOUNT_JSON` environment variable. On the current Render deployment, ADC uses the existing service-account JSON Secret File referenced by `GOOGLE_APPLICATION_CREDENTIALS`.

- Keep the service-account JSON server-side and preserve the existing Render Secret File and path.
- Never put the service-account JSON, private key, OAuth access token, or Firebase Admin credential in Android resources, `BuildConfig`, the web bundle, GitHub Actions logs, or repository files.
- Do not paste the credential into chat, issues, or PRs.
- The service account should have only the Firebase permissions needed to send messages. Do not grant broad project-owner/editor access.
- Do not rotate or revoke the existing credential as a routine fix. If exposure is actually suspected, follow the incident-response process with the deployment owner.
- Do not log environment values or dump the credential object in diagnostics.

## Required implementation and rollout sequence

1. **Keep Android app registration aligned.** Confirm the intended Android application ID and Firebase project. The client `google-services.json` is not the server credential and must never be used as an ADC file.
2. **Preserve token ownership.** Token registration/removal must derive the profile from the authenticated user. Never accept a target user ID from the client as authority. Test account switching, token refresh, logout, and invalid-token cleanup.
3. **Use committed notification events.** The sender claims rows through the restricted server-only `api_claim_fcm_delivery_batch` RPC. The claim query skips notifications already marked read; the delivery ledger controls duplicate suppression, leases, retries, and terminal outcomes. Do not replace the ledger with a scan of the inbox's unread page.
4. **Keep the server sender private.** Use the existing ADC file path and server-only Supabase secret. Do not add a public unauthenticated dispatch route or accept arbitrary recipients/message bodies from clients.
5. **Keep payloads privacy-safe.** Push bodies should be generic (for example, “TACResQ has an update”). Do not include names, phone numbers, incident descriptions, medical details, or precise coordinates. A notification ID is only a navigation hint; the app must fetch current role-authorized data before displaying incident details.
6. **Validate before rollout.** Test token ownership isolation, revoked/rotated tokens, denied notification permission, logout, duplicate/out-of-order events, expired credentials, FCM errors, retry limits, app killed/background/foreground behavior, and the requester/responder/driver/dispatcher role matrix. Confirm the real Firebase project and Render environment without printing secrets.
7. **Enable automatic execution only after an execution design is approved.** A private Render worker/Cron Job is isolated but is not available on the Free instance tier. A Supabase-triggered caller would require a separately reviewed migration, secret handling, authentication, replay protection, rate limits, retries, and monitoring. Do not change production infrastructure or incur costs without explicit approval.

## Acceptance gates

- Android debug and release builds pass on the exact PR head.
- Database migration replay and grants/RLS tests pass.
- Tests prove one user cannot register/remove another user's token or cause a push to an arbitrary recipient.
- Sender tests cover missing/invalid ADC configuration and FCM error handling without exposing credential values.
- A deployed non-sending `--check-config` succeeds.
- The dispatcher is invoked automatically by an approved mechanism and queue age/retry/terminal-failure metrics are monitored.
- Physical-device tests confirm permission, token refresh, foreground/background/closed-app delivery, and privacy-safe notification rendering.
- Push delivery remains supplemental. The persistent in-app inbox is authoritative; a push notification is not proof that an emergency was seen, accepted, or handled.

## Current status

**Sender and queue implementation exist; operational rollout is not verified.** The Android and server foundations are merged, and the notification/ledger migrations are applied to the connected TACResQ Supabase project. The existing Render credential file and ADC path are configured, but the deployed web-service startup does not automatically run the dispatcher. Before claiming operational delivery, run `php artisan tacresq:dispatch-fcm --check-config` in the deployed environment, establish an approved automatic execution mechanism, then perform a synthetic background/closed-app device test. Do not treat a successful configuration check as proof that a user saw or acted on an alert.