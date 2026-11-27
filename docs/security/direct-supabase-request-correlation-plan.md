# TACResQ Direct Supabase Request Correlation — Research & Implementation Plan

**Date:** 2026-11-27  
**Status:** Research only; no trace propagation or event schema changes implemented  
**Parent:** PR #370 — Admin IPS / session and security-event correlation  
**Scope:** Android direct Supabase transport, Admin web Supabase client, Laravel bridge, PostgREST event writers, Admin investigation feed

## 1. Finding

The new Admin API-operation feed is useful but not system-wide. It currently covers Laravel-mediated operations recorded by `ApiOperationAuditLogger`. Source inspection confirms that Android release uses direct Retrofit/OkHttp Supabase requests; the Render bridge is opt-in for debug builds and limited to an explicit 13-RPC allowlist. The Admin browser also authenticates directly with Supabase and calls its Admin RPCs directly.

Consequently, direct Android/browser Auth, RPC, and Storage requests do not pass through the Laravel `api_operation_logs` writer. The feed must not imply that missing API-operation rows mean no direct-client activity occurred.

## 2. Verified request paths

### Android

- `SupabaseTacApi.kt` uses a shared Retrofit/OkHttp client with the Supabase publishable key and the user's bearer token.
- Release builds set `USE_RENDER_BRIDGE=false`; debug canary builds may opt in to the Render bridge.
- The bridge allowlist contains 13 functions: `api_active_responder_locations`, `api_all_incidents`, `api_assign_incident`, `api_assign_incident_team`, `api_cancel_my_incident`, `api_create_incident_v4`, `api_me`, `api_my_incidents`, `api_record_location_v2`, `api_set_duty_status`, `api_units`, `api_update_incident_status`, and `api_update_my_incident`.
- Login/signup/logout, release assignment, claim/claimable operations, notifications, FCM, assignment-history reads, incident chat, and Storage upload/download use direct Supabase endpoints in the reviewed implementation. Several allowlisted bridge calls also remain direct in release because the bridge is disabled there.
- The shared OkHttp client currently adds `apikey`, `Accept`, and stored Authorization. It does not add W3C trace headers.

### Admin web

- `Home.tsx` calls `auth.signInWithPassword()`, restores sessions, listens for Auth state changes, and signs out locally through Supabase Auth.
- `AdminMfaGate.tsx` and `AdminSecurityStatus.tsx` call Supabase Auth MFA methods directly: assurance-level reads, factor listing, enrollment, challenge/verification, unenrollment, and global sign-out.
- `backend/web/src/lib/supabase.ts` calls Admin RPCs directly, including account listing, role changes, account lifecycle, session inventory, analytics, investigation events, and dependency health.
- `backend/web/package.json` currently declares `@supabase/supabase-js ^2.119.2`. No explicit `tracePropagation`, tracing runtime import, or direct `@opentelemetry/api` dependency is configured.

### Laravel bridge

- Laravel generates its own request UUID in `RequestCorrelationId` and records it in `api_operation_logs.correlation_id`.
- `SupabaseBridge` captures trusted upstream `sb-request-id` (or `x-request-id` fallback) and stores it separately as `provider_request_id`.
- The bridge currently does not propagate a W3C `traceparent` header to its upstream Supabase requests.
- The operation logger is fail-open and bounded. It skips persistence if the server-side Supabase URL or secret is unavailable; live Render environment-variable presence was not established by the read-only metadata inspection.

## 3. Existing event coverage

| Source | Existing coverage | Auth-session correlation | Trace limitation |
|---|---|---|---|
| `api_operation_logs` | Laravel bridge Auth validation and allowlisted RPC operations | Present when the bridge validated a user JWT and extracted its signed `session_id`; direct Android/browser calls that bypass Laravel are not represented here | Laravel request `correlation_id` and provider request ID remain separate; W3C trace propagation is not implemented |
| `app_action_audit_logs` | Selected triggers for incident message send, notification read, and duty-status change | Present from the signed PostgREST JWT `session_id`; NULL for system/no-user actions | `correlation_id` remains transaction-local; no propagated trace ID |
| `incident_status_logs` | Incident status changes | Stamped from the signed JWT by the trusted BEFORE INSERT trigger; exposed in the unified feed | No trace ID |
| `incident_claimability_logs` | Claimability transitions | Stamped from the signed JWT by the trusted BEFORE INSERT trigger; exposed in the unified feed | No trace ID |
| `incident_request_edit_logs` | Requester edit history | Stamped from the signed JWT by the trusted BEFORE INSERT trigger; exposed in the unified feed | No trace ID |
| `incident_assignment_events` | Assignment/release/team closure | Stamped from the signed JWT by the trusted BEFORE INSERT trigger; exposed in the unified feed | No trace ID |
| `admin_audit_logs` | Role-change audit | Stamped from the signed JWT by the trusted BEFORE INSERT trigger; exposed in the unified feed | No trace ID |
| `account_lifecycle_audit_logs` | Suspension/reactivation; dedicated protected reader plus unified investigation-feed source | Stamped from the signed JWT by the trusted BEFORE INSERT trigger; exposed in the unified feed | Has a row-local `correlation_id`; no trace ID |
| `auth.audit_log_entries` | Provider Auth events when Postgres persistence is enabled | Not exposed by the current feed; do not infer a session ID from undocumented payload fields | Optional persistence; project setting remains unverified |
| `device_security_events` | Private registry table exists and has an Auth-session metadata column and capture trigger | Column/trigger exist, but no trusted writer populates it | No trusted challenge/proof writers; do not treat client events as authoritative |

