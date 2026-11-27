# TACResQ Admin Audit Trail — Source Inventory and Implementation Readiness

**Reviewed branch:** `admin/remove-unimplemented-pages`  
**Pull request:** #338  
**Review date:** 2026-11-22  
**Scope:** Admin website and the backend event sources required to support its Audit Logs page.  
**Status:** Source review only. No production configuration or database state was changed.

## Executive finding

TACResQ does not need a new, all-purpose audit table. The repository already contains several domain-specific event histories. The correct implementation is a protected Admin investigation interface that reads approved sources through a bounded, server-side aggregation layer, while preserving each source's existing ownership, privacy, and retention boundaries.

The current Audit Logs page is accurately limited to administrative role changes. It must not be relabeled as a complete audit trail until additional sources are safely integrated and coverage limitations are explicit.

## 1.1 Deployed Supabase verification snapshot

A read-only inspection was performed against the connected TACResQ Supabase project on 2026-11-22. The project reported `ACTIVE_HEALTHY` on PostgreSQL 17.11.

- The deployed migration ledger currently ends at `20261122050858_require_totp_for_admin_rpcs`.
- The branch migration `20261125020814_admin_audit_server_pagination.sql` is **not applied** in the connected database.
- The deployed legacy `api_admin_list_audit_logs(integer)` exists, but `api_admin_list_audit_logs_page(...)` does not. The current branch has since moved the page to `api_admin_list_investigation_events(...)`; the older page RPC migration remains in the branch for compatibility but is no longer a direct dependency of the updated page. The new investigation migration depends on the already-deployed Admin TOTP helper and must still be reviewed and applied separately.
- All reviewed event relations exist in the connected database: `admin_audit_logs`, `incident_status_logs`, `incident_assignment_events`, `incident_claimability_logs`, `incident_request_edit_logs`, `api_operation_logs`, `device_security_events`, `fcm_delivery_attempts`, and `auth.audit_log_entries`.
- The read-only count snapshot returned 1 Admin role-change record, 2 incident status records, and 0 records in assignment history, claimability history, API operation telemetry, device security events, Auth Audit Logs, and FCM delivery attempts. These are point-in-time counts, not a claim that those sources are configured or operational end-to-end.
- The `auth.audit_log_entries` relation exists but currently has zero rows. Supabase's current Auth Audit Logs documentation says Postgres storage is optional and can be disabled while external dashboard logs remain available. The project-level toggle could not be verified through the available project metadata. Therefore, treat the database source as present but unconfirmed for event capture until the Dashboard setting is checked.
- The connected database grants authenticated SELECT on `admin_audit_logs`, `incident_status_logs`, and `incident_assignment_events`; RLS further restricts row visibility. Authenticated direct SELECT is not granted on `incident_claimability_logs`, `api_operation_logs`, or `device_security_events`.
- The deployed `incident_status_logs` SELECT policy allows participants and dispatchers, not Admins. The `incident_assignment_events` SELECT policy allows dispatchers and Admins, but does not itself require Admin TOTP/AAL2. Any Admin investigation reader must therefore enforce Admin + AAL2 + TOTP, and the direct assignment-event policy should be hardened so an Admin cannot bypass step-up authentication by querying the table directly. Dispatcher access must remain unchanged.
- The live assignment-event table currently has zero rows; its trigger/migration source exists, but the Admin feed must still show an accurate empty state rather than implying that assignment history is missing or broken.

This verification is read-only. No project settings, grants, policies, functions, or data were changed.

## 1. Verified source inventory

