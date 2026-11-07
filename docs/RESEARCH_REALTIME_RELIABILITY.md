# Phase 3: realtime reliability boundary

**Status:** phase-3 design and contract slice; no realtime transport is enabled yet.

## Current evidence

TACResQ currently refreshes incident state, dispatcher responder locations, incident chat, and the notification inbox through authenticated RPC reads. Those refresh loops are lifecycle-aware and must remain available as the fallback path. The Android module does not currently contain a Supabase Realtime client or a WebSocket transport.

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

## First implementation slice

The next code PR should add one narrowly scoped authenticated stream, preferably the notification/assignment signal, while retaining the current inbox polling loop. The stream should carry only an event identifier and safe event type; the client should then call the existing `api_my_notifications` RPC and reconcile the returned list. Incident details, coordinates, and message bodies must continue to come from authorized RPC reads.

Do not enable responder-location streaming in the same PR. Location has a stricter incident/member authorization boundary and needs its own publication, retention, payload-minimization, and migration replay review.

## Release evidence boundary

CI can prove state-machine behavior, payload allowlisting, cancellation, fallback, and migration replay. It cannot prove Supabase Realtime authorization, network reconnection behavior, Firebase receipt, or Android OEM background behavior. Those remain synthetic staging and physical-device gates.
