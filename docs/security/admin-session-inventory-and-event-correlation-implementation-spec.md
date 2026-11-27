# TACResQ Admin Session Inventory & Security Event Correlation — Implementation Specification

**Date:** 2026-11-27  
**Status:** Session inventory, Laravel provider-request correlation capture, and Admin API-operation feed integration implemented on PR #370; exact-head automated checks passed; manual Admin UI acceptance and direct-client/device event ingestion remain pending  
**Branch:** `docs/admin-ips-ui-master-plan`  
**Parent PR:** #370  
**Scope:** Admin web, Supabase Auth/Postgres, trusted event ingestion, Render bridge where applicable  
**Safety:** This specification does not apply migrations, change Auth settings, write test data, change RLS/grants, or deploy services.

## 1. Problem statement

The current Admin Security workspace is not a session inventory or a security-alert engine. Its current-session MFA status is working against Supabase Auth's current browser session, while the global sign-out action revokes refreshable sessions for the current account. It does not list sessions belonging to other users, associate sessions with trusted devices, or correlate all security event sources.

The investigation RPC `public.api_admin_list_investigation_events` enforces `private.tacresq_require_admin_mfa()` but its source union does not include `auth.sessions`, `public.device_sessions`, `public.device_security_events`, or `public.api_operation_logs`. The current app-action audit `correlation_id` is generated per row and is not propagated from the request.

## 2. Verified current state

### 2.1 Live schema and ACL snapshot

Read-only inspection of the connected TACResQ Supabase project on 2026-11-27 returned:

| Relation | Rows | RLS | anon SELECT | authenticated SELECT | service_role SELECT | postgres SELECT |
|---|---:|---|---|---|---|---|
| `auth.sessions` | 15 | enabled | no | no | no | yes |
| `auth.audit_log_entries` | 0 | enabled | no | no | no | yes |
| `public.user_devices` | 0 | enabled | no | no | yes | yes |
| `public.device_sessions` | 0 | enabled | no | no | yes | yes |
| `public.device_challenges` | 0 | enabled | no | no | yes | yes |
| `public.device_security_events` | 0 | enabled | no | no | yes | yes |
| `public.device_integrity_checks` | 0 | enabled | no | no | yes | yes |
| `public.api_operation_logs` | 0 | enabled | no | no | yes | yes |
| `public.app_action_audit_logs` | 0 | enabled | no | no | no | yes |

The zero-row count for `device_challenges` was also confirmed in this schema snapshot. The device tables have no direct client SELECT privilege; do not add client grants to make the Admin UI work. The provider-owned Auth relations are not exposed through the generated Data API. Keep privileged reads behind narrow server/database functions.

### 2.2 Auth session schema

The connected `auth.sessions` relation exposes these columns: `id`, `user_id`, `created_at`, `updated_at`, `factor_id`, `aal`, `not_after`, `refreshed_at`, `user_agent`, `ip`, `tag`, `oauth_client_id`, `refresh_token_hmac_key`, `refresh_token_counter`, and `scopes`.

The table contains sensitive session metadata and refresh-token-related internal fields. The Admin projection must use an explicit allowlist. It must never return `refresh_token_hmac_key`, `refresh_token_counter`, access/refresh tokens, raw authorization headers, or full unfiltered Auth rows. Default display should not include raw IP or full user-agent values.

### 2.3 Authorization helper

The checked-in `20261125020826_account_lifecycle_admin_mfa_guard.sql` definition of `private.tacresq_require_admin_mfa()` verifies:
- authenticated subject exists;
- JWT assurance level is AAL2;
- JWT authentication methods include TOTP;
- the current profile maps to the subject, has role `admin`, and has `account_status = 'active'`;
- a share lock is held on the active Admin profile during the protected transaction.

It revokes direct execution of the private helper from PUBLIC, anon, and authenticated. New Admin session/investigation readers should call this helper inside their own SECURITY DEFINER wrapper, with pinned search_path and explicit grants. Do not rely on React route visibility or a client-side MFA check.

### 2.4 Existing UI and device foundation

`AdminSecurityStatus.tsx` calls `auth.mfa.getAuthenticatorAssuranceLevel()` and `auth.mfa.listFactors()` for the current browser session/account. Its `signOut({ scope: "global" })` action is current-account-wide sign-out, not a selective Admin session-revocation endpoint. The component's device-security inventory copy explicitly states that no trusted server-side writer currently feeds device-security events into the control center.

The private device registry foundation already exists: `user_devices`, `device_sessions`, `device_challenges`, `device_security_events`, and `device_integrity_checks`. Its migration intentionally leaves direct client access revoked. The device-security plan explicitly gates client/admin use until challenge verification, atomic consumption, session binding, server-side enforcement, and negative/concurrency tests exist. Do not connect empty registry tables to the UI as if they contain trustworthy device telemetry.

## 3. Supabase Auth semantics that affect the design