| Source | Existing purpose and captured events | Current access / limitation | Admin integration decision |
|---|---|---|---|
| `admin_audit_logs` | Append-only role changes; actor profile snapshot, target, old/new role, reason, timestamp. Inserted transactionally by the protected role-change RPC. | Authenticated direct writes/updates/deletes are revoked. Admin read path is protected by the TOTP/AAL2 RPC and restrictive MFA policy. Only `user_role_changed` is currently supported. | **Integrate first.** Preserve as the existing administrative audit source. Do not duplicate it. |
| `incident_status_logs` | Incident creation/status history; status, actor profile ID, timestamp. Trigger writes on insert and status changes. | Existing read policy is participant/dispatcher-oriented; do not assume Admin can read it through the existing policy. Rows record the resulting status, not a complete before/after snapshot. | **Integrate through a new narrow Admin read RPC** only after confirming deployed grants/policies. Display “status changed to …”; do not invent a previous status. |
| `incident_assignment_events` | Append-only team assignment, release, and incident-closure events; incident, affected field unit, actor when available, source, assignment/incident status, timestamp. | RLS read policy permits dispatcher/admin. Direct client writes are not granted. Migration adds triggers for membership insert/delete and incident closure. | **Reuse.** Add to Admin investigation feed through an Admin-protected bounded reader; retain dispatcher access rules and source attribution. |
| `incident_claimability_logs` | Claimability changes with incident, actor, source RPC, previous/new values, status, timestamp. | RLS enabled; direct client access revoked; service-role only. No Admin read API/page existed before this implementation. | **Integrate as an incident event.** The protected reader exposes only previous/new claimability, incident status, and source RPC; it does not broaden table grants. |
| `incident_request_edit_logs` | Requester edit history, including before/after request data. | RLS enabled and direct client privileges revoked; intended for constrained RPC/trusted access. Stored snapshots contain sensitive location/request details and narrative text. | **Integrate a sanitized event only.** The reader compares before/after values internally and returns allowlisted changed-field names plus edit number; raw values, coordinates, and narratives are never returned. |
| `api_operation_logs` | Laravel-mediated provider/operation telemetry: correlation ID, actor auth UUID when available, provider, operation, source, outcome, HTTP status, duration, provider request ID, error code, timestamp. | RLS enabled; service-role SELECT/INSERT only. Logger skips writes when server URL/secret is missing and uses bounded synchronous best-effort writes. Does not cover direct Android/dashboard Supabase calls, direct Storage calls, or the FCM worker comprehensively. | **Optional P1/P2.** Keep under System/Integration activity, not business audit. Expose only a safe metadata projection and clearly label its Laravel-only coverage. |
| Supabase Auth Audit Logs | Provider-side authentication and MFA events, including sign-in/session/factor activity where supported by the provider. | Separate from `admin_audit_logs`. Postgres storage is an optional project setting and is not verified as enabled. No project-level connection/read path was found in the reviewed branch. | **Do not build a UI against assumed data.** Verify project setting and available provider access first. If unavailable, document the gap; do not synthesize auth events. |
| `device_security_events` | Schema supports device registration/proof/integrity/revocation and suspicious-activity event types. | Migration explicitly keeps device tables backend-only until trusted verification endpoints, authorization, and runtime contracts exist. No integrated writer/read API was found in the reviewed source search. | **Not ready for UI integration.** Complete the device-security server workflow first; do not display an empty table as if monitoring is active. |
| `fcm_delivery_attempts` | Per-notification/token delivery attempt ledger, retries, provider outcomes, and failure states. | Server-only delivery ledger; not a record of user/admin intent. Actual worker execution and physical-device delivery are separately deployment-dependent. | **Optional operational diagnostics only.** Never treat provider acceptance as proof of device receipt or user viewing. |

## 2. Existing Admin website state

- Admin navigation currently has Overview, User management, Security & countermeasures, Analytics & reports, and Audit Logs.
- Audit Logs is already a dedicated, URL-addressable page.
- The current page supports server-side search, action/date filters, actor/target profile filters, pagination, refresh, and event details.
- The page currently queries only `admin_audit_logs` through `api_admin_list_audit_logs_page`.
- The read RPC validates page bounds, query length, action, date range, and positive actor/target IDs; it requires the shared Admin TOTP/AAL2 check.
- The page correctly states that role changes are its only current event type and explains that authentication, API telemetry, and operational histories are separate.
- The Security page's `AdminSecurityStatus` component currently checks the current session's AAL/TOTP status, lists the current account's TOTP factors, supports adding a backup authenticator, and supports explicit global sign-out. It is **not** an authentication activity feed.
- The Dispatcher web dashboard remains removed. This work must not restore it.

## 3. Important gaps and constraints

### 3.1 Authentication event availability

The repository references Supabase Auth Audit Logs, but that does not prove that the project has enabled Postgres storage or that a supported reader is available. Before implementation, verify the actual Supabase project configuration and the provider's current access method. Do not use service-role credentials in the browser. Do not enable optional provider storage without approval.

### 3.2 Actor attribution

Each source has different actor semantics:
- Role changes: authenticated Admin profile.
- Incident status: actor profile where available; initial creation is attributed to the requester.
- Assignment insert: `assigned_by`; release: actor derived from the authenticated caller where available.
- Automated transitions: may have no human actor and must be labeled as system/unknown rather than guessed.
- API telemetry: authenticated Supabase UUID may be absent.
- Device events: backend-generated actor/device attribution is not yet integrated.

