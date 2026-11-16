# TACResQ FCM dispatch using Supabase Cron

**Purpose:** invoke the existing Laravel FCM delivery-ledger worker periodically without adding a paid Render Cron Job or Background Worker.

**Important:** this is a best-effort queue trigger, not an emergency-delivery SLA. The persistent Supabase in-app notification inbox remains authoritative. A free Render web service may sleep for 15 minutes and take about a minute to wake; the first dispatch after idle can therefore be delayed. Never rely on push alone for emergency response.

## Architecture

1. Supabase Postgres Cron checks the durable delivery ledger once per minute.
2. It calls pg_net only when at least one notification/token pair is new, retry-due, or has an expired claim lease. When the queue is empty, no request reaches Render, so the scheduler does not keep the free Render service awake continuously.
3. The request includes a timestamp, UUID request ID, and HMAC-SHA256 signature. The HMAC secret is stored in Supabase Vault and as the Render-only FCM_DISPATCH_WEBHOOK_SECRET environment variable. The secret itself is never sent.
4. Laravel validates the signature, five-minute timestamp window, UUID, empty body, replay cache, and per-minute rate limit.
5. A single-instance file-cache lock prevents overlapping invocations on the current one-instance Render service.
6. The endpoint invokes the existing FcmNotificationDispatcher with a hard-coded batch limit of three. It accepts no recipient, notification, token, or message parameters. The existing atomic Supabase claim RPC and per-token delivery ledger remain authoritative.

The three-token cap bounds serial FCM network waits to approximately 30 seconds at the sender's current 10-second per-request timeout. pg_net is configured with a 120-second HTTP timeout to allow for Render cold start plus dispatch. If the request times out, the delivery ledger's lease and retry logic remain responsible for recovery.

## One-time operator setup

Do not perform this setup until the PR is merged and the exact Render deployment is live. Do not paste the generated secret into chat, GitHub, or a PR.

### 1. Configure the same HMAC secret in Render and Supabase Vault

Generate a high-entropy value locally:

~~~sh
openssl rand -hex 32
~~~

Add the resulting value as the Render service environment variable:

~~~text
FCM_DISPATCH_WEBHOOK_SECRET=<generated-value>
~~~

On the connected TACResQ Supabase project, use the SQL Editor as the trusted project owner. Store the endpoint and the same generated value in Vault:

~~~sql
select vault.create_secret(
  'https://tacresq-school-project.onrender.com/internal/fcm/dispatch',
  'tacresq_fcm_dispatch_url'
);

select vault.create_secret(
  '<generated-value>',
  'tacresq_fcm_dispatch_hmac_secret'
);
~~~

Do not store the Firebase service-account JSON or Supabase service-role/secret key in Vault for this design. The existing Render Secret File and GOOGLE_APPLICATION_CREDENTIALS path remain unchanged.

### 2. Enable the database extensions

In Supabase Dashboard → Database → Extensions, enable pg_cron and pg_net if they are not already enabled. The current project inventory showed Vault and pgcrypto installed, but pg_cron and pg_net were not installed at the time of the implementation review.

### 3. Create the recurring job

Run this SQL as the trusted postgres database role after both Vault entries and extensions exist:

~~~sql
select cron.schedule(
  'tacresq-fcm-dispatch',
  '* * * * *',
  $job$
    with secret_values as (
      select
        max(decrypted_secret) filter (
          where name = 'tacresq_fcm_dispatch_url'
        ) as dispatch_url,
        max(decrypted_secret) filter (
          where name = 'tacresq_fcm_dispatch_hmac_secret'
        ) as dispatch_secret
      from vault.decrypted_secrets
    ),
    request_values as (
      select
        dispatch_url,
        dispatch_secret,
        floor(extract(epoch from clock_timestamp()))::bigint::text as request_timestamp,
        gen_random_uuid()::text as request_id
      from secret_values
    )
    select net.http_post(
      url := dispatch_url,
      body := '{}'::jsonb,
      headers := jsonb_build_object(
        'Content-Type', 'application/json',
        'X-TACResQ-Timestamp', request_timestamp,
        'X-TACResQ-Request-Id', request_id,
        'X-TACResQ-Signature',
          encode(
            extensions.hmac(
              convert_to(request_timestamp || E'\n' || request_id || E'\n{}', 'UTF8'),
              convert_to(dispatch_secret, 'UTF8'),
              'sha256'
            ),
            'hex'
          )
      ),
      timeout_milliseconds := 120000
    ) as request_id
    from request_values
    where dispatch_url = 'https://tacresq-school-project.onrender.com/internal/fcm/dispatch'
      and length(dispatch_secret) >= 32
      and exists (
        select 1
        from public.user_notifications as n
        join public.fcm_tokens as t
          on t.profile_id = n.recipient_user_id
        left join public.fcm_delivery_attempts as a
          on a.notification_id = n.id
         and a.token = t.token
        where n.read_at is null
          and (
            a.id is null
            or (
              a.status = 'failed'
              and a.attempt_count < 8
              and a.next_attempt_at <= statement_timestamp()
            )
            or (
              a.status = 'claimed'
              and a.attempt_count < 8
              and a.attempted_at <= statement_timestamp() - interval '600 seconds'
            )
          )
      );
  $job$
);
~~~