**Session search coverage is now implemented.** Migration `20261127060000_admin_event_session_context.sql` adds the session column and trusted insert trigger to the domain/Admin event tables. Migration `20261127070000_admin_session_activity_feed.sql` projects those IDs in minimized event details and includes account-lifecycle rows in the protected unified feed. The Admin session-inventory "Find activity" action can now search incident status, claimability, requester-edit, assignment, role-change, and suspension/reactivation events by the same signed Auth session ID, alongside existing app-action and bridge operation events.

The stored `auth_session_id` is a signed JWT claim used as a historical attribution key. It is not a claim that the Auth session row is still present or that the session remains valid now. There is deliberately no foreign key to `auth.sessions`, so historical event attribution survives sign-out/session deletion. Laravel's `local_account_lifecycle_events` remains a separate Sanctum identity domain and is not joined to Supabase Auth sessions.

The existing sources should remain distinct. Do not manufacture cross-source joins from actor ID plus timestamp proximity.

### 3.1 PostgREST event-writer inventory

The current SQL writers were inspected before designing the parser migration:

- `incident_status_logs`: `public.tacresq_log_incident_status()`, triggered by incident insert/status update.
- `incident_claimability_logs`: `public.tacresq_log_claimability_status_transition()` on status progression and direct inserts in `public.api_set_incident_claimable()`.
- `incident_request_edit_logs`: direct insert in `public.api_update_my_incident()`.
- `incident_assignment_events`: `private.tacresq_log_assignment_membership_event()` on assignment insert/delete and `private.tacresq_log_incident_team_closure()` on incident closure.
- `app_action_audit_logs`: `private.tacresq_record_app_action_audit()` triggers for incident-message sends, notification-read changes, and duty-status changes.
- `admin_audit_logs`: direct insert in `public.api_admin_set_user_role()`.
- `account_lifecycle_audit_logs`: direct insert in `public.api_admin_set_account_status()`; this separate immutable ledger is rendered in the Admin lifecycle-history section and is not currently part of the unified investigation-feed union.

These SQL writers execute in the originating database transaction. Migration `20261127060000_admin_event_session_context.sql` attaches a private BEFORE INSERT trigger that stamps the signed Auth `session_id` claim; the writers do not accept a caller-supplied session ID. This is separate from the future W3C trace parser, which still needs to read and validate `traceparent`. The Laravel `api_operation_logs` writer is a separate service-key HTTP write and receives its session ID only from the bridge's verified Auth context; trace propagation through that bridge remains unimplemented. Supabase Auth audit events have a separate provider persistence path, and `device_security_events` still has no trusted event writer. Laravel's `local_account_lifecycle_events` belongs to the separate Sanctum identity domain and must not be assigned a Supabase Auth session or joined as if it shared the PostgREST caller context.

## 4. Supabase request correlation semantics

### 4.1 Provider request ID

Supabase API Gateway returns `sb-request-id`. Current Supabase observability documentation maps this to `log_attributes['request_id']` in Edge/API Gateway logs. It is not available as `request.headers.sb-request-id` in Postgres. It identifies one gateway request and should remain separate from Laravel's correlation ID and W3C trace ID.

### 4.2 W3C trace context

Supabase's current client-side tracing documentation states that clients can send W3C `traceparent`, `tracestate`, and `baggage` headers; Supabase extracts a `trace_id` into API Gateway and Edge Function logs. PostgREST exposes request headers to SQL through:

```sql
current_setting('request.headers', true)::jsonb
```

Header keys are lowercase in PostgREST's JSON representation. A database function can read `traceparent` and extract the trace ID. The W3C trace ID is a 16-byte (32-hex-character) value within the version-00 `traceparent` structure.

### 4.3 Current SDK readiness

- The web dependency version `^2.119.2` is above the documented minimum for Supabase JS trace propagation. However, the feature is opt-in and the repository does not currently configure it. The documented SDK integration also requires the tracing runtime and `@opentelemetry/api`, plus an active W3C trace context.
- Android uses a custom Retrofit/OkHttp transport, not the Supabase Kotlin SDK. A shared OkHttp interceptor is the natural point to inject a W3C `traceparent` header or to integrate an OpenTelemetry propagator.
- A free, no-exporter configuration is possible: the client can generate W3C trace context and let Supabase logs expose the resulting trace ID. Do not add a paid exporter or third-party observability service without approval. Log access and retention still depend on the actual Supabase project plan/configuration and must be checked.

## 5. Identifier and trust boundaries

