# Phase 3: realtime reliability boundary

**Status:** phase-3 notification broadcast slice merged; live Supabase and device gates remain open.

## Current evidence

TACResQ refreshes incident state, dispatcher responder locations, incident chat, and the notification inbox through authenticated RPC reads. Those refresh loops remain lifecycle-aware and are the fallback path. PR #277 adds an Android Supabase Realtime WebSocket for a private profile-scoped notification topic; events are hints only and trigger the authoritative notification RPC.

The persistent notification inbox remains the source of truth. FCM is a supplemental delivery prompt, and a push receipt is not evidence that a user saw or accepted an incident update.

## Safety boundary

The first realtime implementation must not expose a second, less-authorized read path. It must preserve the existing role-scoped RPCs and database authorization model. A client must not subscribe to a broad table or channel and then filter incident, location, chat, or notification data locally.

Before enabling a production subscription, the project must establish all of the following in an isolated migration/replay test:

1. The chosen channel/topic is scoped to the authenticated user or an explicitly authorized incident.
2. No precise location, phone number, narrative, or other unnecessary sensitive payload is broadcast.
3. Direct table privileges and Realtime publication settings cannot reveal rows outside the caller's existing authorization.
4. A reconnect triggers an authoritative RPC refresh before the UI treats the stream as current.
5. Duplicate, delayed, out-of-order, malformed, and revoked-membership events are harmless.
6. Closing the screen, signing out, or losing authorization tears down the subscription.

## Required connection states

The UI and tests should distinguish these states:

- `unavailable`: no realtime transport is configured or the current backend cannot authorize it;
- `connecting`: a subscription is being established;
- `connected`: the channel is subscribed, but the last authoritative refresh time remains separately visible;
- `reconnecting`: the channel was lost and an RPC refresh is required;
- `fallback_sync`: polling or an explicit refresh is being used while realtime is unavailable;
- `error`: the last subscription or reconciliation attempt failed.

A disconnected channel must never be described as live, and the timestamp of the last successful server read must not be replaced by an event-receipt timestamp.

## Implemented first slice

The first slice adds one narrowly scoped authenticated stream for notification hints while retaining the current inbox polling loop. The stream carries only an event identifier and safe event type; the client then calls the existing `api_my_notifications` RPC and replaces the inbox with the authorized result. The allowlist covers assignment, status, chat-message, requester-edit, and requester-cancellation notifications. A successful initial join or reconnect also emits a reconciliation hint, because events sent while the socket was offline cannot be assumed to have been replayed. Incident details, coordinates, and message bodies continue to come from authorized RPC reads.

The Android JVM suite now exercises duplicate and out-of-order hints as harmless repeated authoritative reads, and verifies that a notification disappears after the authoritative read no longer returns it (the synthetic revoked-membership case). These tests do not claim live channel authorization or network delivery.

Do not enable responder-location streaming in the same PR. Location has a stricter incident/member authorization boundary and needs its own publication, retention, payload-minimization, and migration replay review.

## Release evidence boundary

CI can prove state-machine behavior, payload allowlisting, cancellation, fallback, and migration replay. It cannot prove Supabase Realtime authorization, network reconnection behavior, Firebase receipt, or Android OEM background behavior. Those remain synthetic staging and physical-device gates.


## Follow-up review: requester edit/cancellation event coverage

The requester workflow review added two durable notification types: `incident_request_updated` and `incident_cancelled`. The initial Android Realtime filter accepted only assignment and status-change events, so those new notifications would be visible through the 30-second poll but would not trigger an immediate inbox refresh. The client allowlist now includes both new event types and `incident_message`, keeping the filter aligned with the persistent inbox event families.

The same review found that a successful Phoenix join changed the UI state to connected but did not itself trigger an authoritative read. Since events created while disconnected are not guaranteed to be replayed to this client, a successful initial join/reconnect now emits a refresh hint. The inbox still treats the authenticated notification RPC—not the WebSocket payload—as authoritative.

Research basis: Supabase recommends private Broadcast for low-latency event hints, requires private-channel authorization to match on both database sender and client subscription, and distinguishes Broadcast from the underlying durable application data. See [Broadcast](https://supabase.com/docs/guides/realtime/broadcast), [Subscribing to Database Changes](https://supabase.com/docs/guides/realtime/subscribing-to-database-changes), and [Realtime Authorization](https://supabase.com/docs/guides/realtime/authorization). The existing per-user topic policy and minimized payload remain unchanged.
