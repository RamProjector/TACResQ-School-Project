# TACResQ FCM automatic dispatch — architecture decision and threat model

**Status:** Design proposal only — not approved for production activation  
**Scope:** Automatic execution of the existing `php artisan tacresq:dispatch-fcm` queue sender  
**Non-goals:** Replacing the persistent inbox, changing the FCM payload, changing token ownership, or moving/rotating the existing Render credential.

## 1. Executive decision

TACResQ already has the Firebase HTTP v1 sender, a server-only Supabase claim RPC, and a delivery ledger with bounded leases/retries. The missing component is a dependable invocation mechanism.

There is no currently verified zero-cost path that both preserves the existing Firebase credential exclusively in Render and guarantees prompt execution while the current Render Free Web Service is asleep.

**Preferred long-term direction:** evaluate a Supabase Edge Function dispatcher that claims work through the existing service-only RPC and sends FCM directly, but only if the deployment owner explicitly approves a separately protected Firebase credential in Supabase Edge Function Secrets. This is a credential-placement decision, not permission to copy or move the current Render Secret File. The existing Render credential and path must remain unchanged unless separately authorized.

**No production action is authorized by this document.** Do not enable extensions, create triggers, add secrets, deploy functions, change Render settings, run production migrations, or send a live push as part of this proposal.

## 2. Verified current state

- The persistent `user_notifications` inbox is authoritative; FCM is supplemental.
- The Android app registers tokens through authenticated RPCs.
- The backend has an FCM HTTP v1 sender using Google Application Default Credentials (ADC).
- `php artisan tacresq:dispatch-fcm` claims bounded work through `api_claim_fcm_delivery_batch`.
- The RPC is restricted to `service_role`; the delivery ledger tracks notification/token attempts, leases, retries, and terminal outcomes.
- The claim query skips notifications already marked read. The ledger separately prevents duplicate delivery for a notification/token pair.
- Batch size is capped at 50; attempts are capped at eight; leases expire after ten minutes.
- The existing Render web service has a Firebase service-account Secret File and `GOOGLE_APPLICATION_CREDENTIALS` configured. Its startup launches the PHP web server only; it does not invoke the dispatcher.
- Render Free Web Services can spin down after 15 minutes without inbound HTTP/WebSocket activity. Free instances do not support one-off jobs, SSH/shell access, Cron Jobs, or Background Workers.
- The connected Supabase project previously had Vault enabled but did not have `pg_net` or `pg_cron` enabled. Do not assume that inventory remains current without a new read-only check.

## 3. Candidate designs

### A. Supabase Edge Function as the dispatcher (preferred for evaluation)

**Flow**
1. A narrowly scoped invocation reaches a private Edge Function.
2. The function uses its server-side Supabase secret to call the existing `api_claim_fcm_delivery_batch` RPC.
3. It sends the claimed generic data-only messages to FCM using a Firebase service-account credential stored as an Edge Function secret.
4. It records per-token outcomes through the existing queue/ledger contract.
5. It returns aggregate counts only; no tokens, incident details, coordinates, credential values, or provider response bodies are returned or logged.

**Advantages**
- Avoids depending on a sleeping Render web process for the send operation.
- Keeps the sender in a managed server-side runtime with protected secrets.
- Can reuse the existing claim RPC, batch ceiling, leases, retry policy, and idempotency model.

**Costs and risks**
- Requires a second protected placement of the Firebase service-account credential, or an explicitly approved credential migration. Do not copy the Render Secret File without authorization.
- Requires implementation parity with the existing PHP sender: FCM HTTP v1 OAuth, strict payload allowlist, typed error classification, invalid-token cleanup, retry scheduling, bounded execution, and sanitized telemetry.
- Edge Function invocation limits/timeouts and concurrency must be checked against the real batch workload before selecting a schedule.
- A function is not automatically invoked merely because it exists; its trigger and recovery path must be designed and tested.

**Decision gate:** deployment owner explicitly approves Firebase credential availability in Supabase Edge Function Secrets and the team accepts the operational ownership of a second sender implementation.

