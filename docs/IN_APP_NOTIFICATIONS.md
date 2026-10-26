# Persistent in-app notifications — implementation boundary

## Scope of this implementation stage

This workstream adds the server-side persistence/authorization foundation and a first Android inbox in the existing Alerts & safety destination. It does **not** implement Firebase Cloud Messaging delivery, web notification UI, notification-to-incident deep links, or a claim of real-time delivery.

The authoritative notification record is stored in PostgreSQL. Database triggers create notifications in the same transaction as assignment/status-log writes, so a successful state change and its in-app notification commit or roll back together.

## Events currently generated

- A new incident assignment creates a notification for the assigned responder/driver and the incident requester.
- A meaningful incident status-log entry creates notifications for the requester and assigned units, excluding the profile recorded as the actor.
- Initial 'pending' logs and 'assigned' status logs are skipped by the status-log trigger to avoid duplicating the assignment alert.

Notification text is intentionally generic. It does not copy incident descriptions, phone numbers, or coordinates into notification bodies. The incident ID is a navigation reference, not an authorization grant; opening an alert routes to the current role-scoped incident list and displays the target only if that API currently returns it. Missing or unauthorized targets show a generic unavailable state.

## Access-control design

- 'user_notifications' has RLS enabled and no direct table privileges for 'anon' or 'authenticated'.
- Trigger writers are private SECURITY DEFINER functions with 'search_path = pg_catalog' and fully qualified table references.
- 'api_my_notifications' derives the recipient from 'auth.uid()' → 'profiles.auth_user_id'; it never accepts a recipient ID from the client.
- 'api_mark_notification_read' updates only a row owned by the caller. It returns 'false' for both missing and non-owned IDs to avoid leaking another user's notification existence.
- The listing RPC accepts a bounded limit (1–100). The Android inbox requests 50 records and has lifecycle-bound refresh, loading/empty/error states, retry, unread/read styling, and mark-read actions.
- SECURITY DEFINER functions, grants, triggers, and RLS are all part of the security boundary; client-side hiding is not authorization.

## Validation

The clean-PostgreSQL migration replay test covers assignment and status event creation, recipient isolation, cross-user mark-read denial, idempotent read marking, anonymous grant denial, direct table access denial, unauthenticated calls, and the maximum page size. The Android source-contract test checks RPC use, recipient filtering in demo mode, lifecycle-bound polling, visible loading/empty/error/retry states, and preservation of the central GPS Map destination. CI results for the current branch must be checked before treating these tests as passed; source contracts do not replace runtime UI or live-backend tests.

## Follow-up work required

1. Add a typed Android repository/API model and a dedicated Notifications destination without removing or demoting the prominent GPS Map destination.
2. Add an accessible notification list with unread/read styling, loading/empty/error states, retry, mark-read behavior, and incident navigation that re-checks authorization.
3. Add equivalent web notifications only if dispatchers need personal alerts; do not expose all users' notifications to a dispatcher dashboard by default.
4. If FCM is added, use it only as an optional delivery channel. Store/rotate/remove tokens securely, handle permission denial and sign-out, make delivery retry-safe, and never put service credentials in Android or browser code.
5. Test duplicate event behavior for team assignments and all supported status transitions against the current RPC contract before live rollout.

## Deployment boundary

No live Supabase migration, Render deployment, user/account mutation, token registration, or production notification was performed by this change. Apply the migration only after review and explicit authorization. Until native UI, real-backend integration, and device validation are complete, this is a backend foundation rather than a finished notification feature.
