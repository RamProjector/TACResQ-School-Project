# Admin account suspension: Web Admin and Laravel identity-plane review (batch 7)

## Scope and evidence

Reviewed `backend/web/src/lib/supabase.ts`, the Admin Console call surface, `backend/src/app/SupabaseBridge.php`, the bridge allowlist, bridge feature tests, and the Laravel Sanctum routes in `backend/src/routes/api.php`.

No account records, Sanctum tokens, Supabase Auth sessions, production database, or server configuration were changed.

## 1. Web Admin is a direct Supabase client

The web Admin client initializes `@supabase/supabase-js` with the Supabase URL and publishable key and persists/refreshes the Supabase Auth session in the browser. It directly calls:

- `api_me`, `api_all_incidents`, `api_units`, `api_active_responder_locations`, and `api_assign_incident`.
- `api_admin_list_users_page`, `api_admin_set_user_role`, `api_admin_analytics_summary`, `api_admin_list_audit_logs_page`, and `api_admin_list_investigation_events`.
- `api_dependency_health` is also called directly by the client.

The Admin MFA gate is client-side flow control only; the database migration separately enforces AAL2 + TOTP for protected Admin RPCs and the restrictive direct audit-log policy. This server-side MFA boundary must remain intact.

**Suspension consequence:** browser-side sign-out, hidden navigation, or a disabled Admin page is not enforcement. The active-account predicate must be checked by every protected Admin RPC/internal implementation and by direct table RLS, including audit/investigation sources. A suspended Admin with a still-valid AAL2/TOTP JWT must be rejected even though MFA remains valid.

## 2. Render bridge forwards caller identity, but does not itself check current profile state

The bridge's `getUser()` calls Supabase Auth `/auth/v1/user` with the caller bearer token and publishable key. `callRpc()` forwards that same bearer token to an allowlisted PostgREST RPC. The bridge does not use a service-role credential for these business RPC calls, so PostgreSQL can continue to evaluate `auth.uid()` as the caller.

This is a useful identity-preserving design. It is not an account-state check: `getUser()` validates the Auth token and records the Auth user UUID for audit metadata, but does not resolve the current TACResQ profile state. The SQL RPC/RLS boundary must perform that check. Keep the explicit allowlist and do not add a function without reviewing its effective SQL definition, grants, and account-state guard.

## 3. Separate Laravel Sanctum identity plane

The Laravel API routes also expose a distinct authentication path:

- `/login` looks up a Laravel `User` record by normalized email and issues a Laravel Sanctum personal-access token.
- `/register` creates a Laravel `User` with requester role and issues a Sanctum token.
- Protected routes use `auth:sanctum` and operate on Laravel `User` and `Incident` models.
- `/logout` deletes the current Sanctum token; `/logout-all` deletes all tokens for that Laravel user; `/change-password` rotates those tokens.
- The Laravel bridge path is different: it validates Supabase Auth tokens and forwards the caller JWT to PostgREST.

The device-security plan also explicitly distinguishes Laravel Sanctum tokens from Supabase Auth sessions: they are not interchangeable and cannot be stored as `device_sessions.auth_session_id`.

**Important unresolved boundary:** repository evidence shows two authentication/token domains. This review does not establish that every Laravel `User` row maps one-to-one to a Supabase `profiles` row, or that the legacy Sanctum endpoints are the current Android release authentication path. Do not silently treat a Supabase suspension flag as if it automatically revokes or blocks Sanctum users. Before implementation, decide whether the legacy Laravel account domain remains supported and, if so, define a separate account-state enforcement and revocation policy for its login and `auth:sanctum` routes. Conversely, do not merge the two identity stores by matching email alone.

## 4. Required implementation boundaries

1. Keep Supabase account suspension authoritative for Supabase Auth users and all direct PostgREST/Storage requests.
2. Keep the web Admin's AAL2/TOTP database enforcement; add current-account-state rejection without weakening the restrictive MFA policy.
3. Keep the Render bridge's caller-JWT forwarding and allowlist. Add bridge-side state checks only as defense in depth after a verified profile mapping is available; database enforcement remains mandatory.
4. Establish whether the Laravel Sanctum API is still a supported login/business path. If yes, add its own server-side suspended/disabled state check to both login issuance and every `auth:sanctum` route, with explicit logout-all/session revocation behavior. If not, document retirement and avoid adding new dependencies on it.
5. Do not map identities by email alone. Require an immutable verified linkage between Supabase Auth UUID and profile, and a separate explicit linkage if the Laravel user model is retained.
6. Add tests for suspended Supabase Admin with valid AAL2/TOTP, suspended direct Android caller, suspended bridge caller, and suspended Laravel Sanctum user (if that path remains supported).

## Findings classification

| Surface | Current identity/authorization | Suspension status |
|---|---|---|
| Web Admin | Browser Supabase Auth JWT; direct RPC calls; database AAL2/TOTP checks | Must be guarded in RPC/RLS, not only UI |
| Render Supabase bridge | Validates Supabase JWT and forwards same caller JWT to allowlisted PostgREST RPC | No current profile-state check in bridge; DB guard required |
| Laravel legacy API | Separate Laravel User + Sanctum personal-access tokens + Eloquent routes | Separate account domain; mapping/support status must be resolved before claiming full coverage |

## Verification boundary

This is source-level review. It does not establish which legacy Laravel routes are currently used in production, whether every Laravel user maps to a Supabase profile, or whether any deployment has an account-suspension mechanism. No runtime request or token revocation was performed.
