# TACResQ Admin Authority and Workflow Design

**Status:** implementation gate / design decision record  
**Date:** 2026-10-26  
**Scope:** repository design only; no production schema, account, role, or service configuration changed.

## Why this must precede an Admin UI

The inspected repository currently contains two separate identity/authorization paths:

1. The dispatcher web client in `backend/web` authenticates with Supabase Auth, reads the Supabase profile through `api_me()`, and gates the board on the `dispatcher` role. Its documented data operations are Supabase RPCs and RLS.
2. The Laravel API in `backend/src/routes/api.php` authenticates its own Eloquent `User` records using Laravel Sanctum. Public registration explicitly sets `role = requester`; the `/me/duty-status` route and incident routes enforce Laravel roles.

These are not interchangeable identities. Adding an `admin` value to one role constraint or building a frontend route alone would not grant secure, coherent administration across both systems. A browser-side role check is only presentation; every privileged operation needs server/database authorization.

The current deployed dispatcher console is not an Admin panel. The live Supabase profile role constraint was inspected read-only and currently permits `requester`, `responder`, `driver`, and `dispatcher`, not `admin`. Do not infer that provider-level Render/Supabase ownership equals an in-product TACResQ Admin role.

## Decision gate

Before adding the first privileged endpoint or migration, explicitly establish which identity system is authoritative for in-product administration. Preferred safe direction:

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
- Prevent self-promotion, self-granting through profile updates, and accidental removal/demotion of the last active administrator.
- Define permitted transitions per role instead of accepting arbitrary role strings.
- Deactivation/revocation must invalidate or constrain active sessions/tokens where supported.
- Do not permit account deletion if it would erase required incident/audit history; prefer controlled deactivation.

### 3. Audit trail
- Record actor principal, action, target principal, timestamp, result, reason, and correlation/request ID.
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

## Implementation sequence

1. Confirm the authoritative identity system and document the trust boundary.
2. Add schema/permission migration and server-side authorization checks with rollback strategy; write escalation tests before exposing a UI.
3. Add audit persistence and tests for atomicity and access isolation.
4. Add Admin API endpoints with request validation, pagination, rate limits, and non-sensitive error handling.
5. Build a distinct Admin web experience, with accessible user/role management and audit views.
6. Run backend tests, frontend typecheck/build, migration replay, role-matrix tests, and an end-to-end demo against disposable test data.
7. Review the diff and CI findings; only then request approval for merging. Production migration and initial Admin provisioning remain a separately approved operation.

## Current boundary

This document is not a claim that Admin is implemented. No Admin role, account, route, credentials, or production database change has been created by this design step. The purpose is to prevent a misleading frontend-only implementation and to make the next code changes testable and secure.
