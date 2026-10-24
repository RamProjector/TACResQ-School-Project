# Render bridge canary (QA only)

The bridge is deliberately opt-in and incomplete. It is **not** a production cutover.

## Build the canary

From the repository root:

```sh
./gradlew --no-daemon assembleDebug -PuseSupabase=true -PuseRenderBridge=true
```

This requires a configured Supabase project and a deployed Render service. The APK uses the existing Supabase sign-in/sign-up flow and keeps the existing `tacresq://auth/callback` flow.

## What goes through Render

Only these allowlisted RPCs use `POST /api/bridge/rpc/{function}` when the opt-in flag is set:

- `api_units`
- `api_create_incident_v4`
- `api_cancel_my_incident`
- `api_update_my_incident`

All other RPCs—including profile loading, incident lists, duty status, assignment/status transitions, and location reporting—remain direct to Supabase. Do not expand the allowlist until each RPC's complete SQL definition, role/ownership checks, grants, and response shape have been reviewed.

The bridge forwards the caller's Supabase access token. It must never accept a client-provided role as authority or use a service-role key to perform a user's operation.

## Important behavior

- The bridge flag is only enabled for a debug build and requires `-PuseSupabase=true`.
- Release builds remain on direct Supabase.
- Bridge failures are surfaced to the caller. There is no automatic write fallback to direct Supabase after a timeout, because the server may have committed a request before the connection failed. The SOS client request ID remains the idempotency key.
- The existing direct-Supabase adapter remains available by building with `-PuseSupabase=true` and omitting `-PuseRenderBridge=true`.

## Required verification before widening the canary

1. Confirm Render is running the bridge commit and its Supabase URL/publishable-key environment is correct.
2. Use synthetic QA accounts only; verify requester SOS create, idempotent retry, cancellation rules, request editing, and dispatcher unit listing against the QA Supabase project.
3. Verify HTTP status and JSON response compatibility for each allowlisted RPC and test expired tokens, wrong roles, invalid inputs, timeouts, and rate limits.
4. Confirm all non-allowlisted functions still use Supabase directly and that no service-role credential exists in the APK.
5. Perform emulator/device testing with network interruption and activity recreation before considering any broader rollout.

This canary is for controlled integration testing only. TACResQ is not yet a production-grade emergency dispatch service and must not be the sole channel for emergency assistance.
