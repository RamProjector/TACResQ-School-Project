# TACResQ Admin Authority and Workflow Design

**Status:** implementation gate / design decision record  
**Date:** 2026-10-26  
**Scope:** repository design only; no production schema, account, role, or service configuration changed.

## Verified identity split and chosen first implementation boundary

The inspected repository currently contains two separate identity/authorization paths:

1. The dispatcher web client in `backend/web` authenticates with Supabase Auth, reads the Supabase profile through `api_me()`, and gates the board on the `dispatcher` role. Its documented data operations are Supabase RPCs and RLS.
2. The Laravel API in `backend/src/routes/api.php` authenticates its own Eloquent `User` records using Laravel Sanctum. Public registration explicitly sets `role = requester`; the `/me/duty-status` route and incident routes enforce Laravel roles.

These are not interchangeable identities. Adding an `admin` value to one role constraint or building a frontend route alone would not grant secure, coherent administration across both systems. A browser-side role check is only presentation; every privileged operation needs server/database authorization.

The deployed dispatcher console was not an Admin panel at the time of the initial read-only inspection. This PR adds a proposed Supabase-backed Admin role and web console on a separate branch; neither is live until the migration and UI are reviewed, merged, and deployed. The live Supabase profile role constraint was inspected read-only and permits `requester`, `responder`, `driver`, and `dispatcher`, not `admin`. Do not infer that provider-level Render/Supabase ownership equals an in-product TACResQ Admin role.

## Decision gate

The first implementation scope is the Supabase identity path used by the dispatcher web client and the Supabase-backed Android app. This does not silently grant Admin authority to Laravel Sanctum users. If administration later spans both data stores, a trusted identity mapping and authorization boundary must be designed before connecting the systems. Preferred safe direction:

- Keep the existing dispatcher access path and its operational permissions separate.
- Do not silently merge Laravel Sanctum users and Supabase Auth profiles by matching email; email equality is not proof that two records are the same principal.
- If administration spans both data stores, define a trusted identity mapping and authorization boundary first. Every request must be authenticated and authorized against that boundary.
- Do not put a Supabase service-role key or other server secret in the Android app, React bundle, runtime config endpoint, logs, or API responses.
- Do not accept `role`, `is_admin`, or authorization claims from public registration or a client-editable profile field.
- Provision the first administrator only through a trusted, owner-controlled, out-of-band operation. Never ship a default admin password, public bootstrap endpoint, or reusable seed credential.
- Keep production migration application, role assignment, and secret changes out of this PR; require separate explicit approval.

## Admin workflow scope

### 1. Authentication and authorization
- Dedicated Admin entry point; unauthenticated users are redirected to sign-in.
- Backend rejects every admin API request unless the verified principal has the Admin permission.
- Use least-privilege permissions, session expiry/revocation, rate limiting, and generic authorization errors.
- Dispatcher accounts must receive no implicit Admin capabilities.

### 2. User and role management
- Search and view minimal account details; avoid exposing sensitive incident data by default.
- Role changes require server-side validation, confirmation, reason capture, and an audit event.
- Prevent self-promotion and self-granting through profile updates. The current proposed RPC cannot promote, demote, or edit Admin accounts; first-admin provisioning and later Admin membership changes remain trusted-operator operations.
- Define permitted transitions per role instead of accepting arbitrary role strings.
- Deactivation/revocation must invalidate or constrain active sessions/tokens where supported.
- Do not permit account deletion if it would erase required incident/audit history; prefer controlled deactivation.

### 3. Audit trail
- The current proposed audit table records actor profile, target profile, action, old/new role, timestamp, and required reason for successful role changes. Result/correlation IDs and denied-attempt logging remain follow-up requirements.
- Write audit events server-side in the same transaction as the privileged state change where feasible.
- Treat audit records as append-only for ordinary Admin users; do not expose secrets, passwords, bearer tokens, or unnecessary personal data.
- Log denied privileged attempts without recording submitted credentials or tokens.

### 4. Operational overview
- Read-only system health, stale integration indicators, and aggregate incident-processing status.
- Keep dispatch assignment and emergency response actions outside the Admin role unless a separately reviewed operational permission is specified.
- Clearly label stale/unavailable data; never present a cached count as live status.

## Required automated acceptance tests

| Area | Required assertion |
|---|---|
| Public signup | Omitted or forged `role=admin` always creates a requester (or rejects unknown input); no user-supplied role is persisted. |
| Authentication | Anonymous requests cannot read Admin data or call privileged operations. |
| Role boundary | Requester, responder, driver, and dispatcher each receive denial for Admin endpoints unless an explicit least-privilege permission is assigned. |
| Escalation | A normal user cannot self-promote via profile updates, metadata, direct API calls, or direct database writes. |
| Role change | Only authorized Admin operations can grant/revoke permitted roles; invalid transitions are rejected. |
| Last Admin | Concurrent requests cannot remove/demote the last active Admin. |
| Audit | Successful and denied privileged attempts are recorded with actor/action/target/result; secrets are absent. |
| Session revocation | Deactivated users and revoked administrators cannot continue privileged access with an old session/token beyond the documented revocation behavior. |
| Data isolation | Admin reads return only fields needed for the task; ordinary roles cannot query audit data. |
| Web UI | Loading/error/empty states, keyboard navigation, focus management, confirmation for consequential actions, and session-expiry handling are tested. |

## Implementation sequence and remaining work

1. **In this PR:** proposed Supabase role constraint, Admin-only user/audit RPCs, transactional ordinary-role changes, append-only client access to audit rows, web role gate, user search/filter, required reason, and audit view.
2. Validate the migration by replaying every migration against clean PostgreSQL and run the dashboard TypeScript build; automated CI for the latest commits is not yet visible.
3. Add database-level authorization tests for anonymous and all ordinary roles, attempted profile self-promotion, invalid role transitions, audit atomicity, and concurrent assignment/role-change behavior.
4. Add server-side pagination and query filtering for user lists, denied-attempt audit/monitoring, request correlation IDs, and a documented session-revocation policy.
5. Complete accessible focus management and keyboard interaction tests for the role-change dialog.
6. Implement the separate Admin Android experience only after the shared database authorization contract is verified; do not add a second role authority.
7. Review CI findings and request approval before merging. Production migration and initial Admin provisioning remain separately approved operator actions.

## Current boundary

The branch now contains a proposed Admin role/audit migration and a Supabase-RPC-backed web console, but **the feature is not yet validated or production-ready**. Automated CI for the latest commits has not appeared in the repository status checks; clean PostgreSQL replay, frontend build, database authorization matrix, accessibility tests, and live end-to-end verification are still outstanding. The migration is marked pending in the migration-history contract. No Admin account was provisioned, no live migration was applied, and no Render/Supabase production setting was changed.