The unified feed must preserve null/unknown attribution and identify the source. It must never infer the actor from the record owner.

### 3.3 Event semantics

- `incident_status_logs` stores the resulting status. It does not guarantee a previous/new pair for every event.
- `incident_assignment_events` records team membership and closure, not every field-unit status transition.
- `incident_claimability_logs` is separate from assignment membership.
- `api_operation_logs` is transport telemetry, not a business action ledger.
- `fcm_delivery_attempts` tracks provider delivery attempts, not device receipt or user acknowledgement.
- Provider authentication events are not equivalent to app authorization-denial events.

The UI must not flatten these into misleading generic “actions.” Each row needs a source-specific description and outcome semantics.

### 3.4 Privacy and data minimization

Never copy raw incident narratives, precise coordinates, contact details, private messages, attachment contents, passwords, access tokens, refresh tokens, MFA secrets, raw attestation tokens, or request/response bodies into a general audit feed.

For request-edit history, expose only a separately reviewed, minimized projection if there is a concrete investigation need. Use incident/profile identifiers and safe event summaries rather than copying sensitive snapshots.

### 3.5 Denied privileged actions remain a gap

The current durable source set does not reliably capture every denied Admin RPC or authorization failure. A database insert performed in the same transaction that raises an exception is rolled back with that transaction; a client-reported “denied” event would be spoofable and is not an acceptable audit source. The current Laravel API-operation logger covers only Laravel-mediated operations and is best-effort; it does not cover the Admin web client's direct Supabase RPC calls.

Do not add a client-side denial logger or claim that the current feed captures every blocked attempt. A future implementation needs a trusted failure-recording path outside the failed transaction (for example, a carefully scoped server-side gateway or a verified provider log source), with bounded metadata, rate limiting, correlation IDs, and no request bodies or credentials.

### 3.6 Retention and integrity

The reviewed migrations establish append-only or restricted write paths for several sources, but they do not establish one complete retention policy across all sources. Before broad rollout, define:
- retention period per source and purpose;
- who may investigate and export each category;
- whether exports are logged;
- backup/restore expectations for audit records;
- how deletion requests and legal retention obligations are handled;
- how logging failures and source outages are detected.

Do not promise immutable or tamper-proof storage solely because the UI is read-only or client grants are revoked. Database owners/service-role processes remain privileged; stronger integrity controls need a separately reviewed threat model.

## 4. Implementation plan — only necessary work

### Phase A — Read-only source inventory and deployment verification

1. Verify deployed migration versions and actual table/function grants in the intended Supabase project before relying on branch migrations.
2. Verify Auth Audit Logs availability and configuration without changing settings.
3. Confirm source retention, RLS, and writer behavior for each source.
4. Record which event sources are available in the deployed environment.

**Gate:** No new UI source is enabled until its deployed writer, reader authorization, and semantics are confirmed.

### Phase B — Safe Admin event read layer

1. Keep `admin_audit_logs` and its existing TOTP/AAL2 protections.
2. Add a bounded, server-side Admin investigation RPC/view that reads only approved columns from each approved source.
3. Require the same current Admin + AAL2 + TOTP checks for every browser-callable entry point.
4. Use a fixed source allowlist and validated filters; never accept arbitrary table names, SQL fragments, or caller-supplied actor identity.
5. Use stable event IDs composed from source + source-row ID; retain source timestamps and original source identifiers.
6. Use server-side pagination, date bounds, actor/target/incident filters, and a bounded maximum page size.
7. Avoid broadening direct table grants. Preserve source-specific RLS and use carefully scoped SECURITY DEFINER wrappers with pinned search paths where necessary.

**Initial sources:** administrative role changes, Supabase Auth Audit Logs, incident status history, responder assignment history, dispatcher claimability history, and sanitized requester edit events. These have existing event writers and concrete investigation value. The requester-edit reader exposes changed-field names only, never the underlying before/after values.

### Phase C — Audit Logs UI

1. Keep one dedicated Audit Logs page.
2. Add category/source filtering for the approved event sources.
3. Keep global search, date range, actor, target, incident, and outcome filters server-side.
4. Render source-specific event summaries and details; do not invent old/new values.
5. Show actor attribution as human, system, or unknown according to the source.
6. Show explicit coverage status and source availability; an unavailable source must not appear as “no events.”
7. Retain loading, empty, error, stale, and pagination recovery states.
8. Keep the existing page responsive and keyboard accessible.