| Identifier | Authority / meaning | Permitted use |
|---|---|---|
| Event row ID | Database source primary key | Identify one persisted event |
| Auth session ID | Verified JWT `session_id` / Auth session row | Identify a Supabase Auth session |
| Laravel `correlation_id` | Generated by Laravel middleware | Group Laravel request and operation-audit writes |
| Provider `sb-request-id` | Supabase API Gateway response | Join one upstream request to provider logs |
| W3C `trace_id` | Client/server trace context | Trace-only join across gateway and DB event records |

A client can supply or replay a trace ID. Validate its syntax and reject all-zero IDs, but never use it as actor identity, authorization, device proof, account ownership, compromise evidence, or an automatic alert/suspension trigger. Continue deriving actor identity from verified JWT claims and trusted profile mappings.

## 6. Recommended implementation sequence

1. **Prove PostgREST header delivery (local integration proof completed 2026-11-27).** The disposable local Supabase stack now sends a valid `traceparent` through the local API gateway and verifies the exact value from SQL via `current_setting('request.headers', true)`. The test also sends 24 concurrent requests with distinct traceparent values and confirms each request receives its own value alongside its own verified `auth.uid()` and `session_id`. This proves the local gateway/PostgREST path only; it does not prove hosted-project behavior or that Android/web clients currently emit trace context.
2. **Add a private parser — implemented in PR #370 migration `20261127080000`.** The helper parses only version-00 `traceparent`, validates the trace/span IDs, and returns the lowercase 32-hex trace ID or NULL. Missing, malformed, unsupported-version, all-zero, or malformed-header context fails open.
3. **Persist trace IDs on event writers — implemented in PR #370 migration `20261127080000`.** A private BEFORE INSERT trigger stamps nullable trace IDs on the nine trusted event tables already carrying session attribution. It overwrites supplied column values from `request.headers`; no event ID or existing correlation field is repurposed.
4. **Expose bounded Admin search — implemented in PR #370 migration `20261127080000`.** The protected investigation feed exposes trace IDs in minimized details and supports exact 32-hex trace-ID search. The UI labels them as tracing references, not security verdicts.
5. **Propagate from direct clients.** Enable supported W3C propagation for the browser Supabase client and add an OkHttp interceptor for Android. Generate a fresh trace per request or an active trace per logical user action; do not accidentally reuse one static trace ID for the entire app lifetime.
6. **Connect the Laravel bridge — initial propagation implemented in PR #370.** Laravel validates caller-supplied inbound `traceparent` syntax and forwards valid values to Auth, allowlisted RPCs, and the metadata-only API-operation audit sink. This does not generate a server trace root. The trace ID remains separate from `correlation_id`, `auth_session_id`, and `provider_request_id`.
7. **Preserve emergency availability.** All telemetry remains bounded/fail-open. Tracing or audit failures must not block SOS creation, incident assignment, field status progress, or emergency chat.

## 7. Required tests

- Valid version-00 traceparent returns the expected 32-hex trace ID.
- Missing, malformed, unsupported-version, and all-zero values return NULL.
- PostgREST header key casing is handled.
- The disposable local Supabase integration test proves gateway-to-PostgREST delivery of a supplied traceparent and checks 24 concurrent request contexts remain isolated.
- Multiple audit rows emitted by one RPC share the same trace ID.
- Separate requests have separate trace IDs unless deliberately parented to the same trace.
- Trace context cannot change actor attribution, session ownership, or authorization.
- Admin feed search finds the trace without conflating it with provider request ID or Laravel correlation ID.
- No tokens, request/response bodies, IP, user-agent, cookies, exact coordinates, or incident narratives are stored.
- Missing or failed telemetry never changes emergency business-operation outcomes.

## 8. References

- [Supabase Client-side tracing](https://supabase.com/docs/guides/observability/client-side-tracing)
- [Supabase Advanced log filtering and request correlation](https://supabase.com/docs/guides/observability/advanced-log-filtering)
- [Supabase Securing your API — request information](https://supabase.com/docs/guides/api/securing-your-api)
- [PostgREST Transactions and request headers](https://docs.postgrest.org/en/stable/references/transactions.html)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)

No trace propagation, database migration, RLS/grant, Auth setting, credential, Render configuration, or production data was changed by this research.


## 9. Repository implementation checkpoint — 2026-11-27

PR #370 now contains the first database/Admin/Laravel trace-context implementation:
- Private version-00 parser and fail-open event trigger.
- Nullable, indexed 32-hex `trace_id` columns on Admin, account-lifecycle, incident status, claimability, requester-edit, assignment, app-action, device-security, and Laravel API-operation event tables.
- Protected feed projections and exact trace-ID search.
- Laravel validation/propagation to Supabase Auth, RPC, and API-operation audit writes.
- PHP and SQL runtime/static contracts for invalid input, event stamping, search, and propagation.

This remains partial tracing coverage. Android and browser direct Supabase/Storage requests are not configured to generate/propagate W3C context; device-security events still lack a trusted writer; provider-managed Auth audit entries are not trace-stamped. No hosted migration, service deployment, paid exporter, or telemetry vendor was added. The current change does not alter authorization or infer security alerts from trace IDs.