### B. Supabase Database Webhook / pg_net to the existing Render sender

**Flow**
1. A database event queues an HTTP request.
2. The request calls a narrowly scoped authenticated Render endpoint.
3. The endpoint invokes the existing PHP dispatcher, which continues using the current Render ADC Secret File.

**Advantages**
- Preserves the current Firebase credential placement in Render.
- Reuses the existing PHP sender rather than porting FCM logic.

**Risks**
- The current Render Free Web Service can be asleep and introduce cold-start delay. It is not a prompt-delivery guarantee.
- Supabase Database Webhooks use `pg_net`, which is asynchronous and currently documented as beta.
- Supabase documents that request headers are stored in `net.http_request_queue` until sent. The Database Webhooks guide warns that database login roles can read a decrypted secret while the request waits. A webhook bearer token or HMAC header must therefore not be assumed safe merely because it was read from Vault.
- `pg_net` uses unlogged request/response tables; pending requests and stored responses may not survive an unclean database shutdown. Response retention is limited.
- A webhook payload generated from a row may include the full row. Sending notification title/body or incident fields to an external endpoint is unnecessary and must be avoided.
- Requires an authenticated, replay-resistant, rate-limited endpoint and careful hardening of access to the request queue and response tables.

**Decision:** not recommended as the first implementation. Reconsider only after a concrete privilege review proves that queued request secrets cannot be read by untrusted database roles and a cold-start/retry model is accepted.

### C. Render Cron Job / Background Worker

**Advantages**
- Isolated execution process; easiest operational model for the existing PHP command.
- Preserves existing Render ADC credential location and code path.

**Risks**
- Render does not provide Cron Jobs or Background Workers on its Free instance tier.
- Requires paid service cost approval.
- A periodic schedule adds delay between notification creation and dispatch.

**Decision:** technically straightforward, but not compatible with the current free-only constraint unless the owner approves a paid service.

### D. GitHub Actions scheduled dispatcher

**Decision:** reject as the primary emergency notification path. Scheduled workflow timing is not a dependable low-latency delivery mechanism. It would also introduce another secret distribution path and repository-runner exposure surface.

### E. Background loop in the current Render Free Web Service

**Decision:** reject. The service can spin down after idle time, so a loop in that process cannot guarantee queue polling while asleep. Do not add `schedule:work` or a permanent loop to the web startup and call it reliable.

## 4. Event and trigger design requirements

If a database event is used in a future approved design:

- Trigger from the committed `public.user_notifications` insert event, not from `fcm_delivery_attempts` inserts/updates. Ledger rows are created/updated during claiming and can cause recursive or duplicate wakeups.
- The event should be a wake-up hint only. The dispatcher must claim authoritative work from the existing RPC; it must not trust a client-supplied recipient, token, title, body, or arbitrary notification payload.
- Send the smallest possible event envelope, ideally an opaque event/notification identifier and schema version. Do not send title, body, coordinates, incident narrative, phone number, or other personal data in the webhook payload.
- A notification that is already read when the claim occurs must remain excluded.
- Multiple events, duplicate webhooks, and out-of-order delivery must be safe. The claim RPC and ledger remain the idempotency boundary.
- A periodic reconciliation path is still needed to recover missed wake-up events. It must not rely solely on a best-effort webhook.
- Rate-limit and bound each invocation; never allow an unauthenticated caller to choose recipients or arbitrary FCM messages.

## 5. Threat model and controls

