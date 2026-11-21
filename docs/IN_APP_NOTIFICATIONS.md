# Persistent notifications — implementation and release boundary

## Current implementation

PostgreSQL `public.user_notifications` is the durable source of truth for the Android notification inbox. Notifications are created by database triggers in the same transaction as the corresponding assignment, status-log, requester-edit, or cancellation event where applicable.

The Android client reads the inbox through authenticated, caller-scoped RPCs. It does not query the private notification table directly. The list is bounded (1–100; Android requests 50), and supports loading, refresh, empty/error/retry states, unread/read presentation, mark-read, and role-scoped incident/conversation navigation.

## Event families

- `incident_assigned`: assignment notifications for the requester and assigned field units.
- `incident_status_changed`: meaningful status changes for the requester and assigned team, excluding the actor.
- `incident_message`: incident conversation activity.
- `incident_request_updated`: pending requester edit/location-correction alert for dispatchers.
- `incident_cancelled`: pending requester cancellation alert for dispatchers.

Initial pending and assignment status logs are skipped where a dedicated assignment event already exists, avoiding duplicate alerts. Requester-edit and cancellation alerts are generic and do not copy coordinates, phone numbers, narrative descriptions, or edit payloads into notification text.

## Access-control design

- `user_notifications` has RLS enabled and no direct table privileges for `anon` or `authenticated`.
- Private SECURITY DEFINER trigger functions pin `search_path = pg_catalog` and use fully qualified table references.
- `api_my_notifications` derives the recipient from `auth.uid()` → `profiles.auth_user_id`; the client cannot select another recipient.
- `api_my_notification` performs caller-scoped lookup for a push tap. A notification ID is not an authorization grant.
- `api_mark_notification_read` updates only a row owned by the caller and returns the same not-found result for missing and non-owned IDs.
- The Realtime Broadcast topic is private and profile-scoped. Its payload contains only notification ID and event type. Android treats it as a refresh hint and reloads through the authenticated inbox RPC; it does not trust broadcast content as notification data.
- Android checks the intended recipient profile against the currently restored authenticated session before displaying an FCM push. Push tap routing returns through the authenticated inbox and caller-scoped notification lookup.

## Delivery and reliability boundaries

The repository contains a trusted Laravel FCM HTTP v1 sender, a server-only delivery queue/attempt ledger, bounded claims and leases, retry backoff, invalid-token handling, Android token lifecycle coordination, and an FCM receiving service. These code paths are implemented in source and covered by contracts; that is not evidence that Firebase credentials, the scheduled worker, or physical-device delivery are configured and operating in production.

Realtime is an optimization, not durable delivery. Android retains a 30-second authenticated inbox refresh fallback. The listener now accepts assignment, status, message, requester-edit, and requester-cancellation event hints. A successful initial join or reconnect also triggers an authoritative inbox refresh because events emitted while disconnected are not assumed to be replayed.

FCM is a supplemental prompt, not proof that the recipient saw, read, accepted, or acted on an emergency update. The inbox remains authoritative.

## Validation

CI includes source contracts for Android inbox behavior, notification Realtime authorization/fallback, FCM server delivery boundaries, token lifecycle/account switching, requester edit/cancellation notification policy, and notification action feedback. PostgreSQL migration replay includes notification RPC security and FCM delivery queue tests. These checks must be inspected on the current PR head before claiming success.

Source contracts and disposable PostgreSQL replay do not prove live Supabase Realtime authorization, Firebase credential validity, scheduled worker execution, FCM receipt, Android OEM background behavior, or visual acceptance on a physical device.

## Remaining release gates

1. Confirm Supabase migration ordering and replay against the intended project before any production migration.
2. Verify private Realtime authorization and reconnect behavior in an isolated Supabase staging project.
3. Configure and validate Firebase Android app registration and trusted server credentials without exposing secrets to the client.
4. Verify the scheduled FCM worker/cron execution, lease recovery, retry behavior, and delivery telemetry in staging.
5. Test account switching, notification permission denial, push receipt, notification tap, logout, and reconnect on supported physical Android devices.
6. Decide whether dispatcher web notifications are needed; do not expose all users' notification records to the dispatcher dashboard by default.

## Deployment boundary

No live Supabase migration, Render deployment/configuration change, Firebase credential installation, production token registration, or production notification was performed by this review. Do not apply migrations, enable scheduled delivery, or deploy until the release gates and explicit authorization are satisfied.


## Dispatcher cancellation-alert navigation review (2026-11-20)

The dispatcher notification tap path was reviewed against the actual Android and Laravel code. The current implementation already supports the normal cancellation path: a push tap is resolved through the caller-scoped notification lookup, Android routes to the dispatcher queue, and the dispatcher-only `GET /incidents` collection includes terminal statuses in its bounded snapshot. The dispatcher screen renders resolved/cancelled incidents in a separate, read-only `Closed incidents` section, while active progress and assignment controls remain in their own sections. This separation is intentional: a cancelled SOS must remain inspectable as history and must not re-enter the pending queue.

A new source contract pins the dispatcher notification focus state, inclusion of terminal records in the dispatcher snapshot, the separate closed-history rendering, the status indicator and assignment audit history, and the absence of assignment/progress/chat actions from the closed-history card. The existing CI step for `tools/test_dispatcher_active_incident_filter.py` already runs this expanded contract.

### Known bounded-history limitation

The dispatcher collection is capped at 100 incidents, while the closed-history UI shows the latest five by **request date**, not closure time. A notification older than the 100-record snapshot can still be found in the caller's notification inbox, but its incident may not be present in the currently loaded dispatcher collection. In that case the UI fails closed with an unavailable-target message. A future improvement would be an ID-scoped, dispatcher-authorized incident-detail RPC so a legitimate older notification can open its exact incident without widening the list query. Do not bypass this bound by loading an unbounded incident collection.

Research basis: OWASP recommends validating authorization for every object request and specifically testing IDs surfaced through notification links, rather than trusting the fact that a user has authenticated or can supply an identifier. See [OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html) and [OWASP REST Assessment Cheat Sheet — Broken Object Level Authorization](https://cheatsheetseries.owasp.org/cheatsheets/REST_Assessment_Cheat_Sheet.html).


## Historical dispatcher notification target review (2026-11-20)

A valid dispatcher alert may refer to an incident older than the bounded 100-record dispatcher list. Current Android focus resolution searches the loaded list; if the incident is absent, it shows an unavailable-target message rather than bypassing authorization. This is safe but can strand a legitimate historical alert. The researched follow-up is an ID-scoped, dispatcher-only, read-only incident lookup that leaves the bounded queue intact. The full authorization matrix, transport considerations, and implementation gates are documented in [RESEARCH_DISPATCHER_HISTORICAL_INCIDENT_LOOKUP.md](RESEARCH_DISPATCHER_HISTORICAL_INCIDENT_LOOKUP.md).
