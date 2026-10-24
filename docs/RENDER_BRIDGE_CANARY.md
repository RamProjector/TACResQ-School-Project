# Render bridge canary (QA only)

The bridge is a **debug-only integration canary**, not a production cutover. Android's default release build continues to use direct Supabase data RPCs until a separately reviewed cutover passes authenticated live testing and device/emulator validation.

## Current canary scope

When built with both `-PuseSupabase=true` and `-PuseRenderBridge=true`, the Android adapter routes the complete current 14-function application data-RPC inventory through the authenticated Render bridge:

- `api_me`
- `api_set_duty_status`
- `api_my_incidents`
- `api_all_incidents`
- `api_units`
- `api_active_responder_locations`
- `api_create_incident_v4`
- `api_cancel_my_incident`
- `api_update_my_incident`
- `api_update_incident_status`
- `api_update_my_assignment_status`
- `api_assign_incident`
- `api_assign_incident_team`
- `api_record_location_v2`

The Android transport inventory and server `bridge_rpc_allowlist` must match exactly. The grant-contract CI also verifies that bridged RPCs are within the reviewed client RPC inventory and have explicit `PUBLIC`/`anon` execute revokes plus authenticated grants across the migrations. An allowlist entry alone is not proof that a function's SQL authorization logic is safe; each function's signature, ownership/role checks, grants, and response shape still require review.

## Build the canary

From the repository root:

```sh
./gradlew --no-daemon assembleDebug -PuseSupabase=true -PuseRenderBridge=true
```

This requires the existing Supabase project configuration and a deployed Render service. The build uses the current Supabase sign-in/sign-up flow; Auth endpoints remain on Supabase so the app can obtain the user's access token. The bridge validates that token through Supabase Auth and forwards the same caller token to PostgREST. It must never trust a role supplied by the client or use a service-role key to perform user operations.

## Safety and failure behavior

- The bridge is opt-in for debug builds and cannot be enabled unless Supabase mode is also enabled.
- Release builds remain on direct Supabase. This change does **not** prove Render is live, healthy, configured correctly, or ready for production.
- Once the bridge is enabled in a debug build, each data operation uses the bridge; a bridge error is surfaced and is not silently retried against direct Supabase. This is especially important for create/cancel/update operations because a timed-out request may already have committed.
- The requester's existing client request ID remains the SOS idempotency key.
- Supabase Auth remains direct; the publishable key is the only Supabase key embedded in the Android client. Service-role credentials must remain server-side and must not be added to the app.
- This bridge is not the owned-backend migration. Supabase remains the current live source of truth; do not remove Supabase or dual-write incidents as part of this canary.

## Required verification before any release cutover

1. Verify the deployed Render commit, `/up`, runtime configuration, and the relevant environment variables without exposing secrets.
2. Use synthetic QA accounts and a non-production dataset to test each of the 14 RPCs through Render: success response shape, invalid token, expired token, wrong role, another user's resource, invalid input, rate limits, and upstream outage.
3. Test requester create/idempotent retry/edit/cancel rules, dispatcher queue/assignment/team assignment, responder status transitions, and location reporting/visibility. Confirm rejected requests do not mutate state.
4. Confirm no service-role credential is present in APKs, browser configuration, logs, or responses.
5. Test on emulator or physical device with activity recreation, token refresh/expiry, offline/slow networks, retries, and location permission/service changes.
6. Obtain TACRU operational acceptance and a rollback plan before changing release traffic. CI and static source contracts alone are not emergency-service readiness evidence.

Until these checks are completed, treat the bridge as controlled QA only. TACResQ must not be the sole channel for requesting emergency assistance.
