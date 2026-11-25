# TACResQ Security-Event Coverage Review

**Review date:** 2026-11-22  
**Branch:** `admin/remove-unimplemented-pages`  
**Scope:** Laravel/Render API, Supabase RPC boundary, Admin privileged actions, incident history, and notification delivery  
**Status:** Source review and documentation only; no event schema, runtime, or production changes

## Executive finding

TACResQ already has several distinct evidence sources, but they are not one complete security-event system:

1. `admin_audit_logs` records administrator role changes.
2. `api_operation_logs` records selected Laravel-mediated provider/Auth/RPC operations as metadata.
3. `incident_status_logs` and `incident_assignment_events` record operational incident lifecycle changes.
4. `fcm_delivery_attempts` records notification delivery attempts and outcomes.
5. Supabase Auth/platform logs are separate sources for direct client authentication and platform activity.

The main coverage gap is that Android and the dispatcher/Admin web clients call Supabase directly for many operations. Those requests do not pass through Laravel's `ApiOperationAuditLogger`. In addition, the Laravel login/register/password-change routes currently enforce rate limits and credential checks but do not write explicit authentication security events to `api_operation_logs`. The Admin-specific audit history is not a general record of Admin page access, MFA failures, denied privileged operations, or security configuration changes.

This is an observability and accountability gap; it is not, by itself, proof that the authorization checks are absent. The repository has server-side role checks, RLS/RPC boundaries, and an AAL2/TOTP wrapper for Admin RPCs. The missing part is consistent, attributable evidence when those controls succeed, reject a request, or fail.

## Existing evidence sources and their actual scope

| Source | What it records | Important boundary |
|---|---|---|
| `admin_audit_logs` | Successful application role changes, actor/target profile IDs, old/new role, reason, timestamp | Does not cover all Admin actions, failed role-change attempts, login/MFA events, or operational incident history |
| `api_operation_logs` | Metadata for selected Render-mediated Supabase Auth/RPC and routing-provider operations | Laravel-only coverage; direct Android/dashboard Supabase calls bypass this writer; writer is skipped if server secret is absent |
| `incident_status_logs` | Incident status creation and transitions | Operational history, not a general authentication/authorization security log |
| `incident_assignment_events` | Assignment, release, and incident-closure membership events | Operational team history; read access is role-scoped to dispatcher/admin |
| `fcm_delivery_attempts` | FCM send attempts and provider outcomes | Delivery telemetry, not proof of device receipt or display |
| Supabase Auth/platform logs | Authentication and platform-level activity, subject to project configuration and provider retention | Separate source; repository does not prove current live retention, export, or alert configuration |

## Coverage findings

### 1. Laravel authentication endpoints

In `backend/src/routes/api.php`:

- `POST /login` applies per-IP and per-account/source rate limits and returns a generic credential failure. It does not explicitly record login success, invalid credentials, or rate-limit rejection as security events.
- `POST /register` applies a registration IP limit and creates requester accounts. It does not emit a structured registration security event.
- `POST /logout` revokes the current Sanctum token; `POST /logout-all` revokes all tokens for the account. These state changes do not currently write a dedicated security event.
- `POST /change-password` verifies the current password, enforces a new-password policy, revokes prior tokens, and issues a fresh token. Failed current-password attempts and successful credential changes are not explicitly represented in the security-event stream.
- Rate-limit outcomes return HTTP 429 but are not currently written to the application operation-audit table.

Do not log passwords, password hashes, access tokens, raw authorization headers, or full request bodies. Use a normalized account reference only where the account is known; for unknown login identifiers, use a keyed one-way fingerprint if correlation is operationally necessary, and define key rotation and retention.

### 2. Supabase bridge

`SupabaseBridge` already records metadata for:

- Supabase `auth.get_user` validation outcomes
- Allowlisted RPC outcomes
- Rejected unallowlisted RPC names (normalized/validated before recording)
- Upstream timeout/failure outcomes

