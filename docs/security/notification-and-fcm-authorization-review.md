# Notification and FCM authorization review

**Scope:** Source review of notification inbox RPCs, FCM installation-token lifecycle, and server delivery queue. This is not a live database grant/catalog verification.

## Findings

### In-app notification inbox

- Direct SELECT/INSERT/UPDATE/DELETE on `user_notifications` is revoked from client roles; its identity sequence is also closed.
- `api_my_notifications` derives the recipient profile from `auth.uid()`, enforces a bounded page size (1–100), and returns only that profile's notifications.
- `api_my_notification` treats the notification ID as a locator, not authorization. It returns a row only when the authenticated profile owns it; missing and non-owned IDs are indistinguishable.
- `api_mark_notification_read` updates only a notification owned by the caller and returns the same false result for missing and non-owned IDs.
- The three client-facing notification RPCs use `SECURITY DEFINER`, pin `search_path = pg_catalog`, schema-qualify application references, and revoke PUBLIC/anon execution before granting authenticated execution.

### FCM installation token lifecycle

- Registration derives profile ownership from `auth.uid()`; clients cannot choose a recipient profile.
- The global unique token allows a token to move to the currently authenticated profile when one app installation switches accounts. This avoids the old account continuing to receive that installation's pushes.
- Registration validates token length and can only insert/update the supplied token row. It does not read notifications, choose recipients, or send pushes.
- Direct table writes are closed; registration/unregistration use authenticated RPCs.
- Important trust boundary: the token is a bearer-like installation credential. A caller who obtains another installation's exact token could attempt to reassign it through the registration RPC. The design therefore depends on keeping tokens out of logs, analytics, support screenshots, and client-visible APIs. Avoid adding token-returning RPCs or token search to Admin UI.

### Delivery worker and ledger

- `fcm_delivery_attempts` is inaccessible to anon/authenticated and intended for trusted server-side worker access.
- The batch-claim RPC returns token values and is SECURITY DEFINER, but explicitly revokes execution from PUBLIC, anon, and authenticated and grants only service_role.
- The claim batch is bounded (maximum 50); lease duration is bounded (60–1800 seconds); retries are capped at eight; unique notification/token pairs and conditional conflict updates serialize competing workers.
- Invalid/permanent/sent outcomes are not retried as ordinary failures. The ledger is separate from the user's notification read state.
- This review does not establish that a worker is currently running or that physical-device push delivery works.

## Operational safety and privacy

- FCM is a best-effort alert channel, not a guaranteed emergency dispatch channel. Keep the persistent in-app notification record and independent emergency-service guidance; never make emergency response depend on push delivery.
- Keep payloads minimal. Do not put exact emergency coordinates, phone numbers, full incident descriptions, authentication material, or installation tokens in push payloads unless a separately reviewed product requirement justifies the exposure.
- Keep service-role credentials only on the trusted backend/worker. Never grant the delivery batch RPC to authenticated clients.
- Add rate/abuse monitoring for repeated token registration and account switching without logging raw token values. Prefer a one-way keyed fingerprint if correlation is required.
- If Admin visibility is later added, show delivery counts/status and timestamps—not raw tokens.

## Follow-up tests

1. Verify a user cannot read, mark read, or look up another user's notification by guessing IDs.
2. Verify unauthenticated/anon callers cannot register/unregister tokens or claim delivery batches.
3. Verify authenticated users cannot execute the service-role delivery batch RPC or read delivery-attempt rows.
4. Verify account switching moves only the supplied installation token and does not expose token values in API responses/logs.
5. Verify invalid-token cleanup and worker retry behavior remain server-only and do not delete another account's unrelated installation tokens.

## Evidence boundaries

Reviewed source migrations include persistent notification inbox creation, caller-scoped notification lookup, FCM delivery ledger and queue hardening, FCM token account switching, and operational table privilege restrictions. No SQL was executed, no live catalog queried, no worker invoked, no token or notification data read, and no migration applied.
