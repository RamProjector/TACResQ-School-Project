# TACResQ FCM delivery: Render execution audit

**Audit date:** 2026-11-14  
**Scope:** repository `main`, live Render web-service metadata/logs, and read-only Supabase extension/trigger inspection. No credentials were read, no notification was sent, and no production setting was changed.

## What is already configured

- The backend contains a Firebase HTTP v1 sender (`App\\FcmHttpV1Client`) using Google Application Default Credentials (ADC).
- Google ADC reads the credential file referenced by `GOOGLE_APPLICATION_CREDENTIALS`. Keep the existing Render secret file and its existing environment-variable path; do not copy the JSON into source control, logs, or a second secret store.
- The Render service has the repository's `backend/` directory as its root and builds the existing Dockerfile.
- The dispatcher command exists: `php artisan tacresq:dispatch-fcm`.
- The non-sending preflight command exists: `php artisan tacresq:dispatch-fcm --check-config`. It validates required Supabase/FCM configuration and that ADC can mint an FCM-scoped access token. It does not send a message or prove Supabase queue access/device delivery.

## Execution gap found

The live Render service is a single **Free Web Service**. The deployed Dockerfile starts only:

```sh
php -S 0.0.0.0:${PORT:-10000} -t public router.php
```

There is no scheduler, worker process, cron invocation, or command invocation in that startup command. The application logs inspected for 2026-11-13 through 2026-11-14 show PHP built-in-server connection/close messages; no FCM dispatcher execution was found.

The database currently has the Supabase Vault extension, but the read-only extension inventory did not find `pg_cron` or `pg_net`. The only notification-related trigger found was the existing `tacresq_broadcast_notification_insert` trigger; it does not itself prove an HTTP worker is being invoked.

Therefore, credential setup and sender implementation are present, but automatic queue processing is not established by the deployed service configuration.

## Why not just run the scheduler inside the web container?

Render documents that Free Web Services spin down after 15 minutes without inbound HTTP/WebSocket traffic and can take about a minute to wake. A background loop inside that same container would stop when the service spins down. It cannot guarantee prompt delivery for a newly created incident while the service is asleep.

Render also documents that Background Workers and Cron Jobs do not have Free instances; Cron Jobs have a minimum monthly charge. Do not create one or change the service plan without explicit approval.

## Candidate execution designs (not enabled)

1. **Render Cron Job / Background Worker:** simplest supported execution model, but not free. Requires explicit cost approval.
2. **Supabase-triggered HTTP dispatch:** enable and configure `pg_net` (and optionally `pg_cron` for reconciliation), store a high-entropy webhook secret in Vault, and call a narrowly scoped authenticated Render endpoint. This requires a reviewed production migration/configuration and careful handling of secrets in the asynchronous request queue. A sleeping Free Render service still introduces cold-start delay; this is not a delivery SLA.
3. **GitHub Actions scheduled caller:** not recommended for emergency notifications. Scheduled workflows can be delayed or skipped and would require another protected credential path.

Do not run `schedule:work` in the web container and call the result reliable. Do not use an unauthenticated public dispatch endpoint.

## Safe validation sequence

1. Preserve the existing Render secret file and `GOOGLE_APPLICATION_CREDENTIALS` value/path.
2. Run `php artisan tacresq:dispatch-fcm --check-config` in the deployed environment only after confirming the command can be run safely. It sends no push.
3. Add an authenticated execution mechanism only after its trigger, authentication, rate limit, concurrency behavior, and retry semantics are reviewed.
4. Use a dedicated test device/token and synthetic notification; verify the FCM provider message name and delivery on-device.
5. Monitor queue age, due attempts, sent, invalid-token, retrying, permanent-failure, and expired-lease counts.

**Operational warning:** until a dispatcher is invoked automatically and a real device test succeeds, TACResQ push delivery must be treated as unverified. FCM must not be the sole emergency-alert channel.

## Follow-up implementation — signed Supabase Cron trigger

A repository branch now adds a private POST endpoint at /internal/fcm/dispatch. It accepts only an empty JSON object and requires an HMAC-SHA256 signature over the timestamp, UUID request ID, and exact body. It rejects stale timestamps, malformed IDs, invalid signatures, replayed request IDs, and query/body parameters; it applies a per-minute throttle and a single-instance file-cache lock. The route never accepts caller-supplied recipients, notification IDs, tokens, or message content. It calls the existing FCM delivery dispatcher with a fixed batch size of three and returns only aggregate counts.

The matching operator guide is [FCM_SUPABASE_CRON_TRIGGER.md](FCM_SUPABASE_CRON_TRIGGER.md). It uses Supabase Vault + pg_cron + pg_net to check the delivery ledger every minute and call Render only when new, retry-due, or expired-lease work exists. This avoids an idle keep-alive that would consume Render Free instance hours. The current project already has Vault and pgcrypto installed, but pg_cron and pg_net were not installed at the time of review. No live extensions, Vault secrets, cron jobs, Render variables, deployments, or notifications have been changed by this implementation branch.

**Deployment caveat:** this is best-effort queue processing, not a delivery SLA. Render Free can spin down after 15 minutes and needs about a minute to wake; pg_net's request timeout is set to 120 seconds. The worker batch is limited to three to keep provider calls bounded. The file-cache lock is only suitable for the current single-instance Render service. Physical-device delivery and actual Cron-to-Render invocation remain unverified until the operator setup and synthetic-device test are completed.