The bridge uses the caller's own JWT for the business RPC; a separate server-only key writes metadata. This is a useful separation. However, the bridge does not represent every request arriving at the API boundary: missing bearer tokens and invalid request-body/allowlist checks may be rejected before the bridge logger is invoked. Do not assume every 401/403/404/422/429 response is represented by `api_operation_logs`.

### 3. Direct Supabase clients and Admin controls

The Android app and dispatcher/Admin web application make direct Supabase Auth/RPC calls. These do not traverse Laravel's audit writer. The Admin RPC wrappers enforce an Admin profile plus AAL2 and TOTP, but a rejected call at that boundary is not automatically an application `admin_audit_logs` event. Supabase Auth/platform logs may provide related evidence, but their current live configuration and retention were not verified in this review.

The current Admin audit page should continue to be described as **role-change history**, not a complete Admin activity or security-event history.

### 4. Operational events must remain distinct

Incident status and assignment histories answer operational questions: what happened to an emergency request and its response team. They should not be overloaded with login failures, MFA denials, privilege changes, or suspicious API behavior. Likewise, notification delivery attempts should not be treated as security audit events.

A future investigation interface may correlate these sources by timestamp, actor reference, incident reference, and correlation ID, but should retain each source's purpose, access policy, and retention requirements.

### 5. Audit-sink protection and availability

The `api_operation_logs` table has RLS enabled and no direct client grants; the service role is granted SELECT/INSERT. The Laravel writer is metadata-only and fail-open, with short HTTP timeouts. It silently skips writes when the server secret is not configured. This protects emergency API availability, but it means missing configuration or sink outages can create invisible gaps unless sink health is independently monitored.

The current migration does not establish an automatic retention policy, archive process, reader workflow, or tamper-detection mechanism. These are operational controls to decide before treating the table as a durable compliance record.

## Recommended implementation order

### P0 — define the event contract and coverage map

Use separate event classes for:
- Authentication: login success/failure, registration, logout, logout-all, password change, token/session revocation, MFA enrollment/verification/recovery outcomes.
- Authorization: denied role/permission checks, rejected Admin RPC calls, access to privileged functions without the required assurance level.
- Privileged administration: role changes, Admin provisioning/demotion performed out of band, security-setting changes, sensitive account-detail reveals, and data exports.
- Operational/API telemetry: provider availability, routing, notification attempts, and ordinary business workflow events.

Each security event should include, where known: server timestamp, event type, outcome, actor reference (or explicit anonymous/unknown), target reference, source/application, request correlation ID, safe reason/error code, and severity. Do not force every event into a single table if its trust boundary or access policy differs.

### P0 — cover authentication and authorization failures at their source

Instrument Laravel login, registration, password change, and token revocation outcomes. For Supabase Auth and direct RPC calls, use provider-supported audit sources or a server-mediated path only after verifying the supported event hooks and the effects on existing Android/web clients. Do not trust a client-reported event as proof that a privileged operation happened.

### P1 — record high-impact Admin activity and denials

Keep successful role changes in the append-only Admin audit. Add separate security events for rejected privileged actions, assurance failures, sensitive-data reveals, exports, and out-of-band Admin account changes. Preserve the current restriction that ordinary client APIs cannot promote or demote Admin accounts.

### P1 — make missing telemetry visible

Expose a safe operational indicator for last successful audit write, recent write failures, and configuration absence. Because the writer is fail-open, alerting must not depend solely on successfully writing a failure event to the same unavailable sink. Use the hosting platform's independent application logs/metrics for sink outages.

### P1 — protect and govern records

Define authorized readers, retention, archive/backup, export approval, deletion, and periodic access review. Keep logs append-only for application roles; prevent direct client writes. Record access to sensitive logs and exports. Do not expose raw audit tables to ordinary authenticated users.

### P2 — correlate without creating a surveillance payload