### Phase D — Additional security sources, only when ready

- Authentication: integrate provider events only after confirming a supported, configured source and a protected read path.
- Application authorization denials: add durable records only at trusted server/database enforcement points; avoid client-claimed denial events.
- Device security: wait for actual trusted server-side verification and event writers.
- API telemetry: optionally add a safe Admin diagnostics projection; label its Laravel-only coverage and best-effort write limitations.
- FCM delivery: optional operations diagnostics; distinguish claimed/sent/failed provider outcomes from actual device receipt.

### Phase E — Governance and verification

1. Define source-specific retention and export controls.
2. Restrict read access and log audit exports/access where appropriate.
3. Add source-level tests for actor attribution, event correctness, no direct writes, RLS/grants, TOTP/AAL2, filtering, pagination, and sensitive-data exclusion.
4. Verify migration replay and the deployed schema before release.
5. Keep production migration application, Supabase Auth settings, Render configuration, and secrets as separate approved deployment actions.

## 5. Explicitly out of scope

- Replacing all event tables with one giant generic table.
- Restoring Dispatcher web pages or creating a dispatch console.
- Exposing raw request-edit snapshots, coordinates, chat, attachments, or tokens.
- Enabling Auth Audit Log Postgres storage without approval.
- Creating a new device-security event feed before its trusted backend exists.
- Building a monitoring product for every provider event.
- Introducing paid logging/monitoring services.
- Applying migrations to production, changing Supabase settings, or changing Render secrets as part of this source review.

## 6. Readiness decision

**Implemented in the current PR branch (migration source not applied to production):** a protected, bounded Admin investigation read layer and UI integration for `admin_audit_logs`, `auth.audit_log_entries`, `incident_status_logs`, `incident_assignment_events`, `incident_claimability_logs`, and sanitized `incident_request_edit_logs`. The reader requires Admin + AAL2 + TOTP, preserves source identity, and does not return raw Auth or request-edit payloads.

**Not fully covered yet:** denied privileged RPC attempts, direct-client API/Storage operations, and device-security events. The Auth Audit Logs table exists but currently has zero rows and the project-level Postgres-storage setting remains unverified. Do not claim these event classes are comprehensively captured.

**Deferred:** `api_operation_logs` and `fcm_delivery_attempts` remain operational diagnostics rather than user/admin business actions; add a separate, explicitly labeled System activity view only if administrators need provider troubleshooting. `device_security_events` remains unavailable until trusted server-side writers exist.

No migrations were applied, no production settings changed, and no tests/builds/deployments were run during this source inventory.


## 7. Implementation added to PR #338

The following source changes have now been committed to the existing PR branch:

- Added `api_admin_list_investigation_events`, a bounded, server-side union reader for the six approved event sources. It validates category, pagination, search length, date range, actor/target IDs, and incident ID.
- Reuses the existing `private.tacresq_require_admin_mfa()` helper. The public RPC revokes default/public execution and grants execution only to authenticated callers; every call enforces current Admin role, AAL2, and TOTP.
- Adds a restrictive policy to `incident_assignment_events` so Admin direct table reads also require TOTP/AAL2. The existing dispatcher read path remains allowed.
- Adds source-local descending timestamp indexes for incident status, assignment, claimability, and requester-edit history to support bounded timeline queries without removing existing indexes.
- Includes dispatcher claimability changes and sanitized requester-edit events. For requester edits, only the changed-field names and edit number are returned; location and narrative snapshots remain private.
- Keeps incident status, assignment, claimability, and requester-edit histories in their original tables. Request-edit details are reduced to changed-field names; raw before/after snapshots are not returned.
- Adds Supabase Auth Audit Logs using only the event action and a linked profile label. The raw Auth payload, IP address, and user-agent data are not returned.
- Expands the dedicated Audit Logs page with category, actor, target, incident, and Manila date filters, source attribution, event details, and server-side pagination.
- Adds a static source-contract test. It has **not** been executed.

### Deployment dependency

The connected Supabase database does not yet contain the new investigation reader migration. The required Admin TOTP helper and the six event-source relations are already present in the deployed schema. The updated Audit Logs page depends on `api_admin_list_investigation_events`, not the older `api_admin_list_audit_logs_page` RPC. When deploying the PR, review and apply the pending migration chain in timestamp order; do not apply migrations individually out of order. No migration has been applied to the connected project.