The current Supabase User Sessions documentation states:
- every access-token JWT contains a `session_id` UUID corresponding to `auth.sessions.id`;
- signing out removes the affected session rows;
- a session row may remain after configured maximum-lifetime or inactivity expiry because those conditions are checked on refresh rather than by proactive row deletion;
- a missing session row is evidence that the session was signed out;
- an existing row alone is **not** proof that the session is currently valid.

Therefore the UI must not label every row returned from `auth.sessions` as "Active". Use precise labels such as **Session record present** and **Expiry/refresh policy not evaluated** until the project’s actual Auth timeout settings and their semantics are verified. A future protected operation may validate the current JWT's `session_id` against the session table at sensitive-operation boundaries; do not impose a database lookup on every low-risk request without an explicit reliability/latency decision.

### 3.1 Revocation limits

Supabase documents:
- browser/client `signOut({ scope: 'global' })` revokes refreshable sessions for that user;
- `scope: 'local'` revokes the current session, and `scope: 'others'` revokes other sessions while retaining the current one;
- already-issued access-token JWTs remain valid until expiry;
- the server-side Admin sign-out API accepts a **valid target user's JWT**, not merely a user ID or a session-row ID.

The current session table does not store a target user's access JWT. Consequently, an Admin cannot selectively revoke an arbitrary row merely by knowing its `session_id`. Do not delete rows from `auth.sessions` directly, do not attempt to reconstruct a JWT, and do not silently turn a selected-session action into a global account sign-out. The first session-inventory release should be read-only. Any future selective-revocation design needs a documented supported Auth API path that securely obtains a valid target-session JWT or a different provider-supported revocation primitive, plus clear user confirmation and audit evidence.

## 4. Target architecture

### 4.1 Protected read-only session inventory

Add a narrow SQL function, tentatively `public.api_admin_list_auth_sessions_page(p_target_profile_id, p_limit, p_offset)`, only after clean-replay contract design. The final name/signature must follow repository conventions and the migration history.

Required behavior:
1. Call `private.tacresq_require_admin_mfa()` before reading any target data.
2. Validate positive target profile ID, bounded page size (maximum 50 or 100), bounded offset/cursor, and reject unsupported filters.
3. Resolve the target's Auth UUID through the canonical `profiles.auth_user_id` mapping; never accept a caller-supplied Auth UUID as the ownership authority.
4. Read only the allowlisted `auth.sessions` fields needed for investigation: session ID (prefer a stable HMAC-derived display reference or partially masked value), created/updated/refreshed timestamps, AAL, and carefully normalized provider/client metadata if approved.
5. Omit IP and full user-agent by default. If a later privacy review approves them, expose only masked/normalized data, purpose-limit access, and log privileged reveal separately.
6. Never return refresh-token HMAC material, token counters, scopes unless specifically justified, OAuth secrets, JWTs, or raw Auth records.
7. Use deterministic keyset pagination (`created_at`, `id`) with a hard result cap and stable ordering. Avoid large offsets over unbounded session history.
8. Return explicit source metadata: `source = 'supabase_auth_sessions'`, `observed_at`, `source_status`, and coverage/limitations. A failed or inaccessible source must be distinguishable from a genuinely empty result.
9. Label rows as **record present** rather than **active** unless current Auth timeout policy is evaluated and the status is calculated correctly.
10. Return no device identity unless a verified active row in `device_sessions` joins to a verified, non-revoked `user_devices` record for the same profile/session. Otherwise show **Device not verified / not associated**, not "unknown device is suspicious".
11. Do not join profile identities by email, IP, user-agent, or approximate timestamps.

A SECURITY DEFINER function is acceptable only if its owner, grants, search_path, current MFA helper, profile mapping, and clean-replay authorization contract are verified. Do not grant direct SELECT on `auth.sessions` or device tables to anon/authenticated.

### 4.2 Device association and trusted event ingestion

Do not show device sessions until a real writer is implemented. Follow the existing device-security plan:
- verify Supabase JWT signature/issuer/audience/expiry on the trusted boundary;
- obtain subject and `session_id` from verified request context, not client-supplied identifiers;
- issue purpose-bound, short-lived random challenge; persist only a hash;
- verify proof of possession using the registered public key;
- atomically consume the challenge and persist device/session association plus event in one transaction;
- enforce session/device revocation on protected server boundaries;
- keep Play Integrity verdict verification server-side;
- keep requester emergency submission available from a new/unverified device.

The existing Laravel bridge's `getUser()` verifies a token by calling Auth `/auth/v1/user` but does not currently return verified `session_id` claims. Its Sanctum token domain is separate from Supabase Auth sessions. Android and Admin direct Supabase calls do not automatically traverse Laravel middleware. Do not implement a Laravel-only verifier and claim it protects the direct Supabase RPC paths.

### 4.3 Correlation model

Keep these identifiers distinct and preserve their provenance:

| Field | Source | Scope |
|---|---|---|
| `auth_request_id` | Supabase Auth provider log metadata | One Auth HTTP request; can group related provider Auth audit entries |
| `auth_session_id` | Verified JWT `session_id` / Auth session row | One Supabase Auth session |
| `laravel_request_correlation_id` | Server-generated `RequestCorrelationId` middleware | One Laravel-mediated HTTP request and its operation-audit writes |
| `provider_request_id` | Provider response/log metadata when actually returned | Provider-defined request scope; not assumed equal to Laravel or Auth IDs |
| `device_id` | Trusted device registry after challenge proof | One registered installation |
| `event_id` | Source table primary key | One persisted event row |
| `trace_id` | W3C trace context only after verified end-to-end instrumentation | Distributed trace; must be validated at every propagation boundary |

Do not overload the existing `app_action_audit_logs.correlation_id` column. Its current `gen_random_uuid()` default is a row-local identifier, not a propagated request ID. Do not join events solely by user ID and time proximity. Do not accept arbitrary client-supplied request IDs as trusted cross-source links.

For Laravel bridge operations, preserve the middleware-generated correlation ID through each audit write and store the actual provider request ID only when the provider returns one. The bridge now reads `sb-request-id` from Supabase Auth/PostgREST responses, falling back to `x-request-id` for self-hosted/reverse-proxy deployments; it strips control characters and bounds the value before persistence. It does not accept a client-supplied provider ID. A feature test asserts both header paths and that Auth + RPC audit rows retain the same server-generated Laravel correlation ID. For direct Supabase actions, use a provider-supported request/trace identifier only after verifying what the installed SDK and PostgREST/Auth gateway actually expose to the database/function. If no trusted identifier is available, record the event as uncorrelated rather than inventing a link.

### 4.4 Investigation feed and alerts

Keep source-specific feeds distinguishable and paginated. `api_operation_logs` is now integrated for Laravel-mediated bridge/worker traffic through a protected Admin feed source and category. This is not complete cross-platform coverage: direct Android/browser Supabase requests bypass the Laravel bridge. Keep `device_security_events` excluded from trusted alerting until challenge-verification writers and source contracts exist. Preserve source timestamp, event ID, actor mapping, outcome, and correlation provenance. Add explicit source coverage/freshness status; a zero-row source is **no events recorded in this source**, not **no suspicious activity**.

Alerts should be a derived, deduplicated, reviewable layer with:
- versioned detection rule ID and version;
- evidence event IDs and source names;
- bounded time window and threshold;
- severity and rationale;
- first/last observed timestamps;
- deduplication key and lifecycle (open, acknowledged, resolved, false positive);
- human actor and reason for state changes.

No automatic account suspension, session revocation, incident denial, or emergency-workflow blocking from heuristic alert rules. A shared IP/network, user-agent similarity, one failed login, or missing device proof is not sufficient evidence of compromise.

## 5. Recommended delivery order

| Stage | Deliverable | Gate |
|---|---|---|
| 0 | Verify Auth Audit Logs Postgres persistence setting and current Auth session timeout settings in Dashboard | Owner confirmation; no settings changed by code PR |
| 1 | Session-list SQL contract and tests on clean PostgreSQL replay | Admin + active-account + AAL2 + TOTP, no client grants, privacy allowlist |
| 2 | Protected Admin session inventory read-only UI | Honest source/freshness/status labels; pagination and empty/error distinction |
| 3 | Trusted device challenge/proof verifier | Atomic challenge consume, replay/concurrency/forgery tests |
| 4 | Device/session binding and protected-operation enforcement | Same verified subject/session, fail-closed for sensitive paths, emergency request exception |
| 5 | Source-specific event ingestion and correlation | Provider request IDs and Laravel request IDs kept separate; no fabricated links |
| 6 | Alert rules and review lifecycle | Rule tests, deduplication, false-positive review, no automatic lockout |
| 7 | Optional selective revocation | Supported provider API path proven; never delete Auth rows or substitute global sign-out |

## 6. Required regression and security tests

### Session inventory
- non-Admin denied;
- suspended Admin denied even with otherwise valid AAL2/TOTP claims;
- Admin AAL1 denied;
- Admin AAL2 without TOTP denied;
- missing target profile/Auth mapping denied or returns a safe not-found response without revealing account existence;
- pagination cap and invalid cursor/limit rejected;
- only allowlisted columns returned;
- no raw IP/user-agent/token/HMAC/counter/refresh secrets in JSON or logs;
- record-present row is not labelled active when timeout policy is unknown;
- signed-out session absent from the table is represented as absent, not silently cached;
- provider/source outage is distinguishable from an empty page;
- deterministic pagination under concurrent session refresh/creation.

### Device registry and correlation
- direct anon/authenticated table reads remain denied;
- unverified, wrong-account, wrong-session, revoked, expired, replayed, and concurrently consumed challenges fail;
- exactly one concurrent valid challenge consumption succeeds;
- failed event insert rolls back the association;
- device/session association is written only from verified subject and session claims;
- Laravel request ID is generated server-side and propagated to all related bridge audit writes;
- provider request ID remains a separate field;
- no client-provided correlation header can create a trusted cross-source link;
- uncorrelated sources are displayed as uncorrelated, not falsely merged.

