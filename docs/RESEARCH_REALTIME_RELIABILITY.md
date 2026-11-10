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

The first slice adds one narrowly scoped authenticated stream for notification/assignment hints while retaining the current inbox polling loop. The stream carries only an event identifier and safe event type; the client then calls the existing `api_my_notifications` RPC and replaces the inbox with the authorized result. Incident details, coordinates, and message bodies continue to come from authorized RPC reads.

The Android JVM suite now exercises duplicate and out-of-order hints as harmless repeated authoritative reads, and verifies that a notification disappears after the authoritative read no longer returns it (the synthetic revoked-membership case). These tests do not claim live channel authorization or network delivery.

Do not enable responder-location streaming in the same PR. Location has a stricter incident/member authorization boundary and needs its own publication, retention, payload-minimization, and migration replay review.

## Release evidence boundary

CI can prove state-machine behavior, payload allowlisting, cancellation, fallback, and migration replay. It cannot prove Supabase Realtime authorization, network reconnection behavior, Firebase receipt, or Android OEM background behavior. Those remain synthetic staging and physical-device gates.