The connected database currently reports zero rows in `auth.audit_log_entries`; the project-level Auth Audit Logs Postgres-storage toggle was not exposed by the available project metadata. Verify Authentication → Audit Logs in Supabase Dashboard before expecting provider events in the feed.

No test, build, security scan, deployment, or production migration was run as part of these commits.


## 8. 2026-11-27 follow-up — session revocation, live telemetry, and correlation gaps

This section supersedes the older point-in-time statements above where newer evidence is available. It records the current PR branch and a read-only inspection of the connected project. It does not authorize production deployment or changes.

### 8.1 Current PR #370 session-revocation implementation

The branch now contains `20261127040000_admin_sensitive_session_revocation_guard.sql`.

- `private.tacresq_current_auth_session_valid()` checks that the verified JWT `session_id` identifies an `auth.sessions` row owned by `auth.uid()`. It takes a `FOR SHARE` row lock so a concurrent Auth sign-out/session deletion cannot race through a protected Admin RPC.
- `private.tacresq_current_auth_session_present_read()` is the non-locking `STABLE` counterpart for restrictive RLS read policies. It avoids adding row locks to read-only queries.
- The shared Admin MFA guard now requires a current Auth session in addition to active Admin status, AAL2, and TOTP.
- Direct Admin reads of `admin_audit_logs` and the Admin branch of `incident_assignment_events` require the session-presence check. The dispatcher branch remains independent of Admin MFA/session claims.
- The row-locking helper is internal-only. The read-only helper is executable by authenticated callers for RLS evaluation; neither helper accepts a caller-supplied user/session identity.

**Exact-head verification:** commit `dce92f5ded6649e82e5f84a0f3a38cf12dbbfdb1` completed all eight PR workflows successfully: TACResQ CI, PostgreSQL 17 RPC grant contract, Security/CodeQL, QA, Frontend Review, read-only profile verification, Automated Code Review, and Gradle dependency submission. The local Supabase job itself also completed successfully.

The disposable local Auth → PostgREST test reported 13 passing assertions, including:
- Auth-issued JWTs preserve their own `auth.uid()` and `session_id`.
- A validly signed JWT carrying another user's session ID fails the database ownership check.
- 24 concurrent requests retain caller isolation.
- Sign-out deletes the matching `auth.sessions` row.
- The old, unexpired access JWT remains accepted by ordinary PostgREST JWT verification but fails both database session predicates after sign-out.
- The revoked refresh token is rejected.
- The non-locking predicate succeeds through a PostgREST GET/read-only transaction.

This proves the sign-out revocation boundary. It does **not** make an already-issued access JWT cryptographically invalid, and row presence alone does not evaluate every Auth inactivity/max-lifetime policy.

PR #370 remains open and unmerged. These migrations are branch-only; no hosted Supabase migration/ACL change, Render deployment, or production write was performed.

### 8.2 Read-only live telemetry snapshot

A read-only database count and unified-log inspection was performed against the connected project on 2026-11-27. Counts are point-in-time and do not establish that a writer is correctly wired.

| Source | Total rows | Created/occurred in last 24 hours |
|---|---:|---:|
| `admin_audit_logs` | 1 | 0 |
| `api_operation_logs` | 0 | 0 |
| `app_action_audit_logs` | 0 | 0 |
| `device_security_events` | 0 | 0 |
| `incident_assignment_events` | 0 | 0 |
| `incident_claimability_logs` | 0 | 0 |
| `incident_request_edit_logs` | 1 | 0 |
| `incident_status_logs` | 2 | 0 |
| `fcm_delivery_attempts` | 0 | — |
| `auth.audit_log_entries` | 0 | 0 |
| `auth.sessions` | 15 | 0 |

Additional observations from the same 24-hour window:

- `api_admin_list_investigation_events` received 10 requests, all HTTP 200. No request to `api_admin_list_auth_sessions_page` was observed in that window. This is a usage observation, not proof that the session RPC is broken.
- Supabase Auth service logs recorded login and token-revocation activity. Six `token_revoked` events appeared in the Auth log stream. Gateway log attributes include request/trace IDs and the JWT session ID, but these provider logs are not being ingested into the database-backed Admin investigation sources.
- The database table `auth.audit_log_entries` remains empty even though provider Auth logs contain events. The project-level Auth Audit Logs Postgres-storage setting is still unverified; do not assume the table is a complete provider audit source.
- The 12 PostgreSQL ERROR-level log entries observed in this window were tagged with the Supabase management API application, not the TACResQ application. They are excluded from the application's runtime error count. The Admin investigation RPC requests themselves returned 200.
- One unrelated REST-root request was rejected with an invalid API-key-type 401. It was not an Admin investigation/session RPC failure.

