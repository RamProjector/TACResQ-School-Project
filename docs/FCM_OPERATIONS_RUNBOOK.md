# TACResQ FCM operations runbook

## Deployment boundary and current state

The persistent in-app inbox is authoritative; FCM is a supplemental wake-up hint and never proof that an alert was seen or accepted. The dispatcher command must remain server-side and must not be exposed as a public HTTP route.

The existing Render web service already has the Firebase service-account Secret File and the `GOOGLE_APPLICATION_CREDENTIALS` path configured. Preserve that file and path. The deployed web-service startup currently launches only the PHP web server; it does not invoke `php artisan tacresq:dispatch-fcm`, a scheduler, or a worker. Credential availability is not the same as queue execution.

Do not move, recreate, duplicate, rotate, or revoke the existing credential as part of routine dispatcher work. Do not change Render settings, enable database extensions, create production triggers, or incur service costs without explicit approval.

## Runtime configuration contract

The dispatcher reads these server-side settings:

```env
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_SECRET_KEY=<server-only secret>
FCM_PROJECT_ID=<firebase project id>
GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/firebase-service-account.json
FCM_BATCH_LIMIT=50
```

The file path above is an example only; keep the exact existing value configured on the current Render service. The actual credential file must remain a Render Secret File or equivalent protected file. Never use Android `google-services.json` as the server credential. Never print either credential, an OAuth token, FCM registration token, notification body, incident text, or coordinate.

The dispatcher and database RPC both cap each batch at 50. Values above 50 are clamped by the PHP command and rejected by the database RPC if they bypass the command.

## Safe operator sequence

1. When an authorized operator can run a command in the deployed environment, execute `php artisan tacresq:dispatch-fcm --check-config`. This validates required configuration and ADC without claiming queue rows or sending a push.
2. Record only whether the preflight succeeded. Do not print environment variables, credential contents, access tokens, or provider response bodies.
3. After automatic execution has been implemented and approved, use a dedicated test account/device and a synthetic generic notification. Confirm the provider response and actual device receipt without using real incident data.
4. Verify that duplicate delivery is suppressed, a failed attempt respects `next_attempt_at`, expired leases can be reclaimed, and the eight-attempt ceiling is enforced.
5. Enable recurring execution only after the synthetic device test and monitoring checks pass.

## Execution options and constraints

- **Render Cron Job/Background Worker:** provides a separate execution process, but Render does not offer these as Free instances. Use only after explicit cost approval.
- **Supabase-triggered HTTP caller:** could invoke a narrowly scoped authenticated endpoint, but requires a reviewed production migration, protected secret storage, request authentication, timestamp/replay protection, rate limits, bounded execution, retries, and monitoring. It does not eliminate cold-start delay on a Free Render web service.
- **A loop inside the current Free web service:** not reliable for emergency dispatch because the service may spin down while idle. Do not describe it as guaranteed delivery.
- **GitHub Actions scheduled workflow:** not suitable as the primary emergency notification path because scheduled runs can be delayed or skipped.

Do not implement any of these production execution paths without reviewing the threat model and receiving approval for the required production changes.

## Observability contract

The delivery ledger tracks `claimed`, `sent`, `invalid_token`, `failed`, and `permanent_failure` states. A claim expires after 10 minutes if the worker crashes. Transient failures are retried with exponential backoff and jitter, honoring FCM's `Retry-After` header; quota failures start with at least a one-minute delay. The queue stops retrying a token pair after eight attempts. The claim query skips notifications already marked read; the ledger independently prevents a sent-but-unread pair from being claimed again.

Each run should expose or retain these aggregate signals without sensitive payloads:

- run start/end and a generated run identifier;
- command success/failure and duration;
- distinct notifications claimed, token deliveries claimed, sent count, skipped count;
- invalid-token cleanup count and failed-attempt count;
- oldest due failed-attempt age, expired-lease count, exhausted/permanent-failure count, and last successful run time;
- worker configuration-check result.

A run identifier is for operational correlation only. It must not be derived from a token, incident ID, email, phone, narrative, or coordinate. The existing command's count summary is safe to retain; provider response bodies and exception details must remain sanitized.

`GET /up` checks that Laravel is serving. It does not prove Supabase availability, Firebase credentials, dispatcher execution, or user-device receipt. Alert on missing worker runs, repeated failures, growing pending age, or unexpected invalid-token spikes. Use the persistent inbox to reconcile any suspected missed push.

## Rollback

To stop push delivery without changing incident or inbox writes, pause the approved worker/Cron Job or disable its invocation. Keep the inbox enabled. If a credential is suspected exposed, follow the deployment owner's credential incident-response process; do not paste credentials into issues, logs, chat, or PRs.

## Current evidence boundary

Repository source contracts and CI can validate sender boundaries and build behavior. They cannot validate the live Render Secret File mount, live Firebase authentication, automatic dispatcher execution, or physical-device receipt. Those remain release gates.