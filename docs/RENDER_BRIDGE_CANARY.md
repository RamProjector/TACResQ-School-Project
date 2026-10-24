# Render bridge integration and rollout gate

The authenticated Render bridge is the intended application-data path for TACResQ release builds. Supabase Auth remains direct so the app can sign in and obtain a caller access token. This document describes the controlled QA gate; a successful build or unauthenticated smoke test is not production acceptance.

## Build modes

From the repository root:

```sh
# Demo mode: fictional in-memory data, no Supabase writes
./gradlew --no-daemon assembleDebug

# Real-backend QA: Supabase Auth + Render-bridged application data
./gradlew --no-daemon assembleDebug -PuseSupabase=true -PuseRenderBridge=true

# Release: Supabase Auth + Render-bridged application data
./gradlew --no-daemon assembleRelease
```

The release build enables the bridge. Debug builds require the explicit opt-in flags above. The release APK may be unsigned unless signing is configured.

## Data path and security boundaries

All 14 application data RPCs currently used by Android are intended to call `POST /api/bridge/rpc/{function}` when the bridge is enabled:

- `api_me`, `api_set_duty_status`, `api_my_incidents`, `api_all_incidents`
- `api_units`, `api_active_responder_locations`
- `api_create_incident_v4`, `api_cancel_my_incident`, `api_update_my_incident`
- `api_update_incident_status`, `api_update_my_assignment_status`
- `api_assign_incident`, `api_assign_incident_team`, `api_record_location_v2`

The client sends the caller's Supabase bearer token to Render. The bridge validates the token with Supabase Auth and forwards the caller identity to the allowlisted PostgREST RPC. Database grants and SQL identity, role, and ownership checks remain authoritative. The bridge must not trust a client-supplied role or use a service-role key to impersonate a user. The Android app must contain no service-role credential.

Supabase Auth login, signup, and logout remain direct to Supabase. A bridge error must be shown to the caller; it must not silently retry a write through direct Supabase, because a timed-out write may already have committed. Preserve the incident client-request ID/idempotency behavior.

## Required QA gate

Use a separate Supabase QA project whenever possible. If the configured project is used, create only clearly labeled synthetic test records and test locations; never submit real emergency reports or real people's contact/location details.

1. Confirm Render is running the current bridge deployment. Verify `/up`, `/runtime-config.json`, and that an unauthenticated request to an allowlisted bridge RPC is rejected.
2. Provision dedicated synthetic requester, responder, driver, and dispatcher identities through the trusted Supabase Dashboard. Verify each Auth identity has the intended profile and role. Do not share credentials or service-role keys in chat or source control.
3. Through the real-backend Android QA APK, verify login, profile loading, requester create/history/edit/cancel rules, idempotent retry, dispatcher queue and team assignment, responder/driver assignment and status transitions, and consent-based foreground location reporting.
4. Verify cross-role and cross-user denial: a requester cannot operate another user's incident; a responder cannot dispatch; a dispatcher-only operation is denied to other roles; expired/invalid tokens are rejected.
5. Check request/response JSON compatibility, input validation, rate limiting, timeout/offline behavior, activity recreation, and no duplicate incident creation after retry.
6. Confirm the 14 Android data calls use the bridge in bridge mode and that no service-role key is embedded in the APK. Confirm the demo build remains isolated from real backend writes.
7. Clean up test incidents and test identities when QA is finished. Record evidence for each role and test case.

## What the current smoke test does not prove

The GitHub Actions live smoke workflow checks service health, runtime configuration shape/cache headers, and rejection of unauthenticated bridge calls. It does **not** prove successful authenticated RPCs, full Android lifecycle behavior, GPS accuracy, map routing, dispatcher operations, or TACRU field readiness.

Do not describe TACResQ as production-ready or use it as the sole channel for emergency assistance until authenticated role-based QA, physical-device/network testing, monitoring and incident-response procedures, privacy review, and TACRU operational acceptance have all passed.
