# Account suspension authorization review — batch 22: Admin web, notification Edge Function, and Laravel/Sanctum boundaries

**Evidence:** repository source read from main at the file revisions recorded below. This batch is a static source review; it does not query live Supabase, Render, or production traffic.

## Admin web: direct Supabase RPC calls

The Admin web client in backend/web/src/lib/supabase.ts initializes a Supabase JS client with the publishable key and persisted user session. It calls RPCs directly; it does not route these calls through Laravel middleware.

| RPC | Admin web purpose | Required account-state enforcement |
|---|---|---|
| api_me | Resolve signed-in profile | Explicitly define minimal suspended-profile behavior; no protected directory or incident data |
| api_all_incidents | Dispatcher incident queue data used by web surface | Active dispatcher check; do not rely only on role helper |
| api_units | Field-unit directory | Active dispatcher check; suspended field units not eligible targets |
| api_active_responder_locations | Responder map/location data | Active dispatcher check at private definer boundary |
| api_assign_incident | Assignment action exposed by the shared web client library | Active dispatcher plus active target field unit; retain transactional assignment checks |
| api_admin_list_users_page | Admin account directory | Active Admin + AAL2/TOTP check |
| api_admin_set_user_role | Admin role change | Active Admin + AAL2/TOTP; target state and audit atomicity |
| api_admin_analytics_summary | Admin analytics | Active Admin + AAL2/TOTP |
| api_admin_list_investigation_events | Admin investigation event search | Active Admin + AAL2/TOTP |
| api_admin_list_audit_logs_page | Admin audit log search | Active Admin + AAL2/TOTP |

The shared web client type contract also declares api_admin_list_audit_logs; the currently extracted RPC call-site list did not find a direct invocation of that legacy unpaged method. Its authenticated grant still makes it part of the server-side authorization inventory.

The Admin web code has no direct .from(table) calls in the reviewed client library; its data access is through the RPCs above. That finding is limited to this file, not every file in the web application. A full repository-wide web search remains necessary before asserting there are no direct PostgREST table or Storage operations elsewhere.

## Notification Edge Function: concrete service-role boundary

File: supabase/functions/tacresq-notify/index.ts.

Observed flow:
1. Requires POST and a bearer token.
2. Creates a Supabase client using the server-only service-role key.
3. Calls admin.auth.getUser(userToken) to validate the caller's Supabase Auth token.
4. Reads the caller profile using service-role .from("profiles").select("id,role").
5. Reads the incident using service-role .from("incidents").select("id,requester_id,assigned_to,status").
6. Checks role, incident ownership/assignment, and event type.
7. Reads recipient profiles for dispatchers using role-only filtering, or uses the incident's assigned/requester profile ID.
8. Reads fcm_tokens for the selected recipients and sends push notifications.
9. Deletes invalid FCM token registrations using service-role access.

### Confirmed account-state gap in the reviewed source

The function uses service-role access, so client RLS policies do not automatically constrain these profile, incident, and FCM-token reads/writes. The caller profile query retrieves only id,role; the recipient dispatcher query filters only role = dispatcher; and the assigned/requester recipient path does not load recipient account state. No current account-state field or equivalent active-state predicate is checked in the reviewed function.

Consequently, after an account-state model exists, this function must explicitly:
- Reject a suspended actor before authorizing the event.
- Exclude suspended dispatcher recipients from incident-created notifications.
- Exclude suspended assigned-unit/requester recipients from notification delivery.
- Preserve legitimate system-generated notifications only through a separately authenticated, narrowly scoped system-event contract; do not let the service-role key alone imply that the end user is active.
- Avoid disclosing whether a recipient is suspended in the function response.
- Keep invalid-token cleanup narrow and independent from account-state enforcement.

This finding is a source-level authorization gap for the future suspension model; it is not evidence that the current function has been exploited.

## Laravel / Sanctum is a separate local identity boundary

File: backend/src/routes/api.php.