Propagate server-generated correlation IDs across server-mediated operations. For direct clients, use provider-supported request identifiers where available; do not treat client-supplied IDs as trusted actor identity. Avoid recording GPS coordinates, incident narratives, message bodies, phone/email values, passwords, tokens, session identifiers, or raw headers.

## Event coverage matrix

| Event | Current repository evidence | Recommended state |
|---|---|---|
| Laravel login success/failure | Rate limiter and generic response; no dedicated event found in route | Add structured security event |
| Registration success/rejection | Rate limiter and account creation; no dedicated event found in route | Add structured security event |
| Password change success/failure | Credential checks and token rotation; no dedicated event found in route | Add structured security event |
| Logout/current-token revocation | Token deletion; no dedicated event found in route | Add structured security event |
| Logout-all/session revocation | All Sanctum tokens deleted; no dedicated event found in route | Add structured security event |
| Supabase token validation through Laravel | `api_operation_logs` | Existing partial coverage |
| Allowlisted RPC through Laravel | `api_operation_logs` | Existing partial coverage |
| Rejected bridge RPC | Logged inside bridge if bridge method is reached; earlier route rejections may not be | Close early-rejection gaps |
| Direct Android/dashboard Supabase operations | Bypass Laravel writer | Verify provider-side coverage or design trusted server mediation |
| Admin role change | `admin_audit_logs` | Existing successful-action coverage |
| Admin MFA/assurance denial | RPC rejects without a corresponding application Admin event | Add trusted security-event coverage |
| Incident status/assignment | Dedicated operational histories | Keep separate |
| FCM send attempts | `fcm_delivery_attempts` | Keep as delivery telemetry |

## Do not implement blindly

- Do not write authentication secrets, raw tokens, password values, or full request/response payloads to logs.
- Do not make logging synchronous and unbounded on SOS, assignment, or status-transition paths.
- Do not make emergency operations fail because the audit sink is temporarily unavailable.
- Do not claim direct Supabase calls are covered by Laravel telemetry.
- Do not create an Admin UI that labels these sources as one complete audit trail until source coverage, retention, and access permissions are verified.
- Do not enable paid observability services or change the Supabase plan as part of this work.
- Do not apply migrations or change live provider settings as part of a documentation review.

## Repository evidence reviewed

- `backend/src/routes/api.php`: Laravel login/register/logout/logout-all/password-change routes, rate limits, and credential/session behavior.
- `backend/src/app/SupabaseBridge.php`: existing metadata logging for Auth token validation, RPC outcomes, rejected bridge RPCs, and timeouts.
- `backend/src/app/ApiOperationAuditLogger.php`: metadata-only server-side writer, fail-open behavior, bounded timeouts, and missing-secret skip.
- `supabase/migrations/20261122050811_api_operation_audit_and_fk_indexes.sql`: `api_operation_logs` schema and service-role-only table grants.
- `supabase/migrations/20261112215653_admin_role_audit_foundation.sql`: successful role-change audit event model.
- `supabase/migrations/20261122050858_require_totp_for_admin_rpcs.sql`: Admin AAL2/TOTP assurance wrapper.
- `supabase/migrations/20261022040223_fix_incident_status_audit_trigger.sql`: operational incident status history.
- `supabase/migrations/20261122050834_incident_assignment_event_history.sql`: operational assignment lifecycle history.
- `supabase/functions/tacresq-notify/index.ts`: FCM send authorization and provider failure handling.

This is a source-based review of the repository at the stated commit. It does not establish the live Supabase migration state, Auth audit retention, Render secret configuration, or production event delivery.

## External guidance

- OWASP Logging Cheat Sheet: application-level security events should include authentication successes/failures, authorization failures, session-management failures, and privileged administrative actions; logs should be structured, access-controlled, protected from tampering, and exclude credentials and unnecessary sensitive data.
- OWASP Authorization Cheat Sheet: authorization-related logging supports detection and investigation, but the event stream should be designed with the appropriate level of detail and data minimization.
