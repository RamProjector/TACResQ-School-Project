# Account suspension authorization review — batch 21: Android, Render bridge, and direct Supabase operation map

**Evidence scope:** repository source on `main` at the reviewed file revisions listed below, cross-referenced with the clean-replay callable catalog. This is a source map, not live traffic telemetry or proof that a Render deployment is active.

## Transport architecture that affects enforcement

- The Android release/default transport calls Supabase directly using the signed-in user's bearer token.
- The Render bridge is opt-in for debug builds only when both `useSupabase` and `useRenderBridge` are enabled. It is not a universal gateway.
- Supabase Auth login/signup/session endpoints remain direct to Supabase even in bridge mode.
- The bridge validates the user's Supabase token and forwards that same caller token to PostgREST. It must not replace the caller with a service-role identity.
- Some security-sensitive operations deliberately remain direct Supabase RPCs, table reads, or Storage operations even when the bridge is enabled.
- Therefore, account-state enforcement must exist at the effective Supabase database/RLS/Storage boundary and at any server-side service-role function that performs work on behalf of a caller. Laravel middleware alone cannot enforce the policy.

## Android operations verified in `SupabaseTacApi.kt`

| Operation | Direct Supabase default | Optional Render bridge | Suspension enforcement boundary |
|---|---|---|---|
| Login/signup and profile bootstrap | Auth endpoints and `api_me` | `api_me` can use bridge after Auth returns a token | Auth lifecycle and profile RPC; define safe minimal suspended-state response without opening protected reads |
| Current profile | `api_me` | `api_me` | Profile/RPC boundary; decide explicit suspended profile behavior |
| Duty status | `api_set_duty_status` | Bridge route exists | RPC plus direct table RLS; active field-unit requirement |
| My incident list | `api_my_incidents` | Bridge route exists | RPC and underlying RLS/helper predicates |
| Dispatcher incident queue | `api_all_incidents` | Bridge route exists | Active dispatcher check and RLS |
| Field-unit directory | `api_units` | Bridge route exists | Active dispatcher check; suspended field units excluded from selectable targets |
| Active responder coordinates | `api_active_responder_locations` | Bridge route exists | Private definer helper and active dispatcher check |
| Create incident | `api_create_incident_v4` | Bridge route exists | Current requester account state before idempotency replay or write |
| Cancel incident | `api_cancel_my_incident` | Bridge route exists | Current requester account state and ownership |
| Edit incident | `api_update_my_incident` | Bridge route exists | Current requester account state and ownership |
| Dispatcher incident progress | `api_update_incident_status` | Bridge route exists | Private dispatch helper and active dispatcher state |
| Team assignment | `api_assign_incident`, `api_assign_incident_team` | Bridge routes exist | Private assignment implementation; active dispatcher and active target units |
| Release assignment | `api_release_incident_assignment` | Deliberately direct | Private release path; active caller for normal release |
| Claim incident / claimable queue | `api_claim_incident`, `api_claimable_incidents` | Deliberately direct | Claim RPCs and active responder/driver checks |
| Toggle claimability | `api_set_incident_claimable` | Deliberately direct | Active dispatcher authorization |
| Record field GPS | `api_record_location_v2` | Bridge route exists | Private location writer and active assigned field-unit check |
| Notifications list/detail/read | `api_my_notifications`, `api_my_notification`, `api_mark_notification_read` | Deliberately direct | Notification RPCs and recipient account state |
| FCM token register/unregister | `api_register_fcm_token`, `api_unregister_fcm_token` | Deliberately direct | Register requires active account; unregister may be a narrow self-cleanup exception |
| Assignment history | Direct RLS-protected PostgREST table read | Deliberately direct | Table RLS must deny suspended readers |
| Incident chat history/send | `api_my_incident_messages`, `api_send_incident_message` | Deliberately direct | Chat membership helper must also check active account |
| Chat image upload/download | Direct Supabase Storage API | Deliberately direct | Storage INSERT/SELECT policies plus finalization RPC must reject suspended sender/reader |

The bridge's 14-function list in `docs/RENDER_BRIDGE_CANARY.md` is a transport allowlist, not the full Android callable surface and not a substitute for PostgreSQL authorization. The current allowlist in `backend/src/config/tacru.php` must stay synchronized with the Android bridge interface and the grant-contract test. A bridge allowlist entry does not make a direct-only operation bridged.

## Web and Laravel boundary

- The Admin web portal authenticates with Supabase and uses direct Supabase-backed Admin RPCs; it is not routed through a universal Laravel authorization gateway.
- The retired dispatcher dashboard must not be treated as a current operational client or as the only dispatcher path.
- Laravel has an explicit `bridge_rpc_allowlist` and can accept the user's Supabase bearer token for the debug bridge, but it is not the source of truth for direct Android Supabase calls.
- Legacy Laravel Sanctum sessions are a separate identity domain. Their relationship to Supabase `auth.users` and `public.profiles` is not established by matching email addresses. Any future account-state enforcement in Sanctum must use a verified immutable identity mapping, or explicitly remain a separate access domain until such mapping is built and tested.
- Server-side notification dispatch uses privileged Supabase access. It must validate the original event/caller and current recipient state explicitly; service-role RLS bypass means database client-role policies do not protect that path automatically.

## Evidence files reviewed

- `app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt` — operation-by-operation direct/bridge branching; direct-only assignment release, claim, claimable queue, claimability, notifications, FCM, assignment history, chat, and Storage operations.
- `docs/RENDER_BRIDGE_CANARY.md` — debug-only 14-RPC bridge boundary and required canary validation.
- `backend/src/config/tacru.php` — server-side bridge allowlist and service-only notification configuration.
- `docs/security/device-security-research-and-implementation-plan.md` — default direct-Supabase transport and direct RPC inventory.
- `docs/security/incident-status-lifecycle-and-team-availability-review.md` — verified Android dispatcher status path and lack of a current web dispatch status writer.
- `docs/SUPABASE_BACKEND.md` — legacy requester RPC compatibility notes.

## Remaining gaps before any account-state migration

1. Inspect every current Admin web call site and map each to its exact RPC, view, table, or Auth endpoint; the current summary does not yet enumerate the complete Admin web endpoint list.
2. Inspect all Laravel routes/controllers/jobs that use service-role or Sanctum credentials; distinguish end-user delegated calls from system-originated scheduled/notification work.
3. Verify the actual Edge Function inventory and every service-role database call, including caller/recipient identity derivation.
4. Enumerate every direct PostgREST table read/write and Storage request in Android, web, and Laravel; map to exact policy names and effective grants.
5. Implement negative tests at each effective enforcement point. A client-side route restriction or bridge allowlist test is not sufficient.
6. Keep account-state schema/UI deferred until those mappings and tests are complete.

No connected Supabase project, production data, user session, or deployment was changed. This batch adds no account-state migration or client UI.
