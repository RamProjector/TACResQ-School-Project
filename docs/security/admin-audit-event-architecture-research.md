# TACResQ Admin Audit and Security-Event Architecture Research

**Status:** research and implementation design; no production configuration or database changes authorized  
**Tracking branch:** `admin/remove-unimplemented-pages` (PR #338)  
**Research date:** 2026-11-22

## Executive finding

The current TACResQ Admin Audit History is an application audit view for successful role changes. It is not a complete security-event or operational-event history.

Keep three event domains distinct:

1. **Application administrative audit** — privileged TACResQ application mutations, beginning with role changes.
2. **Identity-provider authentication audit** — Supabase Auth sign-in, sign-out, token, recovery, and MFA events.
3. **Operational incident history** — incident lifecycle, assignment, responder/driver status, and other emergency workflow events.

A unified investigation interface may eventually query multiple domains, but every result must retain its source and event type. Do not copy provider events into the role-change table or label role-change history as a complete audit trail.

## Verified repository baseline

- `public.admin_audit_logs` currently accepts only `user_role_changed`.
- The role-change RPC writes its audit record in the same database transaction as the profile role update. A failed transaction therefore does not leave a successful role-change record.
- The audit table is not client-writable through the ordinary authenticated role; the current UI is read-only.
- The dedicated page uses a TOTP-protected, bounded, server-side paginated RPC with actor/target, action, date, and text filters.
- The current page explicitly says that incident/assignment histories remain in their workflows.
- The current actor label is a numeric Admin profile identity snapshot; it is not a verified human name or email.
- The current schema does not record denied Admin RPC attempts, request correlation IDs, or general security events.
- The project has a documented separate Supabase Auth Audit Log source, but this repository has not verified that database persistence is enabled in the target Supabase project.
- Supabase Auth global sign-out revokes refreshable sessions, but already-issued access JWTs can remain valid until expiry. This is a session behavior, not proof that every protected operation is immediately revoked.

## External research findings

### Supabase Auth Audit Logs

Supabase documents Auth Audit Logs for authentication-related activity, including sign-up/sign-in, password and recovery events, token refresh/revocation, sign-out, and MFA factor/challenge events.

Supabase supports dashboard/external log storage and optional Postgres persistence in `auth.audit_log_entries`. The Postgres option is a project configuration choice and uses database storage. The documentation also notes that log availability may be delayed and dashboard query capabilities are limited.

The documented payload can contain user ID, action, timestamp, IP address, user-agent, and metadata. IP address is present only for some event classes. This data requires privacy review and field minimization before any Admin exposure.

**Decision:** Do not assume Postgres persistence is enabled, do not query the provider's internal Auth schema from the browser, and do not promise complete event coverage or retention until the actual project setting, schema, grants, and retention are verified.

### Supabase Platform Audit Logs

Supabase Platform Audit Logs cover organization/project dashboard and API administration. Current documentation states that Platform Audit Logs are available on Team and Enterprise plans. They are therefore not an assumed dependency for this free-service-constrained project.

**Decision:** Do not add a paid-plan dependency or claim these records are available to TACResQ.

### OWASP logging guidance

OWASP recommends application-level logging for security-relevant events, including authentication successes/failures, authorization failures, session-management failures, privileged user administration, configuration changes, and high-risk data exports.

Useful event context includes when, where, who, what, the outcome, reason, and an interaction/correlation identifier. OWASP also warns against logging passwords, access tokens, session identifiers, and unnecessary sensitive personal data. Logging must be bounded and failure behavior must not create an availability problem.

**Decision:** Use a small, documented event taxonomy and privacy-conscious fields rather than logging request bodies or every application action indiscriminately.

## Proposed event model

Do not implement all event sources in one table by default. First establish the source contract and verify whether each source is authoritative.

| Domain | Initial event types | Authoritative source | Current status |
|---|---|---|---|
| Application administration | Role change; future trusted Admin provisioning/rotation | TACResQ database transaction | Role change implemented |
| Authorization/security | Denied privileged operation; rejected role transition; sensitive export | Trusted server/database boundary | Not centrally recorded |
| Authentication | Sign-in/out, recovery, token and MFA events | Supabase Auth Audit Logs | Provider source documented; Postgres persistence unverified |
| Operational incidents | Incident creation/status transitions | Existing incident workflow/database | Separate operational history; coverage requires inventory |
| Assignment | Assignment, release, reassignment | Existing assignment workflow/database | Separate operational history; coverage requires inventory |
| Platform administration | Project/configuration changes | Supabase Platform Audit Logs | Plan-restricted; not a project dependency |

### Common normalized fields for a future read-only investigation view

Where supported by the authoritative source, expose only:

- `event_id` — source-local immutable identifier.
- `source` — for example `tacresq_app`, `supabase_auth`, or `incident_workflow`.
- `event_type` — stable machine-readable event key.
- `occurred_at` — source timestamp in UTC.
- `actor_profile_id` / `actor_auth_user_id` — nullable and source-appropriate.
- `target_type` and `target_id` — nullable.
- `outcome` — success, denied, rejected, or unavailable where source supports it.
- `reason_code` — bounded and sanitized; avoid storing secrets or arbitrary request payloads.
- `correlation_id` — only where generated and propagated by trusted services.
- `summary` — short sanitized human-readable description.

Do not normalize away source-specific semantics. Do not infer a human identity from matching email addresses across the separate Supabase Auth and Laravel Sanctum identity systems.

### Data that must not be copied into the Admin event feed

- Passwords, OTP/TOTP codes, recovery codes, access/refresh tokens, cookies, or session secrets.
- Full request/response bodies or arbitrary metadata blobs.
- Private chat content, emergency descriptions, or attachment contents.
- Precise GPS coordinates unless a separately authorized incident workflow requires them.
- Raw IP address and user-agent by default. If a source provides them, assess purpose, access, retention, and legal/privacy requirements before exposing them.

## Recommended implementation sequence

### Stage 1 — Verify source availability (research/operations gate)

1. Confirm whether Supabase Auth Audit Log Postgres storage is enabled in the actual project dashboard.
2. If enabled, inspect the actual `auth.audit_log_entries` schema and sample payload shape using a read-only, privileged operator inspection.
3. Verify grants, access path, retention/availability, event delay, and whether the project exposes all event types expected by the UI.
4. Inventory existing incident and assignment history tables/RPCs and identify which transitions are recorded transactionally.
5. Record findings without copying secrets, IPs, user-agent values, or sensitive event payloads into this repository.

No project setting should be changed as part of this research step.

### Stage 2 — Complete application-side privileged-event coverage

1. Define the event taxonomy and stable outcome/reason codes.
2. Identify each privileged RPC and trusted mutation boundary.
3. Add denied-operation telemetry only at trusted boundaries where the actor can be identified safely; never trust actor IDs supplied by the browser.
4. Ensure successful mutation and its audit event are committed atomically where feasible.
5. Ensure audit-write failure behavior is explicit: privileged mutations should fail closed if their required audit event cannot be committed; emergency requester/dispatch operations must not be blocked by unrelated optional telemetry.
6. Add bounded event volume, retention policy, and tests for redaction, access control, and failure behavior.
7. Do not log raw credentials, JWTs, arbitrary request bodies, or sensitive incident payloads.

### Stage 3 — Read-only investigation interface

Only after the sources are verified:

- Add a source filter and event-type filter.
- Display source, timestamp, actor, target, outcome, and sanitized details.
- Clearly label unavailable sources and last successful retrieval time.
- Preserve per-source pagination and ordering; do not claim a globally complete timeline if sources have different delays or retention.
- Require current Admin role plus AAL2/TOTP for every privileged read.
- Use a narrow server-side view/RPC; never expose the `auth` schema or service-role credentials to the browser.
- Keep the existing role-change history readable if provider Auth logs are unavailable.

### Stage 4 — Detection and response

After event coverage and reliability are proven:

- Detect repeated denied privileged operations, unusual role changes, repeated MFA failures, and abnormal request bursts.
- Use thresholds and correlation rather than automatically treating one malformed request as an attack.
- Add an operator response runbook for investigation, session revocation, evidence preservation, and recovery.
- Do not automatically suspend accounts or block emergency requests solely on a heuristic signal.

## Acceptance criteria before claiming comprehensive audit coverage

- Every supported privileged mutation has a trusted actor, target, timestamp, action, outcome, and reason or reason code.
- Successful role changes and their audit records remain atomic.
- Denied operations are recorded only where the server can establish reliable context; no client-supplied actor identity is trusted.
- Auth provider events retain their provider source and source event IDs.
- No credentials, tokens, OTPs, private messages, attachment contents, or unnecessary precise-location data are stored in the event feed.
- Anonymous and ordinary roles cannot read administrative event sources.
- Admin event reads require the same current Admin + AAL2 + TOTP checks as existing Admin RPCs.
- Pagination and filters remain bounded and cannot expose records outside the requested source scope.
- Source unavailability is visible and is never presented as an empty history or proof that no events occurred.
- Logging failure behavior is tested and does not unintentionally block emergency request/dispatch availability.
- Retention, access, and export policies are documented before broadening access.

## Explicit non-goals

- No paid Supabase Platform Audit Log dependency.
- No new monitoring vendor, paid WAF, paid SIEM, or OpenAI API dependency.
- No direct browser access to the Supabase `auth` schema.
- No merging of Laravel Sanctum identities with Supabase Auth identities by email.
- No automatic account suspension based only on heuristic alerts.
- No production Auth setting changes, migration application, secret changes, or deployment.
- No claim that the current role-change table is a complete audit trail.

## References

- Supabase Auth Audit Logs: https://supabase.com/docs/guides/auth/audit-logs
- Supabase Platform Audit Logs and plan availability: https://supabase.com/docs/guides/security/platform-audit-logs
- Supabase Auth sessions and sign-out behavior: https://supabase.com/docs/guides/auth/sessions
- OWASP Logging Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- OWASP Logging event attributes and interaction identifiers: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html#event-attributes
