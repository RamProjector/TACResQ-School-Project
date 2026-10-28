# TACResQ Push Notification Security and Delivery Plan

**Status:** Design only — do not enable production sending until the exposed Firebase service-account key has been revoked and a replacement credential strategy is approved.

## Security incident: exposed service-account key

A Firebase Admin service-account JSON private key was shared in a chat. Treat the key as compromised. The project owner must revoke/delete that exact key in Google Cloud IAM & Admin → Service Accounts → the relevant service account → Keys, review service-account permissions and activity, and inspect repository history and CI logs for exposure. If the key was ever committed, removing the current file is not enough: revoke the key first and follow the repository's secret-remediation process. Do not put a replacement key in chat, source control, Android resources, APK assets, issue comments, or logs.

This document intentionally does not store the project ID, service-account email, key ID, or any credential material.

## Recommended architecture

1. **Android client**
   - Add Firebase Messaging only after checking the current Gradle, manifest, application startup, and app distribution configuration.
   - Obtain an FCM registration token through the supported Firebase SDK and send it over TLS to an authenticated backend endpoint.
   - Never bundle Firebase Admin credentials or a service-account private key in the APK.
   - Treat the token as sensitive device-routing data. Support token refresh, logout/device unlinking, and stale-token cleanup.
   - Notifications are hints to refresh the authenticated API; the API remains the source of truth for incident status.

2. **Trusted server**
   - Send messages only from a trusted server runtime (for example, the existing backend/Render service) using Firebase Admin SDK or the supported HTTP v1 API.
   - Prefer workload identity / Application Default Credentials where the hosting environment supports it. If a key is unavoidable, store it only as a server-side secret and rotate it; never expose it to the browser or Android client.
   - Validate the user's session and role before registering or removing a device token. A user must not register tokens for another user's account.
   - Authorize each notification event from current database state. Do not accept arbitrary recipient IDs or message bodies from an untrusted client.
   - Keep credentials, FCM tokens, exact coordinates, emergency details, and authorization headers out of logs.

3. **Event eligibility and privacy**
   - Candidate events: assignment created/changed, per-unit status changed, and requester-visible incident status changed.
   - Send only the minimum useful text, e.g. “Your emergency request has an update. Open TACResQ to view details.” Avoid names, blood/medical details, exact locations, phone numbers, and sensitive incident descriptions in lock-screen text.
   - Re-check access after opening the notification. A notification is not authorization.
   - Do not send background location updates as push notifications. Location sharing must continue to follow the explicit, documented assignment and lifecycle policy.

4. **Delivery and reliability**
   - Persist business state before enqueueing notification delivery.
   - Make delivery retryable and idempotent; remove tokens that FCM confirms are invalid/unregistered.
   - Do not make emergency creation or assignment success depend on push delivery. Show API state in-app and refresh on resume.
   - Record minimal server-side delivery outcomes for troubleshooting without storing full message payloads or secrets.

## Implementation gates

- [ ] Exposed service-account key revoked and access reviewed.
- [ ] Confirm current backend hosting can securely obtain Firebase credentials without adding paid infrastructure.
- [ ] Confirm package/application ID, Firebase Android app registration, and debug-vs-release Firebase configuration.
- [ ] Define authenticated device-token registration, token rotation, logout cleanup, and RLS/server authorization.
- [ ] Define exact role/event/recipient matrix with TACRU before enabling sends.
- [ ] Add automated tests for unauthorized token registration, cross-user token access, duplicate events, invalid-token cleanup, and message redaction.
- [ ] Test on at least two devices with real backend state and notification permission denied/granted.
- [ ] Verify current-head CI and build before merge/release.

## Scope not included

This plan does not claim push notifications are implemented or working. It does not authorize production sends, change Firebase IAM, create credentials, alter paid plans, or modify production data. The credential owner must complete revocation and review directly in Google Cloud.
