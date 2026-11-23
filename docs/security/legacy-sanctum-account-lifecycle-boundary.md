# Legacy Laravel Sanctum account-lifecycle boundary

**Status:** separate identity domain; local lifecycle-state foundation and request enforcement are implemented in PR #347. The privileged transition and immutable audit workflow remain unimplemented. This does not claim that Supabase account suspension affects Laravel Sanctum users.

## Why this is separate

The Laravel API authenticates a local `App\\Models\\User` with `auth:sanctum` and Laravel personal-access tokens. The model's fillable/cast definitions and the initial `users` migration contain no immutable Supabase Auth user identifier. The optional Supabase bridge instead validates a Supabase bearer token and resolves its profile through `public.api_me()`.

There is no approved, immutable relationship between these two identities. Matching email addresses is not sufficient: addresses can change, can be duplicated across identity providers, and do not prove that the two accounts belong to the same principal. Do not add an email-based join, copy a client-supplied Supabase UUID, or infer identity from JWT metadata.

## Laravel routes in the local Sanctum domain

The following API routes are declared inside the `auth:sanctum` route group:

- `POST /api/logout` — revoke the current personal access token.
- `POST /api/logout-all` — revoke all personal access tokens for the authenticated local user.
- `POST /api/change-password` — change the local password and rotate Sanctum tokens.
- `GET /api/me` — return the local user's profile.
- `PATCH /api/me/duty-status` — update local responder/driver duty state.
- `GET /api/incidents/mine` — list the local user's incidents/assignments.
- `POST /api/incidents` — create an incident through the Laravel backend.
- `PATCH /api/incidents/{incident}/status` — update an authorized incident status.
- `GET /api/units` — retrieve the local field-unit directory.
- `POST /api/incidents/{incident}/assign` — assign a local field unit.

The public `POST /api/login` and `POST /api/register` routes issue local Sanctum tokens. The Supabase `GET /api/bridge/me` and `POST /api/bridge/rpc/{function}` routes are a different token domain and must retain their active-Supabase-profile preflight.

## Current controls and missing lifecycle capability

- Local login uses the Laravel `users` table and password hash; registration creates a local requester.
- Sanctum-authenticated routes are grouped behind `auth:sanctum`.
- Logout, logout-all, and password-change token rotation are covered by existing contracts.
- Added a local `users.is_suspended` flag (default false), nullable `suspended_at`, and bounded `suspension_reason` metadata. The fields are not mass assignable through the public User model.
- Added `account.active` middleware to the local Sanctum route group. Suspended accounts receive a non-cacheable 403 before profile, incident, unit, assignment, or mutation handlers run.
- Kept `/api/logout` and `/api/logout-all` available to suspended users so they can revoke tokens and complete local sign-out.
- Added Laravel feature tests for default-active compatibility, same-token protected-route denial, and suspended-user logout, plus a CI source contract.
- Added a dedicated local lifecycle event table with actor/target references, action, reason, before/after state, and timestamp. User deletion nulls actor/target references rather than deleting the event; a Laravel feature test verifies retention.
- **Still missing:** an append-only database enforcement mechanism, authorized local Admin transition workflow, trusted MFA/re-authentication proof, transactional audit writer, and explicit same-token denial tests for each high-risk incident/assignment route.
- Consequently, a suspended Supabase profile is not evidence that a corresponding local Sanctum user is suspended. Conversely, local Sanctum status cannot be inferred from Supabase profile status.

## Safe implementation order

1. Confirm whether the Laravel Sanctum backend is still a supported production identity path and identify its current Admin authentication/MFA boundary.
2. Add database-enforced append-only protections and a transactional audit writer for the local `users` identity domain.
3. Confirm a trusted local Admin MFA/re-authentication mechanism, then provide a privileged transition path with target restrictions and token/session revocation.
4. Enforce local account state on login and every Sanctum-authenticated request; test same-valid-token denial across profile, incident, assignment, unit, and mutation routes.
5. Only consider cross-provider account linking if an explicit, verified immutable linking flow is designed and approved. Never link by email.

Both local lifecycle migrations are committed to the draft PR but have not been applied to any live database. The audit table preserves event rows when referenced users are deleted, but database-enforced append-only protection and a trusted audit writer are not yet implemented. No local Admin transition UI/API or production account change is included in this slice.
