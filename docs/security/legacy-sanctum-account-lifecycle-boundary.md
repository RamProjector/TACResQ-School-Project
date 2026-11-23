# Legacy Laravel Sanctum account-lifecycle boundary

**Status:** separate identity domain; inventory and regression guard only. This document does not claim that Supabase account suspension affects Laravel Sanctum users.

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
- **Missing:** a local suspension state, an independently authorized local Admin transition workflow, immutable local lifecycle audit, and negative tests proving a suspended local token cannot read or mutate emergency workflows.
- Consequently, a suspended Supabase profile is not evidence that a corresponding local Sanctum user is suspended. Conversely, local Sanctum status cannot be inferred from Supabase profile status.

## Safe implementation order

1. Confirm whether the Laravel Sanctum backend is still a supported production identity path and identify its current Admin authentication/MFA boundary.
2. If retained, design a separate local lifecycle state and append-only audit ledger for the local `users` identity domain.
3. Provide a privileged local Admin transition path with strong re-authentication/MFA, target restrictions, transactional audit, and token/session revocation.
4. Enforce local account state on login and every Sanctum-authenticated request; test same-valid-token denial across profile, incident, assignment, unit, and mutation routes.
5. Only consider cross-provider account linking if an explicit, verified immutable linking flow is designed and approved. Never link by email.

No local lifecycle migration or production account change is included in this inventory-only slice.
