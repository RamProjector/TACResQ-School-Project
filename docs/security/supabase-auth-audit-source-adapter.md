# Supabase Auth Audit Source — Research and Read-Adapter Decision

**Observed:** 2026-11-27  
**Tracking:** PR #370, `docs/admin-ips-ui-master-plan`  
**Status:** Native provider-source read adapter and disposable runtime contract are implemented; hosted Postgres persistence remains unverified and is not enabled by this change.

## Decision

Use Supabase's **native optional Postgres mirror** (`auth.audit_log_entries`) as the Admin investigation feed's only database-backed Auth audit source. Keep the reader read-only, server-side, TOTP/AAL2 protected, and allowlist-only.

Do not create a second application-owned Auth audit ledger, trigger on Supabase-owned Auth tables, custom Auth hook, Management API poller, or external log-drain integration for this task. The available live evidence does not justify duplicating provider records or introducing another credential/service dependency.

The Admin page must distinguish:
- provider Auth audit events in Supabase's external `auth_audit_logs` stream;
- the optional Postgres mirror queried by the Admin RPC;
- the current state of the mirror setting, which is **not observable from the current project metadata**.

An empty Postgres mirror is not evidence of no authentication activity and cannot distinguish disabled persistence from no events written to the mirror.

## Official product behavior

Supabase documents that Auth audit events are captured automatically and stored in two separate destinations:
1. external log storage, visible through the Supabase dashboard; and
2. optional Postgres storage in `auth.audit_log_entries`, enabled in Authentication → Audit Logs → Configuration → **Write audit logs to the database**.

Postgres persistence consumes database storage. Enabling it affects future events and does not backfill earlier external log events.

Supabase Auth Hooks are not a general-purpose webhook for mirroring every Auth audit event. The documented hooks alter selected authentication-flow stages (for example, before-user-created, custom-access-token, send-email/SMS, and certain MFA/password verification attempts). Some verification hooks are plan-gated. Introducing a hook would couple audit capture to authentication success/latency and could reject or delay sign-in when the hook fails. It is not the correct substitute for the provider's audit storage.

References:
- [Supabase Auth Audit Logs](https://supabase.com/docs/guides/auth/audit-logs)
- [Supabase Auth Hooks](https://supabase.com/docs/guides/auth/auth-hooks)
- [Supabase unified log sources and fields](https://supabase.com/docs/guides/observability/log-field-reference)

## Live read-only evidence

Connected project: TACResQ-School-Project (`kttguamhznoflzagfutr`), PostgreSQL 17.11, region `ap-southeast-1`, status `ACTIVE_HEALTHY`.

The latest read-only schema inspection reported:
- `auth.audit_log_entries` exists with provider-owned columns `id`, `payload`, `created_at`, and `ip_address`;
- row count: **0**;
- the Admin session/trace migrations dated 2026-11-27 are not in the hosted migration ledger.

The unified external log stream query for the observed 24-hour window returned **12** `auth_audit_logs` events, all INFO:
- 6 `token_refreshed`;
- 6 `token_revoked`;
- all 12 had an `auth_audit_event.actor_id` and `auth_audit_event.request_id` field.

The confirmed structured keys include `auth_audit_event.action`, `actor_id`, `request_id`, `audit_log_id`, `user_agent`, and `created_at`. Only aggregate action counts and field-presence counts were retained here; no actor IDs, provider request IDs, IP addresses, or user-agent values are copied into this document or the Admin UI.

The external stream observation proves that provider events exist independently of the empty Postgres table. It does **not** prove the project's Postgres-persistence toggle is on or off. The current Supabase connector does not expose a read-only inspection action for that Auth-specific setting.

## Implemented in PR #370

### 1. Native source adapter remains read-only

The protected `api_admin_list_investigation_events` RPC already reads `auth.audit_log_entries` and projects only:
- source identity: `supabase_auth_audit_logs`;
- category: `authentication`;
- provider action;
- event timestamp;
- actor mapping through `profiles.auth_user_id`, when a valid Auth user UUID matches.

It does not return the provider's raw payload, IP address, user-agent, or provider-owned row wholesale. It does not invent an Auth session ID, W3C trace ID, or Laravel correlation ID for provider events. The source remains behind the Admin AAL2/TOTP authorization boundary; no direct client table grant is added.

### 2. Runtime integration contract added

`tools/test_admin_api_operation_investigation.sql` now inserts one synthetic `auth.audit_log_entries` fixture into the disposable PostgreSQL test transaction and queries the actual protected investigation RPC as the synthetic Admin.

The test asserts:
- the event appears under `supabase_auth_audit_logs` / `authentication`;
- its provider action is preserved;
- the actor maps through the Auth-user-to-profile relation;
- no `auth_session_id`, `trace_id`, IP, user-agent, or raw payload is exposed.

The fixture is rolled back with the test transaction. It is not inserted into the hosted project.

### 3. Admin coverage copy corrected

The Audit Logs page now says explicitly that it reads only the optional Postgres mirror and does not query the separate external provider stream. It also states that the persistence setting is not observable from the page and that an empty mirror cannot prove no authentication activity occurred.

### 4. Static regression contract extended

`tools/test_admin_investigation_events_contract.py` now requires the runtime fixture/assertions and the updated source-coverage wording.

## Correlation rules

- Provider `request_id` is a per-request Auth provider reference. It is not an Auth session ID, a W3C trace ID, or a Laravel correlation ID.
- The external unified log stream exposes provider request IDs, but the live Postgres mirror has zero rows. Do not assume its `payload` stores the same request ID until a populated mirror is observed and the exact field is inspected.
- Supabase Auth audit payloads must not be assumed to contain a signed JWT `session_id`.
- Do not join provider audit events to application events solely by actor and timestamp proximity.
- Do not attach a browser-supplied `traceparent` to provider-managed Auth rows; no trusted path has been established for doing so.
- Keep provider audit records and application-generated event records source-qualified.

## Verification still required before claiming live coverage

1. Project operator checks Authentication → Audit Logs → Configuration and confirms whether **Write audit logs to the database** is enabled. Do not change this setting through this PR.
2. If the operator approves enabling it, record the current setting first, enable it in the Dashboard, and generate a controlled future Auth event using an approved test account.
3. Confirm the new row appears in `auth.audit_log_entries` after the documented ingestion delay.
4. Verify the exact stored payload shape on that new row without exporting raw IP/user-agent values.
5. Confirm the protected Admin RPC returns the minimized event and the browser does not gain direct access to the Auth table.
6. Confirm retention and database-storage impact before treating the mirror as a permanent audit archive.

No historic external log events are expected to backfill into Postgres automatically.

## Explicit non-actions

- No hosted Auth setting was changed.
- No hosted migration or SQL write was applied.
- No trigger, hook, Edge Function, log drain, external monitoring vendor, or new secret was added.
- No production event was inserted or altered.
- No Render setting, deployment, or PR merge was performed.