### Alerts and operations
- duplicate evidence events do not create duplicate alerts;
- alert evidence is inspectable and source-linked;
- empty, delayed, unavailable, and partially populated sources have separate states;
- false-positive acknowledgement and resolution are audited;
- no heuristic rule automatically suspends users, revokes sessions, or blocks emergency requests.

## 7. External references

- Supabase, [User sessions](https://supabase.com/docs/guides/auth/sessions) — JWT `session_id`, session rows, timeout behavior, and refresh semantics.
- Supabase, [Signing out](https://supabase.com/docs/guides/auth/signout) — local/global/other scopes and access-token expiry after revocation.
- Supabase, [JavaScript Admin signOut](https://supabase.com/docs/reference/javascript/auth-admin-signout) — server-side target-user JWT requirement.
- Supabase, [Auth Audit Logs](https://supabase.com/docs/guides/auth/audit-logs) — provider audit storage and database persistence configuration.
- Supabase, [Admin API](https://supabase.com/docs/reference/javascript/admin-api) — server-only secret-key boundary.
- OWASP, [Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) — interaction identifiers, event integrity, and sensitive-data exclusion.

## 8. Current disposition

The session inventory is implemented on the PR branch: a protected, read-only, keyset-paginated session-list RPC; a validated typed client helper; and an account-detail session panel with explicit record-present / validity-not-evaluated semantics. The RPC uses the existing active-Admin + AAL2 + TOTP helper, resolves Auth ownership from the target profile, and exposes no IP, user-agent, token, token-HMAC, or refresh-token metadata. It does not revoke sessions or assert that a row is currently valid.

Laravel bridge correlation capture is implemented. It records the provider's `sb-request-id` response header (with `x-request-id` fallback when the primary header is absent or blank), keeps that value distinct from the middleware-generated `correlation_id`, and has a feature test for both header paths. The Admin investigation RPC now consumes `api_operation_logs` through the forward migration. It exposes a separate `operations` category and `api_operation_logs` source, resolves actors only through `profiles.auth_user_id`, and projects only minimized operation metadata. Admins can search a shared Laravel correlation ID to retrieve related Auth and RPC events; provider request IDs remain separate per upstream call.

Exact-head validation completed successfully on `13f0d7636d9dad59b35c9d2864e5b6a16f2fae4b`: TACResQ CI (including PostgreSQL 16 clean migration replay, Android debug/release builds, source checks, and Laravel/dashboard integration), PostgreSQL 17 RPC grant contract, Security (repository guardrails and CodeQL Android/Web/Actions), QA (local-stack stress and prototype capture), Frontend Review, and read-only profile verification. The clean-replay Admin API-operation runtime test and Laravel bridge header-capture feature test passed after correcting the synthetic Auth fixture and blank-primary-header fallback behavior.

The migrations remain unapplied to hosted Supabase. Manual Admin UI acceptance has not been performed; prototype capture is not manual acceptance. The external Auth audit source remains provider-log based and may not be persisted to `auth.audit_log_entries` unless its optional database persistence setting is enabled. Direct Android/browser Supabase requests still bypass the Laravel bridge and remain outside this telemetry source. Device events remain untrusted/empty until trusted challenge-verification writers are implemented. The next research/implementation gate is to map direct Android/browser privileged event paths and design trusted event writers, while keeping provider IDs, Auth session IDs, Laravel correlation IDs, and device IDs distinct. Do not add selective revocation or heuristic automatic suspension.


## 9. Direct-client request correlation — follow-up research (2026-11-27)

The Laravel operation feed is not a system-wide request ledger. Current source review confirms:

- Android release uses the custom Retrofit/OkHttp Supabase transport directly. The Render bridge is an opt-in debug-build path and is limited to the explicit `bridge_rpc_allowlist`; login/signup/logout and several sensitive RPC, notification, FCM, chat, assignment-history, and Storage operations remain direct.
- The Admin web portal signs in, manages MFA, reads account/session data, changes account state/roles, loads analytics, and reads investigation events directly through `@supabase/supabase-js`. It does not route these operations through the Laravel bridge.
- The existing `api_operation_logs` writer is in Laravel's `ApiOperationAuditLogger`; therefore it does not record those direct client requests.
- Existing database-backed event sources cover specific successful actions (role/lifecycle, incident status, claimability, requester edits, assignments, and selected app actions). They do not share a trusted request trace field. The `app_action_audit_logs.correlation_id` default is a row-local UUID, not a propagated request identifier.

### 9.1 Verified provider trace mechanism

Current Supabase documentation describes two different identifiers:

1. `sb-request-id` is returned by the API Gateway. Supabase maps it to `log_attributes['request_id']` in Edge/API Gateway logs; it is not exposed as `request.headers.sb-request-id` to Postgres.
2. W3C `traceparent` can be propagated by clients. Supabase API Gateway and Edge Function logs extract a `trace_id`; PostgREST exposes request headers to SQL through `current_setting('request.headers', true)::jsonb`, with lowercase header names. A database function can therefore read the incoming `traceparent` header and extract its trace ID.

The current Admin web dependency is `@supabase/supabase-js ^2.119.2`, which is new enough for the documented trace-propagation feature. However, the repository does not currently configure `tracePropagation`, import the SDK tracing runtime, or declare `@opentelemetry/api` directly. Supabase's built-in integration is opt-in and requires an active W3C trace context. The Android app uses a custom Retrofit/OkHttp client rather than the Supabase Kotlin SDK; no traceparent propagation is present in its shared Supabase interceptor.

### 9.2 Identifier rules

Keep these values separate:

- `event_id`: identity of one persisted event row.
- `auth_session_id`: Supabase Auth session UUID from a verified JWT/session record.
- `correlation_id`: server-generated Laravel request UUID; trusted within the Laravel request/audit path.
- `provider_request_id`: provider response ID such as `sb-request-id`; one upstream request.
- `trace_id`: W3C trace context shared by requests in one distributed trace. It is a tracing hint, not an identity or authorization claim.

A client may choose or replay a trace ID. Validate W3C syntax and reject all-zero identifiers, but never use trace context to identify the actor, authorize an operation, establish device ownership, prove compromise, or trigger automatic suspension/revocation. Actor identity must continue to come from verified Supabase JWT claims and server/database profile mapping.

### 9.3 Recommended next implementation slice

1. Add a narrow private Postgres helper that safely reads `request.headers.traceparent`, validates the W3C version-00 shape, and returns only the 32-hex trace ID or NULL. It must tolerate a missing request context and must not read/store IP, user-agent, cookies, baggage, or arbitrary headers.
2. Add nullable `trace_id` metadata to the event sources that are actually written during PostgREST requests. Populate it only inside trusted SQL functions/triggers from the helper. Preserve existing event IDs and row-local `correlation_id` semantics.
3. Extend the protected Admin investigation feed with the trace ID as minimized metadata and support exact trace-ID search. Label it as request tracing, not a security verdict.
4. Add direct-client propagation: browser Supabase client via supported W3C trace propagation or a carefully scoped custom fetch wrapper; Android via its shared OkHttp interceptor. Prefer an active span per user action when one exists; never send trace headers to third-party hosts.
5. Propagate a trace context from Laravel into upstream Supabase requests separately from the existing server-generated correlation ID, then persist the trace ID in Laravel operation telemetry if the schema is extended.
6. Keep telemetry fail-open and bounded so tracing/audit failure cannot block emergency creation, assignment, status progress, or SOS reporting.

Before writing a migration, prove the current hosted PostgREST request-header behavior with a read-only-safe diagnostic in an approved test environment. Clean PostgreSQL replay tests should simulate `request.headers` using transaction-local `set_config`; a separate disposable PostgREST integration test must prove the actual HTTP header reaches SQL. Do not infer this from a SQL-only replay.

### 9.4 Required tests and gates

- valid W3C version-00 traceparent yields the expected trace ID;
- missing, malformed, unsupported-version, and all-zero traceparent yields NULL without failing the business operation;
- header names are handled case-insensitively through PostgREST's lowercase JSON representation;
- two event rows emitted by one RPC share the same trace ID;
- separate requests do not accidentally share a trace ID unless an explicit parent operation created that trace;
- actor/profile/session authorization remains independent of trace context;
- a caller-supplied trace ID cannot change actor attribution or access control;
- no tokens, request/response bodies, IP, user-agent, cookies, or precise location are copied into event metadata;
- Admin feed search can find events by trace ID without confusing it with provider request ID or Laravel correlation ID;
- telemetry failure does not change emergency RPC success/failure semantics.

### 9.5 References

- Supabase, [Client-side tracing](https://supabase.com/docs/guides/observability/client-side-tracing) — current SDK support, opt-in behavior, and W3C trace propagation.
- Supabase, [Advanced log filtering and request correlation](https://supabase.com/docs/guides/observability/advanced-log-filtering) — API Gateway `sb-request-id` mapping and trace ID log correlation.
- Supabase, [Securing your API — request information](https://supabase.com/docs/guides/api/securing-your-api) — Postgres `request.headers` access.
- PostgREST, [Transactions and request headers](https://docs.postgrest.org/en/stable/references/transactions.html) — transaction-local request context and lowercase header keys.
- W3C, [Trace Context](https://www.w3.org/TR/trace-context/) — standard `traceparent` format and validation.

This is a research-only follow-up. No trace propagation, event schema, RLS/grant, Auth setting, hosted project, or production service was changed by this section.


## 10. Direct-client trace correlation research

The direct Android/browser coverage map and W3C trace-propagation design are recorded in `docs/security/direct-supabase-request-correlation-plan.md`. The important implementation gate is to prove that the actual PostgREST HTTP request header reaches SQL before adding trace columns or writers. The browser dependency is new enough for the documented Supabase JS feature but tracing is not configured; Android uses custom Retrofit/OkHttp and has no trace propagation. The API Gateway's `sb-request-id` is mapped to provider log `request_id`, while W3C `traceparent` provides a separate `trace_id`. Treat trace IDs only as tracing hints, never as identity or authorization. This follow-up remains research-only.


## 11. Sensitive-operation session revocation enforcement — exact boundary research

**Research update:** 2026-11-27  
**Status:** Session-enforcement migration and regression contracts are committed to PR #370; exact-head CI and local Auth/PostgREST verification are pending. Not deployed.

### 11.1 Verified behavior from a disposable local Supabase stack

The PR's expanded local Auth → PostgREST test now proves all of the following with Auth-issued tokens:

1. POST /auth/v1/logout?scope=local removes the matching auth.sessions row.
2. The already-issued, unexpired access JWT remains accepted by ordinary PostgREST JWT verification after that row is removed.
3. Its original sub and session_id claims still reach the SQL probe.
4. The revoked refresh token is rejected.

This is not a reason to reject every request that presents a JWT after sign-out at the API gateway; ordinary PostgREST JWT verification is intentionally stateless. It is a reason to add an explicit database-side check at the operations that require immediate revocation enforcement.

### 11.2 Existing Admin gate does not yet enforce session-row presence

The current private.tacresq_require_admin_mfa() helper checks:

- non-null auth.uid();
- JWT aal = aal2;
- a TOTP entry in JWT amr;
- a current profile mapped to auth.uid(), with role = admin and account_status = active;
- a FOR SHARE lock on that active Admin profile.

It does **not** currently inspect JWT session_id or auth.sessions. The new session-inventory and investigation RPCs call this same helper, so their current AAL2/TOTP/active-profile check is not equivalent to immediate session-revocation enforcement.

The same helper is reused by the Admin directory, role-change and account-lifecycle wrappers, audit readers, analytics, session inventory, and investigation feed. Strengthening this shared helper is preferable to copying divergent checks into each wrapper, but it has a wider effect than the new session page alone and must be regression-tested against every caller.

### 11.3 Recommended database enforcement pattern

For **sensitive Admin RPCs** that already call private.tacresq_require_admin_mfa(), the next implementation candidate is to extend that helper so it also:

1. Reads the session_id claim from the verified PostgREST JWT context.
2. Rejects a missing, empty, malformed, or all-zero session UUID without attempting an unsafe cast.
3. Requires a row in auth.sessions where id = session_id and user_id = auth.uid().
4. Uses a VOLATILE, SECURITY DEFINER write-capable helper that takes a transaction-scoped FOR SHARE row lock on the matching session while the privileged RPC runs, in addition to the existing active-profile lock.
5. Fails closed for these privileged Admin operations if the session record cannot be verified; it must not silently fall back to AAL2/TOTP-only authorization.

Direct-table SELECT policies run in read-only PostgREST transactions, so they must not call the row-locking helper. Add a separate STABLE, read-only session-presence predicate for restrictive RLS policies. It validates the same session_id/user_id relationship without taking a row lock. This preserves read-only GET behavior; it also means an already-running SELECT can complete if it observed the session before logout commits, while subsequent SELECTs are denied.

The ownership predicate is essential: a session UUID alone must not be treated as proof that it belongs to the caller. The check belongs inside Postgres, in the same transaction as the protected RPC, because Admin web calls Supabase directly and bypasses Laravel middleware. A Laravel-only check would leave that direct path open.

The session row lock provides a clear transaction boundary: a protected operation that has already acquired the matching row lock may finish before a concurrent logout deletion completes; a later operation sees no row and is denied. This does not promise cancellation of an operation already in flight when logout begins.

Do not add a session lookup to every ordinary requester SOS read/write or every low-risk application request as part of this Admin slice. Any expansion to responder, dispatcher, or requester actions needs a separate per-operation risk and availability review. In particular, never make device attestation or a new device-registration requirement a prerequisite for a requester creating an emergency request.

### 11.4 Entry-point and bypass boundaries

| Surface | Current evidence | Required handling |
|---|---|---|
| Admin web direct Supabase RPCs | Protected wrappers call the shared Admin MFA helper | Database helper is the authoritative enforcement point; browser route guards are only UX |
| Admin direct table reads protected by RLS | Admin audit history and assignment history have restrictive AAL2/TOTP policies | Add the STABLE read-only session-presence predicate to those restrictive Admin branches; do not use a row-locking helper in a SELECT policy |
| Laravel Supabase bridge | SupabaseBridge::getUser() validates a token by calling Auth /auth/v1/user; callRpc() forwards the same caller JWT to PostgREST | The database guard still applies to the forwarded RPC. Do not treat Laravel's Auth validation as a substitute for the transaction-local session check |
| Laravel legacy auth:sanctum routes | Sanctum tokens belong to a separate Laravel identity/token domain | Do not compare Sanctum token IDs to Supabase session_id; define a separate policy if these legacy routes remain supported |
| Android direct Supabase RPCs | Direct calls bypass Laravel | Database-side enforcement is required wherever the operation is in the Admin-sensitive scope |
| Edge Functions / service-role workers | Function JWT verification and service-role execution are separate from the caller's PostgREST SQL transaction | Do not assume the Admin helper protects these paths; trace caller identity propagation and server authorization independently |
| Session inventory UI | Read-only RPC returns record_present and validity_assessment = not_evaluated | Preserve this honest display semantics; do not equate row presence with full provider-policy validity |

### 11.5 Required regression matrix before changing the helper

Run against disposable clean PostgreSQL replay and the local Auth/PostgREST stack; use synthetic users and tokens only.

- Active Admin + current AAL2/TOTP JWT + matching session row: existing protected Admin RPCs continue to work.
- Active Admin + AAL2/TOTP JWT + deleted session row: every protected Admin RPC is denied.
- Active Admin + JWT whose session_id belongs to another Auth user: denied.
- Active Admin + missing, malformed, or all-zero session_id: denied cleanly, with no cast error leaking details.
- Active Admin + valid session row but AAL1 or no TOTP: remains denied.
- Suspended Admin + otherwise-valid JWT/session: remains denied.
- Non-Admin + valid session row and AAL2/TOTP: remains denied.
- Authenticated, anon, and service_role direct execution of the private helper: remains denied.
- Session inventory and investigation readers: continue to work for an authorized current Admin session and reject revoked sessions.
- Existing account lifecycle, role-change, audit, and analytics RPCs: preserve grants, output shape, profile-lock behavior, and atomic audit semantics.
- Concurrent logout vs protected Admin RPC: document and test the row-lock ordering; no operation may begin after the session deletion commits.
- Direct table/RLS paths: revoked-session tokens must not retain sensitive Admin table access through an independent policy that omits the session check.
- Laravel bridge: a revoked token must fail at the protected PostgREST operation even if a prior /auth/v1/user validation request succeeded before revocation.
- Emergency requester create/retry, cancellation, and other normal non-Admin flows: remain unchanged by this Admin-only enforcement slice.

### 11.6 Implementation order and release gate

1. Enumerate every current client-callable Admin SECURITY DEFINER wrapper and every Admin-sensitive direct-table RLS policy/grant on the branch head.
2. Confirm the helper owner can read and lock auth.sessions in the clean PG16 and PG17 migration replay environments without granting direct Auth-table access to API roles.
3. Add the shared helper check in a forward migration only after the inventory is complete.
4. Extend the existing Admin session SQL test and static contracts with same-valid-JWT/revoked-session cases; do not create a parallel generic test framework.
5. Extend the local Supabase Auth/PostgREST integration test to exercise at least one protected Admin RPC with a real Auth-issued JWT, then sign out and prove that same unexpired JWT is denied by the database helper.
6. Run all exact-head CI workflows, both PostgreSQL replay versions, RPC grant contracts, and local Supabase integration. Report the advisory local-stack result separately.
7. Keep PR #370 open and unmerged; do not apply the migration to hosted Supabase or deploy Render as part of this work.

### 11.7 Current Supabase documentation and compatibility note

The current Supabase User Sessions guide documents the session_id claim and explicitly recommends checking it against auth.sessions for sensitive actions when post-sign-out guarantees are required. It also cautions that max-lifetime and inactivity timeout sessions may remain in the table until later cleanup; therefore this proposed check is specifically a sign-out/session-row revocation check, not a complete independent implementation of every Auth timeout policy.

The November 2026 Supabase changelog review found no Auth breaking change that alters this session-row contract. The Supabase CLI v3 local-by-default behavior is a separate CLI breaking change; this PR's CI continues to use its existing pinned CLI workflow and must not be casually upgraded as part of this change.

References:
- [Supabase User Sessions](https://supabase.com/docs/guides/auth/sessions)
- [Supabase Sign out](https://supabase.com/docs/guides/auth/signout)
- [Supabase changelog — Auth](https://supabase.com/changelog?tags=auth)
- [Supabase changelog — breaking changes](https://supabase.com/changelog?types=breaking-change)

No hosted Auth settings, Auth sessions, database grants, migrations, Render services, or production data were changed during this research.

### 11.8 Branch implementation checkpoint

The following repository-only changes now exist on PR #370:

- Migration 20261127040000 adds two separate session predicates: a STABLE, read-only predicate for direct SELECT/RLS policies and a VOLATILE row-locking predicate for protected RPCs.
- The shared Admin MFA helper now requires the caller's JWT session_id to identify a row in auth.sessions owned by auth.uid(), and holds a FOR SHARE lock for the protected transaction.
- The restrictive Admin audit-history and Admin assignment-history RLS policies now include the read-only session-presence predicate. The dispatcher branch of assignment-history access is preserved.
- The existing disposable SQL session-inventory test now covers valid, cross-user, missing, malformed, all-zero, and deleted session IDs; it also checks direct Admin audit-history access disappears after session-row deletion.
- The local Auth/PostgREST integration harness now probes both predicates with real Auth-issued tokens, a correctly signed cross-user session_id token, and the old access JWT after local sign-out.
- Migration-history alignment classifies the new migration as an unapplied forward migration. It is not present in the connected project's migration ledger.

**Validation checkpoint (2026-11-27, code commit `94abb9f5251eabfd2f7f722557c6007b6c14671d`; documentation follow-up `46b2be2a15119256ef52e7d4d20524c16c939cff)):**
- Static source checks, Python contracts, TypeScript/Admin web build, Android debug/release builds, JVM tests, instrumentation-test compilation, and Android Lint passed.
- PostgreSQL 17 RPC grant contract passed, including the expanded SQL runtime test that searches separate Admin and requester sessions across role, lifecycle, claimability, assignment, incident-status, and requester-edit sources.
- Clean PostgreSQL migration replay passed with both new forward migrations.
- The disposable local Supabase stack replayed migrations successfully after CI excluded the unused Mailpit container. The Auth/PostgREST JWT context and traceparent test passed, including concurrent request/header isolation, sign-out session-row deletion, stale access-JWT database denial, and revoked refresh-token rejection.
- Laravel/dashboard integration, QA prototype capture/stress checks, Frontend Review, read-only profile verification, repository security guardrails, CodeQL for Android/web/Actions, and automated code review passed.
- The local Supabase lane remains advisory and is reported separately from required CI. Local traceparent delivery does not establish hosted Supabase behavior or current Android/web client propagation.
- Session activity coverage now includes the signed Auth session claim on trusted incident, claimability, requester-edit, assignment, Admin role, and account-lifecycle events. The unified investigation feed exposes these fields and includes account lifecycle rows. The separate Laravel Sanctum lifecycle ledger remains isolated. `device_security_events` still has no trusted writer, and Supabase Auth audit payloads are not assumed to contain a session ID.

No hosted migration, ACL update, Auth-session mutation, Render configuration change, production deployment, or merge was performed. PR #370 remains open and unmerged. The current PR head is documentation-only follow-up to the fully green code commit above; its checks are being rerun.


### 11.9 W3C trace context milestone — repository implementation

Migration `20261127080000_admin_trace_context_correlation.sql` adds a private, fail-open parser for W3C version-00 `traceparent`. It returns only the 32-character lowercase trace ID and rejects malformed headers, unsupported versions, all-zero trace IDs, and all-zero span IDs. The parser catches malformed request-header JSON and returns NULL rather than failing the event transaction.

A private BEFORE INSERT trigger stamps nullable `trace_id` fields on the same nine trusted event tables that already carry Auth session attribution. The trigger overwrites any supplied column value from the transaction header; it does not accept a body-provided trace ID. Trace IDs are stored as 32-hex text, indexed, and constrained to the expected format. No foreign key to `auth.sessions` is added. Auth provider audit rows are not modified because their writer is provider-managed and no verified trace-header ingestion path has been established for that source.

The protected Admin investigation feed includes trace IDs in its minimized details and supports exact 32-hex trace-ID search. The UI labels this value **W3C trace ID** and explicitly distinguishes it from Auth session IDs, Laravel correlation IDs, and Supabase provider request IDs. It does not present trace context as evidence of identity, compromise, or authorization.

Laravel now validates inbound traceparent syntax before forwarding it to Supabase Auth, allowlisted RPC requests, and the metadata-only API-operation audit sink. This is propagation of caller-influenced context, not server-generated trace-root creation. Malformed values are omitted. The PHP bridge and audit logger do not store raw headers or add trace data to business payloads; PostgREST event triggers parse the forwarded header.

Tests added:
- PHP validation for valid/normalized, missing, malformed, unsupported-version, zero-trace, and zero-span headers.
- Laravel bridge and audit-writer header propagation assertions.
- PostgreSQL runtime parser checks for valid and invalid request contexts.
- Trusted event-trigger assertions for API-operation, Admin, lifecycle, claimability, assignment, incident-status, and requester-edit rows.
- Protected feed search assertions across independent traces.
- Static migration, privacy, and Admin UI contracts.

**Coverage limits:** The shared Android OkHttp and browser Supabase-js transports now generate a fresh W3C traceparent per request, covering their Auth, PostgREST, Storage, and bridge calls. This does not cover requests that bypass those shared transports. Device-security events still have no trusted writer, and provider-managed Supabase Auth audit rows remain outside trace persistence. A missing trace ID therefore means only that no valid traceparent reached that event writer; it does not mean the event is absent or suspicious. The local PostgREST header-delivery integration proof remains the source of truth for gateway-to-database header availability. No hosted migration or deployment is authorized by this code change.
