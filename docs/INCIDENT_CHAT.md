# Incident-scoped chat — backend foundation

## Scope

This workstream adds text-only incident messaging over Supabase RPCs and a first Android conversation screen reachable from role-scoped incident lists, the dispatcher queue, and incident-message notifications. Messages are durable records tied to an incident and sender. No attachments, edits, deletes, read receipts, or public/global chat are included.

## Authorization model

- Requesters may read/send only on their own incident.
- Responders/drivers may read/send only when assigned through `incident_assignments` or the legacy `incidents.assigned_to` field.
- Dispatchers may read/send for incidents available to the dispatch role.
- Unknown roles fail closed. Admin does not inherit incident chat access implicitly.
- The authenticated profile is derived from `auth.uid()`; clients cannot choose a sender ID.
- The message table has RLS enabled and no direct API-role privileges. Clients use only `api_my_incident_messages` and `api_send_incident_message`.

## Message integrity and abuse controls

- Message text is trimmed and limited to 1–2000 characters.
- A client-generated UUID provides retry idempotency. Retrying the same key with the same incident/content returns the original message; reusing it for different content is rejected.
- Sends are serialized per sender for a 20-message-per-minute flood guard.
- Messages are append-only in this MVP. There are no client-side update/delete RPCs.
- Message listing is bounded to 1–100 records and supports an exclusive before-ID cursor.
- Database triggers create a generic notification for other incident participants. Message contents are not copied into notification bodies.
- Notifications go to the requester and assigned responders/drivers, excluding the sender. Dispatchers can open the conversation, but this migration does not notify every dispatcher globally about every message.

## Validation

The clean-PostgreSQL replay test covers requester/responder/dispatcher access, unrelated-account denial, sender identity, idempotent retry, conflicting idempotency-key reuse, message length/blank rejection, pagination, direct table privilege denial, RPC grants, message notifications, and rate limiting. The latest branch CI must pass before the backend can be considered verified.

## Android behavior in this stage

- Uses typed message models and the caller-scoped list/send RPCs; no direct table access.
- Opens from requester/responder incident history, dispatcher queue cards, and incident-message notifications without adding a sixth bottom-navigation destination.
- Polls every 10 seconds only while the conversation screen is in the foreground.
- Supports bounded 50-message pages, older-message pagination, a 2000-character composer, send-in-progress feedback, and visible error/retry states.
- Reuses the same client message UUID when retrying an unconfirmed send, preventing duplicate messages when the server committed but the response was lost.
- Disables sending until the conversation list confirms the caller has access. The database independently reauthorizes every list/send request.

## Remaining work

1. Test actual requester, assigned responder/driver, dispatcher, and unrelated accounts against a non-production Supabase environment.
2. Test foreground/background transitions, keyboard/insets, long messages, network loss during send, idempotent retry, and pagination on physical Android devices.
3. Decide whether to add read receipts or web dispatcher chat only if there is a clear workflow need; do not add attachments or broad dispatcher notifications without a separate privacy review.

No live Supabase migration, production message, account change, Render deployment, or FCM delivery was performed by this change.