| Threat | Required control |
|---|---|
| Public caller triggers arbitrary pushes | No caller-controlled recipients/body; only claim pre-existing rows through service-only RPC |
| Replay of a dispatch request | Timestamp + nonce/request ID, short validity window, deduplication, and replay cache where a signed bridge is used |
| Secret exposed in database webhook queue | Do not assume Vault protects queued headers; inspect role grants and queue visibility before any activation |
| FCM service account exposed to Android | Keep credential server-side; no service-account key in APK, web bundle, Git, or logs |
| Duplicate/out-of-order events | Atomic claim RPC, lease, delivery ledger, idempotent notification/token key |
| Concurrent dispatchers | Database claim lock/lease; bounded batch; verify lease semantics under concurrent workers |
| Malformed or sensitive push body | Fixed generic data-only payload allowlist; app fetches role-authorized detail from Supabase |
| Invalid token cleanup deletes a valid token | Retain typed FCM error classification; only delete on confirmed token-invalid responses |
| Queue backlog or silent worker failure | Monitor oldest due age, last successful run, expired leases, retries, permanent failures, and invocation failures |
| Worker overload or denial of service | Batch limit 50, execution deadline, per-origin rate limit, concurrency ceiling, backoff |
| Function returns success but FCM/device did not receive | Distinguish accepted-by-FCM from device receipt; physical-device QA remains a separate gate |

## 6. Required tests before any production rollout

### Contract and unit tests
- Function/endpoint rejects missing, invalid, expired, and replayed authentication where applicable.
- Invocation cannot choose arbitrary user IDs, FCM tokens, notification text, or incident details.
- Claim RPC remains service-role-only and batch size cannot exceed 50.
- Duplicate invocation does not create duplicate sends for a notification/token pair.
- Read notifications are skipped; sent-but-unread pairs are not resent.
- Expired leases can be reclaimed; active leases cannot be stolen; exhausted attempts remain terminal.
- FCM transient, quota, unregistered, typed invalid-argument, and malformed-request errors map to the existing retry/cleanup policy.
- Function/endpoint logs and responses contain aggregate counts only.

### Integration tests
- Concurrent dispatch attempts against the same seeded queue.
- Worker crash after claim but before send; worker crash after FCM acceptance but before ledger update.
- Timeout, retry, partial batch failure, expired OAuth token, and Supabase RPC outage.
- Trigger duplication, missing trigger event, and reconciliation recovery.
- Secret absence and malformed service-account JSON without printing its contents.

### Device tests
- Dedicated synthetic accounts and test tokens only.
- Android 13+ notification permission granted/denied.
- Foreground, background, force-stopped/closed-app behavior where supported by Android/FCM.
- Logout, account switch, token refresh, reinstall, and invalid-token cleanup.
- Generic lock-screen content; tap-through fetches current role-authorized data.
- Verify the persistent inbox remains correct even if push is delayed or dropped.

## 7. Rollout gates and rollback

No automatic dispatch should be enabled until all are true:

1. Architecture and credential placement explicitly approved.
2. Threat model and database grants reviewed.
3. Implementation parity tests and migration replay pass.
4. Non-sending configuration validation passes in the target runtime.
5. Synthetic FCM provider acceptance and physical-device receipt are confirmed.
6. Queue age, retries, invalid tokens, permanent failures, and last successful run are observable.
7. A reconciliation mechanism exists for missed wake-up events.
8. Rollback disables only the dispatcher invocation; it must not disable notification inserts or the persistent inbox.

Rollback: pause the approved trigger/scheduler/worker or disable its invocation, preserve the inbox and ledger, and reconcile pending attempts after the issue is fixed. Never delete pending notification records as a rollback shortcut.

## 8. Approval required before implementation

The next production decision is specifically whether TACResQ may store a Firebase service-account credential as a separate protected Supabase Edge Function secret. Until that is approved, keep the existing Render credential in place and continue only with repository-level tests, design review, and non-production implementation planning.

## 9. Official references

- Supabase Database Webhooks: https://supabase.com/docs/guides/database/webhooks
- Supabase pg_net: https://supabase.com/docs/guides/database/extensions/pg_net
- Supabase Edge Function secrets: https://supabase.com/docs/guides/functions/secrets
- Supabase Edge Function authentication: https://supabase.com/docs/guides/functions/auth
- Supabase Edge Function limits: https://supabase.com/docs/guides/functions/limits
- Render Free instance limits: https://render.com/docs/free
- Render FAQ: https://render.com/docs/faq