The live schema confirms a structural correlation gap:

| Source | Existing identifier fields | Missing link |
|---|---|---|
| `api_operation_logs` | `correlation_id`, `provider_request_id`, actor Auth UUID | No `auth_session_id` |
| `app_action_audit_logs` | `correlation_id`, actor profile ID | No `auth_session_id` |
| `device_security_events` | `auth_session_id` | No `correlation_id`; no trusted writer or live rows |
| `admin_audit_logs` | Source row ID, actor/target profile IDs | No `correlation_id` or `auth_session_id` |
| `incident_assignment_events` | Source row ID, actor/target profile IDs, timestamp | No `correlation_id` or `auth_session_id` |
| `auth.sessions` | Auth session ID and owner | No request correlation ID |
| `auth.audit_log_entries` | Provider audit row/payload when populated | Currently zero rows; no usable session join demonstrated |

### 8.3 Why session and security alerts are not yet correlated

The current UI has separate concepts, not a shared event graph:

- `AdminAccountSessions` displays session metadata but does not show a session-scoped activity timeline.
- `AdminSecurityStatus` shows current MFA/session posture and a recent authentication-events preview; it is not a device-security telemetry reader.
- `AdminInvestigationEvent` has a source event ID and source details, but no first-class `correlation_id` or `auth_session_id` fields/filters.
- The Laravel request middleware creates a request correlation ID and the Laravel Supabase bridge can propagate it. Direct Android and Admin-web Supabase calls bypass that Laravel middleware.
- Correlation IDs exist on the API-operation and app-action schemas, but both live tables currently have zero rows. The device-security table has an Auth session reference but no correlation ID and no trusted event writer.
- Supabase's unified provider logs expose request/trace IDs and session IDs, but the Admin database RPC cannot query that log stream. The database-backed Auth audit table is empty.

Therefore the present issue is not merely a missing UI column. The durable event sources are mostly empty, and the sources that do have identifiers use different keys. The UI must not imply that a blank feed means no security activity occurred.

### 8.4 Correct correlation model

Keep these identifiers distinct:

1. **Event ID** — identifies one source row.
2. **Correlation ID** — identifies one request/operation across trusted components.
3. **Auth session ID** — identifies the verified Supabase Auth session that performed an operation.
4. **Provider request ID** — identifies the provider-side request; it is not a substitute for the application correlation ID.

Recommended follow-up work:

- Add `auth_session_id` to authenticated event writers and derive it inside the database from the verified JWT session claim. Never accept a client-supplied user/session ID as authoritative.
- Generate one correlation ID at a trusted ingress and propagate it to all records created by that operation. Laravel-mediated calls should retain the middleware-generated ID. Direct Supabase RPCs should generate a server-side ID when no trusted request context exists and return it to the caller.
- Extend the unified investigation RPC and TypeScript event model with first-class nullable `correlation_id` and `auth_session_id` fields, plus exact-match filters. A source that cannot provide an identifier must return null rather than a guessed value.
- Add a session-scoped related-events view to the account-session UI. Show “no linked events” separately from “source unavailable” or “writer not configured.”
- Keep device-security events unavailable until a trusted verification/writer path exists. Do not grant client INSERT or create alerts from client-claimed device/session identifiers.
- Do not correlate by user and timestamp alone. If an event has no shared key, label it as a possible temporal association, never a confirmed link.
- Verify the Auth Audit Logs storage setting and supported provider access method before promising provider authentication events in the Admin feed. Do not put management API credentials in the browser or add a paid logging service.

### 8.5 Acceptance tests for the correlation follow-up

- A trusted authenticated operation records the exact JWT-derived session ID and one correlation ID across all records it creates.
- A forged body/header session ID cannot change the stored Auth session ID.
- Two sessions for the same user remain distinguishable.
- Multiple requests within one session have different correlation IDs but the same session ID.
- A provider request ID remains separately attributable.
- Events without a verified Auth session (system/worker events) retain a null session ID and an explicit system actor.
- The Admin feed filters by exact session/correlation IDs and preserves source-specific event semantics.
- Empty, unavailable, and unconfigured sources render distinct UI states.
- Device-security writes remain denied to browser/mobile clients until the trusted writer and verifier are implemented.

No live data, Auth settings, ACLs, migrations, or deployments were changed during this follow-up.
