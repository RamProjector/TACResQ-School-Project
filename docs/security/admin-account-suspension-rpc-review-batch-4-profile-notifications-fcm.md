# Account suspension authorization review — batch 4: profile, notifications, and FCM

**Branch:** `security/admin-account-suspension`  
**Status:** source review only; no account-state schema or enforcement changes.

## RPC and direct-table findings

| Entry point / surface | Existing controls observed | Account-state finding and required boundary |
|---|---|---|
| `api_me()` | SECURITY INVOKER profile read resolved through `auth.uid()`; current implementation returns the caller's profile identity, role, and duty status (plus current profile fields in the effective definition). | No account-state predicate. Decide the safe suspended-session contract: either return only a minimal suspension state needed to explain access denial, or deny profile data and rely on a separate safe account-status endpoint. Do not accidentally expose full profile data to a suspended user. |
| `api_my_notifications(integer)` | SECURITY DEFINER; resolves the profile from `auth.uid()`, validates limit 1–100, and returns only notifications where `recipient_user_id` equals the caller profile. Direct table privileges on `user_notifications` are revoked. | No account-state check. Suspended users can still read notification contents with a valid JWT. Decide whether suspended users should receive a minimal suspension notice only; do not allow ordinary incident notifications to remain a bypass around the suspension gate. |
| `api_my_notification(bigint)` | SECURITY DEFINER; resolves caller profile, validates positive notification ID, and returns only the matching row owned by that profile. A missing row and another user's row are indistinguishable. | No account-state check. A push locator must not become an access path to incident notification details after suspension. Apply the same explicit suspended-session response contract as the list RPC. |
| `api_mark_notification_read(bigint)` | SECURITY DEFINER; resolves caller profile, validates ID, updates only the row whose ID and recipient profile match, and returns false for missing/non-owned rows. | No account-state check. Mark-read is a mutation, so deny it to suspended callers unless a narrowly defined suspension-notice acknowledgment is intentionally supported. |
| `api_register_fcm_token(text)` | Final SECURITY DEFINER function validates token length, derives profile from `auth.uid()`, and upserts only the caller's token. | No account-state check. Suspended accounts must not register or rotate tokens. Do not assume FCM registration itself sends a push or controls recipients. |
| `api_unregister_fcm_token(text)` | SECURITY INVOKER self-delete operation; profile is derived from `auth.uid()`, and the delete is scoped to the caller's profile/token under RLS. | This is a useful cleanup exception: consider allowing a suspended user to remove their own push token, while preventing reads, inserts, updates, or token reassignment. Test the exact RLS behavior rather than broadly allowing FCM table access. |
| `public.fcm_tokens` | RLS enabled; historical direct authenticated grants include SELECT/INSERT/UPDATE/DELETE, with self-scoped SELECT/INSERT/DELETE policies. The final registration RPC is SECURITY DEFINER; unregister is invoker/self-scoped. | Direct table grants and policies need an explicit active-account condition. If cleanup deletion remains allowed, isolate it to self-owned token deletion only. Do not allow suspended users to enumerate tokens or attach a token to another profile. |
| `public.user_notifications` | RLS enabled; all direct client privileges and sequence access are revoked. Writes occur through trusted trigger functions; reads/mark-read occur through scoped SECURITY DEFINER RPCs. | Direct table closure is good, but every authenticated-callable notification RPC needs current account-state enforcement. Trigger writers should continue recording operational events regardless of recipient suspension; suspension should control access/delivery, not erase audit/notification history. |

## Device/security tables

The clean-replay inventory includes `device_challenges`, `device_integrity_checks`, `device_security_events`, `device_sessions`, and `user_devices`. The replayed public-table ACL/RLS inventory and authenticated-callable private-function inventory must be used to establish which are server-only and which have client-facing RPCs.

Do not assume these are safe merely because they are named “device” or “security.” The next review must trace:
- every authenticated-callable RPC that reads or mutates these tables;
- whether an existing device/session record is revoked or invalidated on suspension;
- whether Android can continue submitting integrity/security events after suspension;
- whether a session record is used for authorization or only telemetry;
- whether any privileged backend worker relies on these records.

The suspension design must not claim immediate global sign-out based only on an Auth ban or refresh-token revocation. Database authorization must reject the still-valid JWT independently.

## Notification behavior and operational continuity

- Keep event creation and immutable operational history intact. A suspension should not delete notifications, incidents, messages, assignments, or device history.
- Separate “can receive/read ordinary app notifications” from “can receive a safe account-suspended explanation.”
- If a suspension notice is needed, it should contain only a generic reason/status and avoid incident details.
- Prevent FCM token registration for suspended accounts. A narrow self-delete path may be retained to let a user clean up their own installation token.
- Recheck all direct `fcm_tokens` policies because a SECURITY DEFINER RPC guard does not protect direct table requests.

## Source files inspected

- `supabase/migrations/20261105235009_persistent_in_app_notifications.sql`
- `supabase/migrations/20261112170112_add_my_notification_lookup.sql`
- `supabase/migrations/20261114153550_fcm_token_account_switch.sql`
- `supabase/migrations/20261015092225_location_and_push.sql`
- `supabase/migrations/20261015080806_tacresq_core_schema.sql`
- `supabase/migrations/20261023103737_per_responder_assignment_status.sql`

No connected Supabase project was queried or modified. This review describes repository migration behavior, not the current deployed state of any live project.
