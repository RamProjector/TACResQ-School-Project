# Authentication event collection boundaries

## Decision

Do not treat Laravel's `/login` and `/register` routes as the system-wide authentication audit source. The current Android client and Admin web portal authenticate directly with Supabase Auth. A Laravel-only implementation would therefore produce incomplete coverage while appearing more comprehensive than it is.

This document records the repository-level implementation boundary and a safe sequence for extending authentication-event visibility. It does not change runtime behavior, database schemas, Supabase configuration, or production services.

## Verified client and server paths

| Surface | Current authentication path | Audit implication |
|---|---|---|
| Android | `SupabaseTacApi.login()` calls the Supabase Auth wire client's login operation; `register()` calls `auth/v1/signup`. | These requests go directly to Supabase Auth and do not pass through Laravel's `ApiOperationAuditLogger`. |
| Admin web | `Home.tsx` calls `getSupabase().auth.signInWithPassword()`; the Supabase client persists and refreshes its session. | Sign-in and session lifecycle events are not written by the Laravel audit logger. |
| Laravel adapter | `POST /login`, `POST /register`, `POST /logout`, `POST /logout-all`, and `POST /change-password` are implemented in `backend/src/routes/api.php`. | These routes cover the Laravel/Sanctum adapter only. Their presence does not prove that the active Android or web authentication flow uses them. |
| Laravel Supabase bridge | `SupabaseBridge::getUser()` validates a supplied token with Supabase Auth and records an `auth.get_user` provider operation. | This records bridge validation outcomes, not the original password sign-in, signup, refresh, or direct-client sign-out event. |

## Existing event sources are not interchangeable

- `api_operation_logs` is metadata-only telemetry for selected Laravel-mediated provider and RPC operations. It is not a complete authentication-event ledger.
- `admin_audit_logs` currently records administrator role changes. It is not a general account-security history.
- Supabase Auth's own audit/event facilities are the natural source to investigate for direct-client authentication lifecycle events. Their persistence, event coverage, retention, exportability, and access controls must be verified in the actual project configuration; repository code alone does not establish those live settings.
- Incident status/assignment history and FCM delivery attempts remain operational records, not authentication events.

## Required event coverage

The eventual security-event model should distinguish at least:

1. Authentication success and failure, including the event source and a stable event time.
2. Signup completion and relevant signup rejection outcomes.
3. Session refresh/revocation and sign-out outcomes where the provider exposes them.
4. Password recovery/change events where the provider exposes them.
5. Authorization denials and privileged-operation rejections at the enforcement point.
6. Administrative privilege changes and security configuration changes.

Not every provider exposes every event with the same semantics. Preserve the provider's original event type and source; do not manufacture a success/failure event from an unrelated health probe or infer a user identity from an unverified client payload.

## Safe implementation sequence

### Phase 1 — verify the provider source

Before building a new sink or dashboard, verify in the Supabase project:

- Which Auth events are emitted for password sign-in, signup, token refresh, sign-out, and recovery.
- Whether Auth audit events are persisted, where they are stored, and what retention applies.
- Whether the available event stream covers both Android and browser clients.
- Which project roles can read the records and whether access is auditable.
- Whether event export or a database-side integration is available on the currently approved plan.

Record the verification date and configuration evidence. Do not assume a provider feature is enabled because the project uses Supabase Auth.

### Phase 2 — use a trusted server-side collector

If the provider exposes a supported event hook or persisted event source, ingest it through a server-controlled path. Never put the Supabase service-role/secret key in Android, browser code, runtime-config JSON, or a public build artifact.

Normalize only safe metadata, such as:

- provider and original event type;
- event timestamp and ingestion timestamp;
- outcome, when the provider supplies one;
- provider event/request identifier, when available;
- actor Auth UUID, only when authoritatively supplied by the provider;
- source category (for example, Android, browser, or unknown) only when trustworthy;
- a server-generated correlation identifier when a reliable request correlation exists.

Do not store passwords, access/refresh tokens, authorization headers, full request/response bodies, exact GPS coordinates, incident narratives, chat content, or unnecessary contact details in security-event records.

### Phase 3 — preserve source boundaries

Keep provider authentication events, application authorization/security events, administrator audit events, and emergency operational history as distinct event classes with explicit source labels. A unified read-only investigation view may correlate them, but must not imply that one source contains records it does not collect.

### Phase 4 — cover server-enforced actions

For Laravel-mediated operations, add narrowly scoped events at the actual decision points for successful and rejected login, signup, logout/session revocation, and password change. Reuse the existing fail-open metadata-only logging principles, but define event semantics separately from generic provider-operation telemetry.

For direct Supabase RPC and PostgREST paths, record authorization denials at the trusted database/API enforcement boundary. A client-side error toast or browser callback is not authoritative evidence that a privileged action was denied.

### Phase 5 — validate operational safety

Before enabling any new collector:

- Bound network timeouts and payload size.
- Ensure telemetry failure cannot block SOS creation, assignment, status updates, or other emergency workflows.
- Define retry/duplicate handling without creating unbounded queues.
- Apply least-privilege read access, retention, and tamper-resistance controls.
- Test both success and rejection paths, missing provider configuration, event-source outage, and duplicate delivery.
- Clearly label missing or unavailable event sources as unknown; never present missing data as zero events.

## Explicit non-goals

- No new client-side service key or direct client write access to a privileged event table.
- No claim that the existing Admin Audit Logs page contains all authentication or security events.
- No migration or production configuration change in this documentation commit.
- No replacement of incident status/assignment history or FCM delivery records.
- No assumption that Supabase Platform Audit Logs or any paid-tier feature is available or enabled.

## Repository evidence reviewed

- `app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt`
- `backend/web/src/pages/Home.tsx`
- `backend/web/src/lib/supabase.ts`
- `backend/src/routes/api.php`
- `backend/src/app/SupabaseBridge.php`
- `backend/src/app/ApiOperationAuditLogger.php`
- `docs/API.md`
- `supabase/migrations/20261122050811_api_operation_audit_and_fk_indexes.sql`

This is a source-code review, not verification of the live Supabase project's Auth log configuration or retention settings.
