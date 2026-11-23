# Admin account suspension: Android direct-call and notification boundary review (batch 6)

## Scope and evidence

Reviewed the Android `SupabaseTacApi` implementation and its direct PostgREST/Storage transport, the optional Render bridge transport, and the checked-in `tacresq-notify` Edge Function source mirror. This batch records concrete client call paths and the security consequences for suspension. It does not make runtime or deployment claims.

## 1. Android is not universally behind Laravel

The normal Android transport constructs a Supabase client with the publishable key and applies the stored signed-in user's bearer token to requests. Unless the optional bridge is enabled, business RPCs go directly to Supabase PostgREST.

The source explicitly uses direct Supabase calls for, among others:

- Profile and duty: `api_me`, `api_set_duty_status`.
- Requester incident lifecycle: `api_create_incident_v4`, `api_cancel_my_incident`, `api_update_my_incident`.
- Dispatch and field work: `api_all_incidents`, `api_units`, `api_active_responder_locations`, `api_update_incident_status`, `api_assign_incident`, `api_assign_incident_team`, `api_release_incident_assignment`, `api_claim_incident`, `api_claimable_incidents`, `api_set_incident_claimable`, `api_record_location_v2`.
- Notifications and FCM: `api_my_notifications`, `api_my_notification`, `api_mark_notification_read`, `api_register_fcm_token`, `api_unregister_fcm_token`.
- Chat: `api_my_incident_messages`, `api_send_incident_message`, and `api_send_incident_image_message`.
- Assignment history uses a direct authenticated table read of `incident_assignment_events`, relying on RLS.

Some operations remain direct even when the optional Render bridge is enabled: the Android source explicitly keeps assignment release, field claim/claimability, notification RPCs, FCM token RPCs, chat, image upload/download, and assignment-history RLS reads on the direct Supabase path.

**Consequence:** Laravel route middleware cannot be the enforcement boundary for account suspension. Every direct PostgREST function, RLS policy, Storage policy, and direct table grant must reject a suspended caller independently.

## 2. Storage path is direct and session-authenticated

Android uploads to `storage/v1/object/{bucket}/{path}` and downloads from the authenticated Storage object endpoint using the same configured Supabase client. It does not rely on a Laravel route for chat media. Therefore the Storage policy/helper guard recorded in batch 5 is a required server-side suspension boundary, not an optional bridge hardening.

The deterministic object path contains incident ID, sender profile ID, and client message UUID. The Android caller supplies the sender ID for path construction, but the database RPC derives sender identity from `auth.uid()` and validates the exact expected path. Preserve that server-side identity derivation; do not treat the client-provided sender ID as authorization.

## 3. Optional bridge is not the universal path

The optional bridge transport forwards the caller's bearer token to allowlisted Laravel RPC routes. The normal release configuration remains direct Supabase according to the repository's transport comments and configuration contract. Even if bridge middleware checks account state, direct Android requests remain possible by design.

The bridge may add defense in depth, but it cannot replace database/RLS/Storage enforcement. Keep its allowlist narrow and ensure it forwards the caller JWT for caller-scoped RPC authorization.

## 4. Notification Edge Function: exact current-state omission

The checked-in `supabase/functions/tacresq-notify/index.ts` source mirror:

1. Validates the supplied bearer token through `admin.auth.getUser(userToken)`.
2. Resolves the caller profile using a service-role query selecting only `id,role`.
3. Loads the incident and applies event-specific role/ownership checks.
4. Loads recipient FCM registrations through service-role access and sends notifications.

The profile query does not select or test an account-state field. JWT validity and role/incident ownership therefore do not establish that the caller remains active. The dispatcher recipient query also selects all dispatcher profiles by role, without an active-account predicate. The FCM token query is likewise not joined to current account state.

**Required suspension behavior:** before any event-specific action, resolve the caller's current account state with the privileged client and reject suspended callers. For recipient selection, exclude suspended recipient accounts before loading tokens. Do not infer that an account is active merely because its Auth user/token exists. Preserve the existing notification delivery architecture and separately audit the deployed caller topology before changing or disabling this legacy/parallel function.

This source finding is not evidence that the function is currently deployed from this exact mirror or that a caller is active in production. No function was deployed or changed.

## 5. Required regression matrix

- Direct Android PostgREST request with a still-valid JWT for a suspended requester/responder/driver/dispatcher/Admin is rejected at the database boundary.
- The same request through the optional bridge is rejected; bridge middleware is defense in depth, not the only control.
- Direct Storage upload and download for a suspended participant are denied.
- Active users retain their current allowed incident/chat/notification flows.
- Direct assignment-history SELECT obeys a restrictive active-account condition without widening dispatcher/participant visibility.
- Suspended caller cannot invoke the notification Edge Function successfully, even with a still-valid JWT.
- Suspended dispatcher/requester recipients are not selected for FCM delivery.
- Role change or reactivation cannot accidentally clear or bypass suspension unless the explicit reactivation operation is authorized and audited.

## Finding

The Android app has multiple direct Supabase paths and does not universally traverse Laravel. The notification Edge Function also uses service-role access after validating a token and currently selects only caller ID/role. Account suspension must therefore be enforced in database RPC/RLS/Storage paths and explicitly in service-role code, not solely in the web portal or Laravel middleware.

## Verification boundary

This is source-level tracing. No live Android request, bridge request, Edge Function invocation, notification delivery, Auth session, or suspension action was executed.