The SQL deliberately refuses to call any URL other than the fixed TACResQ Render endpoint. It sends an empty JSON object and signs the exact serialized body. The Vault secret is read only by the scheduled database command; it is not embedded in the cron definition. The due-work predicate mirrors the current claim RPC: new pairs, failed attempts whose next_attempt_at has arrived, and claimed attempts whose 600-second lease expired. Sent, invalid-token, permanent-failure, exhausted, and not-yet-due rows do not wake Render.

## Verification and monitoring

1. Confirm cron.job contains exactly one active job named tacresq-fcm-dispatch.
2. Inspect cron.job_run_details for successful schedule executions.
3. Inspect net._http_response for the corresponding request IDs, HTTP status, timeout, and sanitized response body. pg_net retains response rows for a limited period.
4. Check Render logs for aggregate FCM dispatch complete counts. The endpoint does not log request signatures, secret values, FCM tokens, provider bodies, or incident details.
5. Use a dedicated test account and test device to verify Android permission granted/denied, foreground, background, force-stopped/closed app, token refresh, logout, and account switching. Do not use a real emergency incident as a test.
6. Verify the in-app inbox still works when push is delayed, disabled, or unavailable.

Useful read-only checks:

~~~sql
select jobid, jobname, schedule, active
from cron.job
where jobname = 'tacresq-fcm-dispatch';

select status, return_message, start_time, end_time
from cron.job_run_details
where jobid = (
  select jobid from cron.job where jobname = 'tacresq-fcm-dispatch'
)
order by start_time desc
limit 20;

select id, status_code, timed_out, error_msg, created
from net._http_response
order by created desc
limit 20;
~~~

Do not select or print vault.decrypted_secrets, net.http_request_queue.headers, Render environment values, or service-account file contents in routine diagnostics.

## Rollback

Pause the job without deleting its history:

~~~sql
select cron.alter_job(
  job_id := (select jobid from cron.job where jobname = 'tacresq-fcm-dispatch'),
  active := false
);
~~~

To remove it after review:

~~~sql
select cron.unschedule('tacresq-fcm-dispatch');
~~~

Then remove FCM_DISPATCH_WEBHOOK_SECRET from Render and delete the two named secrets from Supabase Vault through the Dashboard. Do not disable or drop pg_cron, pg_net, Vault, or pgcrypto; other project functions may depend on them.

## Limitations and security notes

- Supabase documents pg_net as beta; review its current API/limits before enabling it.
- The net.http_request_queue is an unlogged queue. Request headers can exist there transiently while a request is pending; keep database access restricted to trusted operators.
- The Render Free service can cold-start, restart, or be suspended by platform limits. It is contacted only while the delivery queue has due work, so idle periods do not create a permanent keep-alive ping. A successful cron invocation is not proof that FCM delivered or that a user saw an alert.
- The current file-cache lock is suitable only for the existing single-instance Render deployment. Revisit locking before enabling multiple instances or moving the endpoint to a multi-instance host.
- Supabase Free projects can pause after extended inactivity; if the project is paused, Cron cannot run.
- The sender's current bounded retry/lease ledger remains the recovery mechanism. Do not add a second sender or bypass api_claim_fcm_delivery_batch.
