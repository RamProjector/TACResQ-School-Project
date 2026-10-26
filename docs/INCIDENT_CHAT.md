# Incident-scoped chat — backend foundation

## Scope

This migration adds text-only incident messaging over Supabase RPCs. It is a backend foundation, not a finished Android conversation screen or a live deployment. Messages are durable records tied to an incident and sender. No attachments, edits, deletes, or public/global chat are included.

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

## Remaining work

1. Add typed Android message models and Supabase RPC calls.
2. Add a conversation screen reachable from the role-scoped incident details, preserving the existing five-destination navigation and central GPS Map.
3. Poll only while the conversation is visible; handle send-in-progress, retry, offline/error, empty, and older-message pagination states.
4. Keep incident IDs as navigation references only; every conversation open/list/send must be reauthorized server-side.
5. Test actual role accounts and device behavior against a non-production Supabase environment before considering live rollout.

No live Supabase migration, production message, account change, Render deployment, or FCM delivery was performed by this change.
