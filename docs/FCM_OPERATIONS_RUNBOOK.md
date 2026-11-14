# TACResQ FCM operations runbook

## Deployment boundary

Run `php artisan tacresq:dispatch-fcm` only on a private Render Cron Job or worker. The public dispatcher web service must not receive the Firebase service-account file or `SUPABASE_SECRET_KEY`. The persistent in-app inbox remains authoritative; FCM is a supplemental wake-up hint and never proof that an alert was seen or accepted.

A Render worker should use the repository's PHP build and the following private settings:

```env
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_SECRET_KEY=<secret-manager value>
FCM_PROJECT_ID=<firebase project id>
GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/firebase-service-account.json
FCM_BATCH_LIMIT=100
FCM_HTTP_TIMEOUT=8
```

Mount the service-account JSON as a Render Secret File. Never use Android `google-services.json` as the server credential. Never print either credential, an OAuth token, FCM registration token, notification body, incident text, or coordinate.

## Safe operator sequence

1. Deploy the worker with `tacresq:dispatch-fcm --check-config`. This validates configuration and ADC without reading the inbox or sending a message.
2. Confirm the command exits successfully and logs only the sanitized configuration result.
3. Run one bounded synthetic delivery against a non-production account and generic notification. Record only aggregate counts and the delivery ledger result.
4. Review invalid-token cleanup and failed-attempt counts. Do not retry by manually sending to a token.
5. Enable the recurring Cron Job only after the synthetic device test passes.

## Observability contract

The worker claims attempts through the restricted `api_claim_fcm_delivery_batch` RPC. Its ledger tracks `claimed`, `sent`, `invalid_token`, `failed`, and `permanent_failure` states. A claim expires after 10 minutes if the worker crashes. Transient failures are retried with exponential backoff and jitter, honoring FCM's `Retry-After` header; quota failures start with at least a one-minute delay. The queue stops retrying a token pair after eight attempts. The inbox remains independent: an unread alert may already have been pushed successfully, and read state alone is not evidence of delivery.

Each run must expose or retain these aggregate signals without sensitive payloads:

- run start/end and a generated run identifier;
- command success/failure and duration;
- distinct notifications claimed, token deliveries claimed, sent count, skipped count;
- invalid-token cleanup count and failed-attempt count;
- oldest due failed-attempt age, expired-lease count, exhausted/permanent-failure count, and last successful run time;
- worker configuration-check result.

A run identifier is for operational correlation only. It must not be derived from a token, incident ID, email, phone, narrative, or coordinate. The existing command's count summary is safe to retain; provider response bodies and exception details must remain sanitized.

`GET /up` checks that Laravel is serving. It does not prove Supabase availability, Firebase credentials, Cron execution, or user-device receipt. Alert on missing worker runs, repeated failures, growing pending age, or unexpected invalid-token spikes. Use the persistent inbox to reconcile any suspected missed push.

## Rollback

To stop push delivery without changing incident or inbox writes, pause the private Cron Job. Keep the inbox enabled. If a credential is suspected exposed, disable the worker, revoke/replace the Firebase credential through the operator's secret manager, rerun `--check-config`, and perform a fresh synthetic test. Do not paste credentials into issues, logs, chat, or PRs.

## Current evidence boundary

The repository source contracts and CI can validate sender boundaries and build behavior. They cannot validate this Render worker, live Firebase authentication, or physical-device receipt from the sandbox. Those remain release gates.
