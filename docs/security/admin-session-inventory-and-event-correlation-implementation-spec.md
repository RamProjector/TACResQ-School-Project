# TACResQ Admin Session Inventory & Security Event Correlation — Implementation Specification

**Date:** 2026-11-27  
**Status:** Research/design complete; implementation not started  
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

For Laravel bridge operations, preserve the middleware-generated correlation ID through each audit write and store the actual provider request ID only when the provider returns one. For direct Supabase actions, use a provider-supported request/trace identifier only after verifying what the installed SDK and PostgREST/Auth gateway actually expose to the database/function. If no trusted identifier is available, record the event as uncorrelated rather than inventing a link.

### 4.4 Investigation feed and alerts

Keep source-specific feeds distinguishable and paginated. Add `api_operation_logs` and `device_security_events` only after trusted writers and source contracts exist. Preserve source timestamp, event ID, actor mapping, outcome, and correlation provenance. Add explicit source coverage/freshness status; a zero-row source is **no events recorded in this source**, not **no suspicious activity**.

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

The first implementation slice is now committed on the PR branch: a protected, read-only, keyset-paginated session-list RPC; a validated typed client helper; and an account-detail session panel with explicit record-present / validity-not-evaluated semantics. The RPC uses the existing active-Admin + AAL2 + TOTP helper, resolves Auth ownership from the target profile, and exposes no IP, user-agent, token, token-HMAC, or refresh-token metadata. It does not revoke sessions or assert that a row is currently valid. Contract tests cover the SQL authorization/privacy/pagination shape and the web integration. Exact-head clean PostgreSQL migration replay, TypeScript build, and workflow checks remain pending; do not claim runtime acceptance until they pass. The empty Auth audit relation and absent trusted device/API event writers still prevent a complete correlated alert feed. Next: inspect the new workflow results and fix any clean-replay, RPC grant, TypeScript, or static contract failures before expanding event ingestion. Do not add selective revocation or device alerts in the same change.