Observed route groups:
- Public /login and /register create/issue Laravel Sanctum tokens for the Laravel User model.
- auth:sanctum protects /logout, /logout-all, /change-password, /me, /me/duty-status, incident list/create/status routes, unit listing, and assignment routes.
- A separate /bridge/me and /bridge/rpc/{function} path handles Supabase caller-token forwarding for the optional bridge.
- Route-local role checks and Laravel DB transactions apply to the Laravel-backed prototype routes; they are not equivalent to Supabase RLS/RPC checks.

The current source does not establish a trusted immutable mapping between a Laravel Sanctum User and a Supabase auth.users / public.profiles identity. Do not join or authorize across these systems by email. Treat the Sanctum prototype as a distinct account domain until a reviewed identity-linking design and migration exist.

For any later suspension feature:
- Either explicitly scope account suspension to the Supabase identity domain and clearly state that legacy Sanctum accounts are separate, or build and test an immutable identity mapping before claiming one account-wide suspension action covers both.
- If Laravel accounts remain in service, add a distinct Laravel account-state gate to Sanctum routes and ensure token issuance, token use, password recovery, and privileged routes honor it.
- Do not silently treat Laravel role, Laravel token revocation, or Laravel duty status as a substitute for Supabase account state.
- Ensure a suspension does not silently rewrite duty status or cancel active incident assignments in either database.

## Remaining inventory gaps

1. Search every web source file for .rpc(, .from(, .storage, Auth methods, and Edge Function invocation; the Admin client library alone is not the whole web app.
2. Inspect the full Laravel bridge controller/service and all jobs/commands for service-role access and caller identity forwarding.
3. Inventory every deployed Edge Function from repository configuration, including scheduled/internal functions, and identify whether it is user-triggered or system-triggered.
4. Add non-production tests proving suspended actors and recipients are denied/skipped in the notification Edge Function, including a valid still-unexpired JWT.
5. Add separate tests for Sanctum if the legacy Laravel account domain is retained; do not infer cross-domain account linkage.

## Source files reviewed

- backend/web/src/lib/supabase.ts — direct Supabase JS initialization and RPC methods.
- supabase/functions/tacresq-notify/index.ts — service-role FCM notification flow and current role/ownership checks.
- backend/src/routes/api.php — Sanctum routes, local Laravel data operations, and bridge routes.
- backend/src/config/tacru.php — service-only Supabase secret configuration and explicit bridge allowlist.

No connected Supabase project, production data, Auth session, Render deployment, or account record was changed. No account-state migration or UI is included.


## PR #347 implementation addendum — Laravel Supabase bridge

The optional Supabase bridge now performs an active-profile preflight for both `GET /bridge/me` and `POST /bridge/rpc/{function}` after Supabase Auth validates the caller's bearer token:

1. It invokes `api_me` using the same original caller JWT and publishable key.
2. The `api_me` invoker query is subject to the restrictive `profiles` RLS policy installed by the account-lifecycle migration. A suspended profile is invisible, so the RPC returns no profile.
3. The bridge returns 403 and does not forward the requested RPC or return Auth identity data when the profile is missing.
4. An upstream profile-preflight error fails closed as a sanitized 502; it is not misreported as a suspended account.
5. No email or user-metadata join is used, and no service-role credential is used for the preflight.

Feature coverage was added in `backend/src/tests/Feature/SupabaseBridgeTest.php` for suspended/unprovisioned profile denial on both bridge routes, preflight outage behavior, and preservation of the caller JWT. The lifecycle SQL regression now asserts that `api_me()` cannot resolve a suspended profile under the same still-valid JWT. CI now also runs `tools/test_laravel_bridge_account_lifecycle_contract.py`, which locks the Auth-validation → per-user rate-limit → active-profile preflight → denial-audit ordering on both routes and prevents the requested RPC from running before the profile gate. This source contract complements (and does not replace) the Laravel feature-test job.

**Boundary remains explicit:** this does not cover the separate Laravel Sanctum `User` records or their personal-access tokens. No immutable identity mapping to Supabase profiles exists, so a Supabase Admin suspension does not claim to suspend those legacy accounts. If Sanctum remains a supported account domain, it needs its own lifecycle state, privileged management path, audit, and tests; do not link accounts by email.
