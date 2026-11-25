# TACResQ Security and Reliability Control Matrix

**Review date:** 2026-11-17  
**Scope:** Android GPS/location, incident lifecycle, Supabase authorization, Laravel/dashboard boundaries, auditability, and FCM.

This is an engineering alignment review, not a certification, penetration-test report, or statement that TACResQ is ready for real emergency operations.

## Evidence rules

- **Repository fact:** confirmed in current source, migration, or test.
- **Live fact:** read from connected Supabase on 2026-11-17.
- **Pending:** code exists but is not applied to the live database or is awaiting PR review.
- **Not verified:** runtime/device evidence has not been collected.
- Successful CI proves only the jobs actually run.

## Control matrix

| Area | Public practice | TACResQ evidence | Gap / required control |
|---|---|---|---|
| API object/function authorization | OWASP API Security Top 10 2023: API1 Broken Object Level Authorization and API5 Broken Function Level Authorization. https://owasp.org/API-Security/editions/2023/en/0x00-header/ | RPCs enforce caller role/ownership in SQL. Tests include tools/test_rpc_grants.py, tools/test_location_rpc_security.sql, and tools/test_notification_rpc_security.sql. | Eighteen authenticated-callable SECURITY DEFINER functions were reported by the prior advisor review. This count is not proof of vulnerability. Inventory every signature, owner, search_path, EXECUTE grant, role check, ownership check, exposed fields, and negative test. |
| Privileged functions | Supabase recommends SECURITY INVOKER where possible; SECURITY DEFINER needs a controlled search_path, schema-qualified references, and restricted EXECUTE. https://supabase.com/docs/guides/database/functions | api_units() is SECURITY DEFINER and schema-qualifies public/auth references. Main contains migration 20261122050807_pin_api_units_search_path.sql setting search_path=pg_catalog. | Live inspection on 2026-11-17 still showed search_path=pg_catalog, public, auth. The migration is in the repository but absent from the live migration ledger. Do not create a duplicate migration. Keep it pending until the authorized migration process; test exact proconfig and unchanged grants in isolated PostgreSQL. |
| Grants | OWASP API5 and PostgreSQL function/table privilege controls. https://www.postgresql.org/docs/current/sql-grant.html | PR #319 removes authenticated incident table DML and dynamically revokes column-level INSERT/UPDATE grants. PR #321 restricts the unbounded legacy Admin user-list RPC. | Table-level REVOKE does not remove separately granted column privileges. Inspect table, column, sequence, and function ACLs separately. Negative-test direct DML, legacy RPC denial, and valid replacement RPC calls. |
| Incident concurrency | PostgreSQL explicit row locking. https://www.postgresql.org/docs/current/explicit-locking.html | PR #317 serializes field claims and enforces one active assignment per field member. PR #319 serializes dispatcher claimability with incident lifecycle. | Static source contracts are not two-session concurrency tests. Add independent-session tests for same-incident claims, cross-incident double claims, capacity races, claimability vs cancellation/closure, and role revocation vs claim. Assert final database invariants. |
| Location permission/capture | Android location permissions and fused location APIs. https://developer.android.com/develop/sensors-and-location/location/permissions and https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient | LocationProvider.kt uses FusedLocationProviderClient. Requester capture uses getCurrentLocation, bounded timeout, zero max update age, validity checks, and no lastLocation fallback. Responder updates use coarse granularity in an explicitly started foreground service. GPS_LOCATION_NOTES.md documents consent and flow separation. | Source proves the API used, not which physical sensors contributed to a fused fix. Test approximate/precise permission, denial/revocation, disabled services, stale/null fix, timeout, lifecycle cancellation, and manual fallback on supported devices. Do not add background permission merely to improve freshness. |
| Location provenance | Android fused-location documentation and OWASP logging/data-minimization guidance. https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html | GeoFix in Models.kt stores coordinates, accuracy, capture time, elapsed time, permission precision, and manual-selection flag. CreateIncidentV4Rpc sends location source (gps/manual/none), not a provider identifier. incidents stores location_source; location_logs stores coordinates, accuracy, and timestamps. | No actual device provider identifier/version or map/geocoding provider is persisted. A fused API label must not be misrepresented as GNSS/Wi-Fi/cellular provenance. Trace every create/edit/responder-write path first. Add only validated, allowlisted metadata that the client can actually provide; describe it as client-reported. |
| Audit logging | OWASP ASVS 5.0 logging/error handling and OWASP Logging Cheat Sheet. https://owasp.org/www-project-application-security-verification-standard/ and https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html | PR #316 adds metadata-only api_operation_logs and correlation IDs. Its contract excludes bodies, credentials, coordinates, narratives, URLs, and contact details. | Direct Android/dashboard Supabase calls bypass Laravel's audit sink. If the server secret is absent, API audit writes are skipped. Define retention/access and state coverage boundaries; do not claim full API tracing. |
| FCM | Firebase says normal-priority messages can be delayed during Doze; FCM is not a guaranteed life-critical delivery channel. https://firebase.google.com/docs/cloud-messaging/android/message-priority and https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-priority | Persistent in-app notifications and a delivery ledger exist. Project research records FCM as best effort; production Cron dispatch is not active per the latest handoff. | FCM acceptance is not proof of device receipt/display. Keep the in-app inbox authoritative. Test offline/reconnect, Doze, token rotation, logout/account switch, permission/channel disabled, duplicate delivery, and expiry. Reserve high priority for urgent visible alerts and verify actual display. |
| Offline/retry | Android offline-first architecture and WorkManager guidance. https://developer.android.com/topic/architecture/data-layer/offline-first and https://developer.android.com/develop/background-work/background-tasks/persistent | PR #312 adds encrypted account-bound text outbox persistence and idempotent retry. | Image retry and emergency SOS retry are separate. Deferrable background work is not guaranteed immediate dispatch. Never show an unconfirmed SOS as received. |
| Route preview | Geoapify Routing API and OSRM API docs. https://apidocs.geoapify.com/docs/routing/ and https://project-osrm.org/docs/v5.24.0/api/ | Backend source defaults to Geoapify when enabled; private OSRM is explicit. Backend and Android route preview default to disabled. | Route previews disclose origin/destination coordinates to a provider and cannot establish passability, flood status, closures, emergency access, or live traffic. Keep keys server-side, preview disabled until reviewed, and avoid automatic provider failover that would disclose coordinates to another provider. |
| Database performance | Supabase RLS performance guide and PostgreSQL FK indexing. https://supabase.com/docs/guides/database/postgres/row-level-security and https://www.postgresql.org/docs/current/ddl-constraints.html | Prior live advisor review identified four missing FK indexes and six RLS initplan warnings. PR #316 includes the four FK indexes. | Tiny live tables do not establish measured speedups or justify dropping unused indexes. Recheck advisors after approved migrations; benchmark representative query/write patterns. Apply (select auth.uid()) only when row-independent and semantics remain identical. |

## Verified PR and live-database snapshot

Checked on 2026-11-17:

- PRs #316, #317, #318, #319, #320, and #321 were open, unmerged, and reported mergeable.
- Exact PR heads inspected:
  - #316: 21e8c5eb3c5013256aa9063ae3736c15c20d057e
  - #317: 5ba1727f760aeb2a22f79198e2048db727af69b7
  - #318: 1276b6b22473982c7781dbcfac654ff541e6f917
  - #319: 02c1e7bb05b6965adae01b811b708a457264f63f
  - #320: 21495bfbe7d93c87850ab9151b6cf156c1081d8f
  - #321: f44af2d88a8c822ab911612352a9cf65a114137a
- For each exact head, the returned workflow list showed TACResQ CI, TACResQ Security, RPC grant contract, and TACResQ Frontend Review completed successfully. #316 also showed TACResQ Render live smoke completed successfully. This does not prove physical-device behavior, live migration state, or overall readiness.
- Live Supabase migration ledger ended at 20261116042927_responder_incident_claiming. It did not contain 20261122050807_pin_api_units_search_path.sql or later PR migrations. No live migration was applied during this review.
- Read-only inspection of live public.api_units() confirmed SECURITY DEFINER and the older search_path=pg_catalog, public, auth. The repository migration already narrows it to pg_catalog. This is deployment drift, not a reason to create a duplicate migration.

## Prioritized follow-up

1. Review the existing PRs; do not duplicate their changes.
2. Add true two-session PostgreSQL concurrency tests where missing.
3. Keep api_units() search-path migration pending until the authorized migration process; verify function config and grants afterward.
4. Trace Android-to-RPC location payloads before proposing provider metadata.
5. Define audit retention and coverage, especially direct client-to-Supabase operations.
6. Keep FCM supplementary and verify end-to-end behavior on devices before making reliability claims.

## References

- OWASP API Security Top 10 2023: https://owasp.org/API-Security/editions/2023/en/0x00-header/
- OWASP ASVS: https://owasp.org/www-project-application-security-verification-standard/
- OWASP Logging Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- OWASP MASVS: https://mas.owasp.org/MASVS/
- Android location permissions: https://developer.android.com/develop/sensors-and-location/location/permissions
- FusedLocationProviderClient: https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient
- Supabase database functions: https://supabase.com/docs/guides/database/functions
- Supabase RLS performance: https://supabase.com/docs/guides/database/postgres/row-level-security
- PostgreSQL explicit locking: https://www.postgresql.org/docs/current/explicit-locking.html
- PostgreSQL constraints and foreign keys: https://www.postgresql.org/docs/current/ddl-constraints.html
- Firebase Android message priority: https://firebase.google.com/docs/cloud-messaging/android/message-priority
- Firebase message priority guidance: https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-priority


## Concurrency evidence update — 2026-11-17

- PR #317 now includes a genuine two-session PostgreSQL 16 test. Its latest-head clean migration-replay job passed and emitted both expected assertions: one claim succeeded, the competing claim returned `UNIT_BUSY`, and the final database state contained one membership and one assigned incident.
- PR #319 now includes a genuine two-session dispatcher-toggle/requester-cancellation race test. Its latest-head migration-replay job is still running; do not count this test as passed until that job completes and its output is inspected.
- These tests use synthetic fixtures on disposable CI PostgreSQL. Neither test runs against live Supabase.


## Final concurrency CI snapshot — 2026-11-17

- PR #317 head `84a95488872d4808a837bd7d03646b1290de8ab2`: TACResQ CI completed successfully, including clean PostgreSQL 16 migration replay and the two-session cross-incident claim test; RPC grant contract and Frontend Review also passed. TACResQ Security/CodeQL was still running at the last poll.
- PR #319 head `b21f8cd7d9697b7c358a25e09e342d3470950248`: clean PostgreSQL 16 migration replay completed successfully, including the two-session dispatcher-toggle/requester-cancellation race. RPC grant contract and Frontend Review passed; the full TACResQ CI and Security workflows were still running at the last poll.
- Neither PR is merged, and neither migration has been applied to live Supabase.


## Supabase advisor and function-ACL recheck — 2026-11-17

This section supersedes the earlier advisor counts and the older workflow-pending statements above where they conflict.

### Live advisor results

Read-only Supabase advisor results on 2026-11-17:

- **Health:** no findings.
- **Security:** five RLS-enabled tables without policies; 18 authenticated-callable SECURITY DEFINER functions in the exposed `public` schema; leaked-password protection disabled.
- **Performance:** four unindexed foreign keys; six RLS initplan warnings; eight unused-index candidates.
- The five no-policy tables are `fcm_delivery_attempts`, `incident_messages`, `incident_request_edit_logs`, `incident_submission_keys`, and `user_notifications`. The advisor finding alone does not establish exposure. Verify table/schema grants and intended access paths before adding policies; a no-policy RLS table can intentionally deny all direct client access.
- The eight unused-index notices are not grounds for removal. Current table sizes and workload are insufficient to establish that the indexes are unnecessary.

### SECURITY DEFINER ACL and search-path inspection

A read-only catalog query inspected SECURITY DEFINER functions in `public` and `private`, including owners, function settings, and expanded EXECUTE ACLs.

- No inspected SECURITY DEFINER function had EXECUTE granted to `PUBLIC`.
- The 18 public functions callable by `authenticated` match the security advisor warning count. This is an intentional RPC surface only if each function independently enforces caller authentication, role, ownership, and input constraints; the advisor warning is not itself proof of a vulnerability.
- `public.api_units()` remains live with `search_path=pg_catalog, public, auth`; the existing repository migration to narrow it remains unapplied.
- `private.tacresq_is_incident_assignee(bigint)` is SECURITY DEFINER and live with `search_path=pg_catalog, public`. Its current SQL body schema-qualifies the tables it reads and explicitly qualifies `auth.uid()`; no exploit was confirmed. The extra `public` path entry is unnecessary and is a defense-in-depth hardening candidate. Review the repository migration history and dependent policies before preparing a forward-only change.
- Other inspected SECURITY DEFINER functions used `search_path=pg_catalog`.
- This catalog review is not a complete semantic audit of all function bodies. Each callable RPC still requires function-specific authorization and negative-test review.

### Exact-head CI status recheck

At the latest poll on 2026-11-17:

- PR #317 head `84a95488872d4808a837bd7d03646b1290de8ab2`: TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully.
- PR #319 head `b21f8cd7d9697b7c358a25e09e342d3470950248`: TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully.
- PR #322 head `b0e1313c288bf43a5ed9d754cad8ecf7cc033dd7`: TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully.
- All three PRs remained open and unmerged at this poll. Passing workflows do not prove the migrations have been applied to live Supabase or that device/runtime checks passed.


### RLS no-policy table exposure check

The five advisor-flagged tables were checked directly in PostgreSQL catalogs and with `has_table_privilege`:

- All five have RLS enabled and zero policies.
- `anon` has no SELECT or INSERT privilege on any of them.
- `authenticated` has no SELECT, INSERT, UPDATE, or DELETE privilege on any of them.
- Therefore, the advisor warning is currently a deny-by-default configuration, not evidence that these tables are directly exposed to anon/authenticated through the Data API. Do not add broad policies merely to silence the linter. Revisit only if a supported client access path is intentionally introduced; prefer narrow RPCs and explicit authorization.


## Authenticated SECURITY DEFINER RPC review — 2026-11-17

A read-only review examined all 18 public SECURITY DEFINER functions currently executable by `authenticated`, plus the private chat authorization helper functions.

### Reviewed controls that are present

- Admin audit/user-list endpoints check the caller's profile role is `admin`; audit page size is capped at 200 and paginated user search is capped at 100 rows with bounded offset/query input.
- Requester cancellation and editing bind incident ownership to the authenticated profile and lock the requester/incident rows.
- Incident creation requires requester role, validates the incident type, location metadata, and request idempotency key; v4 delegates creation to v3 inside the same transaction.
- Notification read/detail/list operations resolve the authenticated profile and filter by `recipient_user_id`; list size is capped at 100 and a foreign notification ID is indistinguishable from a missing row.
- Chat read/send operations resolve the caller profile, use the private incident-participation helper, bound message length/page size, serialize sends per sender, and use idempotency keys. Image sends additionally enforce path structure, MIME allowlist, size ceiling, and verify the uploaded object metadata.
- FCM token registration requires an authenticated profile and bounds token length.
- Dispatcher unit listing checks dispatcher role. Field claim and claimability functions check role/duty state; their current live concurrency/lifecycle gaps are addressed by pending PRs #317 and #319.

### Outstanding items / risks

- **Legacy unbounded admin user listing:** live `api_admin_list_users()` remains callable by authenticated admins and returns all profiles including contact data without pagination. PR #321 revokes client EXECUTE while retaining the function definition and removes the stale dashboard type declaration. Its exact-head CI, Security/CodeQL, RPC grant contract, and Frontend Review all passed; isolated runtime proof that the legacy RPC is denied while the paginated RPC remains available is still pending. Do not treat this migration as live-applied.
- **Live lifecycle drift:** live `api_claim_incident()` and `api_claimable_incidents()` still predate PR #317's responder exclusivity/claimability fixes; live `api_set_incident_claimable()` still predates PR #319's row-lock and lifecycle hardening. Both PRs are green but unmerged/unapplied.
- **Search-path drift:** `api_units()` still has `search_path=pg_catalog, public, auth`; narrow it to `pg_catalog` in a forward-only migration after dependency review.
- **Potential scale concern:** `api_claimable_incidents()` returns the full claimable queue without pagination. It exposes emergency coordinates/details to eligible on-duty field units, which is an intended operational need, but should be paginated or bounded if expected incident volume grows. Preserve enough results for dispatch/response safety and test queue completeness.
- The private chat helper fails closed for unknown roles and explicitly grants dispatcher, requester-owner, or assigned responder/driver access. Admin is intentionally not included in chat access by default.
- No confirmed cross-account data disclosure or privilege escalation was established by this function review. Static inspection is not a substitute for authenticated negative tests.

### Exact-head workflow evidence

- #317 `84a95488872d4808a837bd7d03646b1290de8ab2`: TACResQ CI, TACResQ Security, RPC grant contract, Frontend Review passed.
- #319 `b21f8cd7d9697b7c358a25e09e342d3470950248`: TACResQ CI, TACResQ Security, RPC grant contract, Frontend Review passed.
- #321 `f44af2d88a8c822ab911612352a9cf65a114137a`: TACResQ CI, TACResQ Security, RPC grant contract, Frontend Review passed.
- #322 `2212cfe71d64a0231d41944c0a4a81f6a4435030`: TACResQ CI, TACResQ Security, RPC grant contract, Frontend Review passed.

All four PRs remain open/unmerged at this audit point. No live migration was applied.


## Location provider provenance trace — 2026-11-17

A source-to-database trace was completed for requester one-time GPS and responder foreground location sharing.

### Confirmed implementation path

- Android capture: `app/src/main/java/com/tacresq/app/location/LocationProvider.kt` calls `FusedLocationProviderClient.getCurrentLocation(...)` for requester fixes and `requestLocationUpdates(...)` for responder foreground fixes.
- Android model: `app/src/main/java/com/tacresq/app/data/model/Models.kt` defines `GeoFix` with coordinates, accuracy, capture time, elapsed realtime, permission precision, and manual-selection flag. It has no provider field.
- Incident submission: `SupabaseTacApi.kt` serializes `CreateIncidentV4Rpc` with `p_location_source` (`gps/manual/none`) but no provider field.
- Responder tracking: `RecordLocationRpc` sends incident ID, coordinates, accuracy, and recorded timestamp only. `ResponderLocationRequest` likewise has no provider field.
- Live database: `incidents` contains `location_source`, `location_recorded_at`, `accuracy_m`, and coordinates; `location_logs` contains coordinates, accuracy, `recorded_at`, `received_at`, and incident/user IDs. `incident_request_edit_logs` stores before/after JSONB with location source and coordinates. None has a dedicated location-provider or map/geocoding-provider column.
- `location_source` therefore means the capture mode (GPS vs manually selected pin), not the underlying location provider.

### Recommendation before implementation

Do not label `Location.getProvider()` as proof of GPS hardware or physical presence. TACResQ uses Google's fused-location API, which can combine multiple signals; the provider name is client metadata and may be absent, generalized (for example, fused), spoofed, or changed by device/OEM behavior.

If provider provenance is required for troubleshooting/audit, add separately named fields with precise semantics:

- `capture_provider`: app capture API/provider identifier, such as `google_fused_location`, `manual_map_selection`, or `unknown`; not a claim about the underlying satellite/network source.
- `capture_method`: keep the existing `location_source` semantics (`gps`, `manual`, `none`) rather than duplicating it under another ambiguous name.
- `map_data_provider`: only when a map/geocoding/routing provider actually supplied the selected coordinate or address; do not populate this with a tile provider merely because a map was displayed.
- Retain the existing capture timestamp, accuracy, and server receipt timestamp. For incident creation, `created_at` is the server-side row creation time; for responder tracking, `location_logs.received_at` is already available.

Treat all client-reported provider values as untrusted diagnostics, validate against a small allowlist, and never use them for authorization or emergency dispatch verification. Avoid adding location coordinates or raw provider metadata to the generic API operation log.

No provider column or API payload change was made in this audit; first confirm which provider distinction the client and operational users actually need.

## Direct-client audit coverage trace — 2026-11-17

A source trace compared the PR #316 Laravel operation-audit design with the active Android and dispatcher-dashboard transports.

### Confirmed transport split

- Android's normal Supabase mode builds Retrofit against the Supabase URL and calls PostgREST RPCs directly. The current SupabaseRpcWire surface includes profile/duty, incident list/create/edit/cancel/status/assignment/claim/claimability, responder location, notifications, FCM-token lifecycle, and chat read/send/image-message RPCs.
- Android also uploads and downloads chat attachments directly through Supabase Storage. Those object operations do not pass through the Laravel API-operation logger.
- The optional Render bridge is a separate transport (SupabaseBridgeRpcWire) and is enabled only when the build/runtime opts into it. Its current allowlist is not the complete RPC surface: claim/claimable, notifications, FCM-token operations, chat operations, and Storage remain direct Supabase paths in the inspected client.
- The dispatcher dashboard initializes @supabase/supabase-js with the browser-safe publishable key and invokes api_me, api_all_incidents, api_units, api_active_responder_locations, api_assign_incident, and the paginated Admin RPCs directly. These calls do not traverse Laravel's /api route middleware.
- The Android OkHttp HttpLoggingInterceptor is configured at BASIC level. This is diagnostic request/response-line logging, not a durable, actor-aware audit record; it does not close the direct-to-Supabase audit gap.
- The Render bridge authenticates the Supabase token, applies a per-user rate limit, and forwards allowlisted RPCs. In the inspected route, it does not create the PR #316 api_operation_logs record itself.

### Audit coverage conclusion

PR #316's Laravel API-operation logger must be described as **Laravel-route coverage**, not complete application/API coverage. It cannot by itself provide a complete audit trail while Android and the dashboard call Supabase RPCs and Storage directly. Supabase platform/API logs may help with infrastructure diagnostics, but should not be treated as a durable business audit trail unless retention, actor attribution, operation naming, and access have been verified for the deployed plan/configuration.

### Safe next design

1. Keep business audit events at the database boundary for security-sensitive state changes, using narrowly scoped transaction-local audit writes or explicit audit inserts inside the relevant RPC transaction. This is the only shared point for direct Android, dashboard, and bridge callers.
2. Use an allowlisted operation name and derive actor identity from auth.uid() on the server. Never trust a client-supplied actor ID, role, outcome, or correlation claim.
3. Record only metadata needed for accountability: operation, actor, target record ID where appropriate, outcome, server timestamp, and a validated request/correlation ID if a trustworthy propagation mechanism is established.
4. Do not record request bodies, access tokens, message text, incident narratives, phone/email, image bytes/paths, or exact coordinates in the generic operation log. Sensitive domain-specific audit tables should have separate purpose, access, and retention rules.
5. For Storage, verify whether upload/download audit events are available and retained in the current Supabase project. If they are not sufficient, add a controlled attachment lifecycle record around the chat RPCs without duplicating image content or exposing signed URLs.
6. Define retention, admin read access, export, and tamper-resistance before calling the result a complete audit system. Do not add broad RLS policies to the five deny-by-default no-policy tables just to expose logs to clients.

No database migration, Render configuration change, client transport switch, or production logging change was made. The next implementation should be a focused database-side audit design with tests proving that direct RPC and bridge callers create equivalent business audit events without leaking sensitive payloads.

## PR #322 exact-head workflow refresh — 2026-11-17

- Latest inspected head: bcefc4702c3f29d6902923983ea44074b605bb4e.
- TACResQ Frontend Review, RPC grant contract, TACResQ CI, and TACResQ Security all completed successfully on that exact commit.
- PR #322 remains open and unmerged. No migration or deployment was performed.

## Claimable-queue query review — 2026-11-17

A read-only live PostgreSQL function-definition inspection confirmed that api_claimable_incidents() currently:

- Requires a valid authenticated Supabase user whose profile role is responder or driver and whose duty_status is available; other callers receive an empty array.
- Returns all incidents where is_claimable=true, status is pending or assigned, and current incident_assignments count is below five.
- Sorts the JSON aggregate oldest-first and includes incident description, exact coordinates, timestamps, claimability, and assigned user IDs.
- Has no page-size argument, cursor, LIMIT, or server-side response-size bound. It builds one JSONB array for the entire eligible queue.
- Uses correlated subqueries per returned incident to count assignments and aggregate assigned user IDs.

This is an operational scalability and data-minimization concern, not a confirmed authorization bypass: the function restricts callers to authenticated on-duty responders/drivers, and these fields are part of the current claim workflow. However, an unbounded response can become large as the queue grows and sends precise incident coordinates/descriptions to every eligible field unit.

Do not add a simple LIMIT that silently hides older or urgent incidents. A safe pagination design needs explicit product semantics: stable ordering, cursor behavior under newly arriving/claimed/cancelled incidents, a maximum page size, and a visible way to load more while preserving emergency priority. Consider whether the app should receive a compact claim-list projection first and fetch full incident details only after a responder opens or claims an item. Any projection must still provide enough information to decide whether to respond safely.

Before implementation, add tests for stable cursor ordering, newly inserted incidents, rows becoming non-claimable between pages, assignment-capacity changes, role/duty changes, and no duplicate/missing eligible incidents across pages. Benchmark the query with representative synthetic queue sizes and inspect indexes for the join/filter path. No live function was changed and no migration was applied.
## Audit migration state and transaction semantics — 2026-11-17

### Live migration state

A fresh read-only migration-ledger query returned the live migration history through `20261116042927_responder_incident_claiming`. It does not contain:

- `20261122050811_api_operation_audit_and_fk_indexes.sql` from PR #316;
- `20261122050813_harden_field_claim_exclusivity.sql` from PR #317;
- `20261122050818_harden_dispatch_claimability_lifecycle.sql` from PR #319;
- `20261122050820_protect_profile_identity_metadata.sql` from PR #320; or
- `20261122050823_restrict_legacy_admin_user_list_rpc.sql` from PR #321.

These migrations are still pending PR review/merge and have not been applied to the live database. In particular, `public.api_operation_logs` is not yet a live table. Do not describe PR #316 audit persistence as active production coverage.

### Distinguish operation telemetry from business audit history

- `api_operation_logs` in PR #316 is server-mediated transport telemetry: provider, operation, source, outcome, HTTP status, duration, correlation ID, and limited error metadata. It is not a complete business event history and its writer depends on a configured backend server secret.
- `admin_audit_logs` is a domain-specific history for privileged role changes.
- `incident_request_edit_logs` captures requester edits and before/after values; those JSONB values contain sensitive location/request data and should remain access-restricted.
- `incident_claimability_logs` in pending PR #319 is a domain-specific record of dispatcher claimability changes and automatic lifecycle clearing.

Do not collapse these tables into one generic audit log or copy their sensitive payloads into `api_operation_logs`. Keep purpose, field minimization, authorization, and retention distinct.

### Transaction semantics that affect the next design

A database audit INSERT executed inside a business RPC transaction commits or rolls back with that transaction. This is desirable for successful state-change history: a committed incident assignment should have its corresponding audit event, and a rolled-back assignment should not leave a false success record. However, a rejected RPC that raises an exception also rolls back any audit row inserted earlier in that same transaction. Therefore an in-transaction audit table cannot, by itself, record every denied/failed attempt.

Recommended coverage model:

1. **Committed business changes:** insert a minimal domain event in the same transaction as the state mutation, deriving actor identity from `auth.uid()` and target IDs from the locked/changed row. Prefer explicit inserts in the existing authorized RPC or a tightly scoped trigger when all supported write paths must be covered.
2. **Rejected/failed attempts:** rely on gateway/Postgres/platform logs or a separate external append-only telemetry sink with verified retention and actor correlation. Do not falsely imply that a transactionally rolled-back row is durable evidence.
3. **Server-mediated calls:** retain PR #316's Laravel telemetry for Render API/worker paths once its migration and configuration are deployed. Keep its coverage label explicit.
4. **Direct client calls:** do not let clients write audit rows directly or submit their own actor identity/outcome. Database functions must derive actor and event outcome server-side.
5. **Sensitive reads:** avoid indiscriminately logging every location/chat read as a domain audit event. If access auditing is required, define which high-risk reads matter, minimize target metadata, and validate event volume and retention first.

This design preserves atomicity for successful business changes without promising complete failed-attempt coverage from a table that shares the business transaction. No schema, migration, live setting, or application behavior was changed during this review.
## Claimable-queue index and plan evidence — 2026-11-17

Read-only catalog and EXPLAIN review of the live claimable-queue query found:

- `incidents` has approximately two estimated rows in the current live statistics and is only 88 kB; `incident_assignments` is 48 kB (its row estimate is unavailable in the returned statistics). This is far too small a workload to justify index changes based on estimates alone.
- Existing indexes include `idx_incidents_claimable` on `(status, is_claimable)` with a partial predicate `is_claimable = true`, and the `incident_assignments` primary key `(incident_id, user_id)` plus `(user_id, incident_id)`.
- The EXPLAIN plan uses `idx_incidents_claimable` for the incident filter and the assignment primary-key index for the correlated count and member lookup. It performs an explicit sort for oldest-first incident ordering and another small sort for assigned-member ordering.
- The current plan is inexpensive at current scale; this does not demonstrate acceptable latency or memory use for a large operational queue.

Recommendation: do not add speculative indexes or remove existing indexes now. If queue pagination is designed later, benchmark with realistic synthetic volume and compare plans for the exact cursor predicate/order. A candidate partial ordered index may be evaluated only if the benchmark shows a material sort/filter bottleneck. Preserve the existing assignment index coverage unless measured evidence supports a change. No indexes or query functions were modified.

## Pre-merge evidence checkpoint — 2026-11-17

This is a snapshot of the seven related open PRs, not a claim that the changes are integrated or deployed. Each PR is tested on its own head and base; those results do not establish that the combined migration/application state is conflict-free.

| PR | Exact head at checkpoint | Independent validation | Remaining gate |
|---|---|---|---|
| #316 API operation audit and FK indexes | `56f3dc1be63b85759fe42a492a62045231ba6484` | Source checks, static security review, RPC contract, migration replay, Render smoke passed on earlier/current stages; synthetic audit connection-failure test added | Re-run Laravel integration after fixing test closure capture; current head's full CI still running |
| #317 Field-claim exclusivity | `cada32949128d4368b95826d8cc16ce00cf6ff9b` | Source checks and PostgreSQL replay/RPC contract passed, including same-responder/different-incident and two-responder/same-incident races | Android build, Android CodeQL, and Laravel integration still running |
| #318 Requester location edit validation | `82adab4d341fcd371194b5164eaeafad9a6d3a55` | Clean PostgreSQL replay includes authenticated owner/other-owner, manual-accuracy, stale/future timestamp, invalid-coordinate, cancelled-request, urgent-correction, and edit-audit checks; remaining exact-head checks passed | Umbrella CodeQL is neutral due configuration-history warnings; language-specific checks passed |
| #319 Dispatcher claimability lifecycle | `24a16ef271d5b94fcb84769505a116872f986ed1` | Exact-head CI, PostgreSQL replay, authenticated incident-write boundary, cancellation race, RPC grants, Android builds, and language-specific CodeQL passed | No live migration or production deployment |
| #320 Profile identity metadata | `e5ca8e7a45b490f9cc8195297ebe0d810865b065` | Authenticated replay rejects profile ID, auth ID, creation timestamp, contact, and field-unit role mutation while preserving supported duty update; other exact-head checks passed | Umbrella CodeQL is neutral due configuration-history warnings |
| #321 Legacy Admin list RPC | `c7df0bb56d6cc7da7e8b46768fdd788f1ea66c42` | Authenticated replay proves legacy unbounded RPC denial, bounded Admin page access, and requester denial; other exact-head checks passed | Umbrella CodeQL is neutral due configuration-history warnings |
| #322 Control matrix documentation | `348fc22d8dd3a114e3e6f4c9daea345ce5e640a7` | Documentation PR's CI, replay, source, security, Android, and language-specific CodeQL checks passed | Umbrella CodeQL is neutral due configuration-history warnings |

### Integration and deployment gates

- All seven PRs were open and unmerged at this checkpoint. No migration in these PRs is considered applied to live Supabase.
- The branches overlap in `tools/test_supabase_migration_replay.sh`, `tools/test_migration_history_alignment.py`, and `.github/workflows/ci.yml`; #316 and #322 also both touch `docs/project-memory/README.md`. GitHub's individual `mergeable` signal does not prove the seven-way result will merge cleanly.
- A provisional integration order is #316 → #317 → #318 → #319 → #320 → #321 → #322, but every step must re-check conflicts, migration order, clean replay, authenticated-role tests, and exact-head workflows after the previous PR is integrated.
- Do not merge a later PR using stale green checks from its pre-integration base. Re-run the full relevant suite on the resulting mainline after each integration.
- Live Supabase migration application, Auth setting changes, Render secret provisioning, and production deployment remain separately approved operator actions. CI success does not authorize them.
- CodeRabbit's OSS manual-review skip is not an approval. The umbrella CodeQL neutral result is disclosed; the language-specific CodeQL checks are the relevant passed results where noted.


## Exact-head review refresh — 2026-11-17 (latest poll)

This snapshot supersedes earlier PR-head and workflow snapshots above.

| PR | Exact head | State / mergeability | Reported PR workflows |
|---|---|---|---|
| #316 API operation audit and FK indexes | `362d99b7a60ddd43a0d4c2b419bc13307b0d2e06` | Open, unmerged, mergeable | TACResQ CI, RPC grant contract, Frontend Review, and Render live smoke succeeded; TACResQ Security was still in progress at the latest poll. |
| #317 Field-claim exclusivity | `cada32949128d4368b95826d8cc16ce00cf6ff9b` | Open, unmerged, mergeable | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review succeeded. |
| #318 Requester location edit validation | `82adab4d341fcd371194b5164eaeafad9a6d3a55` | Open, unmerged, mergeable | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review succeeded. |
| #319 Dispatcher claimability lifecycle | `24a16ef271d5b94fcb84769505a116872f986ed1` | Open, unmerged, mergeable | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review succeeded. |
| #320 Profile identity metadata | `e5ca8e7a45b490f9cc8195297ebe0d810865b065` | Open, unmerged, mergeable | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review succeeded. |
| #321 Legacy Admin list RPC | `c7df0bb56d6cc7da7e8b46768fdd788f1ea66c42` | Open, unmerged, mergeable | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review succeeded. |
| #322 Control matrix documentation | `038ef43f19277840083ad035e678eb601a4587be` | Open, unmerged, mergeable | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review succeeded. |

### Profile editing regression review

PR #320's migration retains the existing protections on `name`, `email`, and `phone`; it adds immutability checks for `id` and `created_at`. The migration's purpose is not to newly disable profile contact editing. The existing guard already rejected contact-field changes through direct `profiles` updates, and the new authenticated-role test preserves that behavior while checking the new metadata restrictions. A separate, trusted profile/contact update flow was not demonstrated by this PR's evidence; if self-service contact editing is a required product feature, it must be implemented through an explicit validated Auth/profile update path rather than by weakening the table guard.

### Current integration gate

The exact-head workflows above are independent PR runs. They do not validate a combined seven-branch migration replay. The PRs still overlap in shared migration-history, replay-script, and CI files; #316 also has a newer head than the previous checkpoint and its Security workflow was not complete at this poll. Keep all PRs open until #316's exact-head Security run finishes, the combined migration history and replay are validated on an integration candidate, and manual device checks cover the changed GPS/incident flows. No PR was merged and no live Supabase migration or production deployment was performed.


## Combined-branch integration review — 2026-11-17

### Defect found and corrected in PR #321

The #321 branch was based on main commit `4405127e96973e1fdb902a0c37d6fbed3c4f3014`, while PRs #316–#320 add migration files and update the migration-history inventory on separate branches. Its original `tools/test_migration_history_alignment.py` did not allowlist the five migrations from #316–#320 and incorrectly classified `20261116042927_responder_incident_claiming.sql` as pending even though the live migration ledger includes it.

Corrected the existing #321 branch in commit `c3f438525930d56133532366b2e35ee00cc81f43`:
- Moved `20261116042927_responder_incident_claiming.sql` into EXPECTED.
- Added the #316–#320 migration filenames to PENDING, preserving the distinction between repository presence and live-applied status.
- Kept #321's own legacy Admin RPC migration pending.
- Did not apply any migration to Supabase.

At the latest poll, #321's Frontend Review had passed; TACResQ CI, Security, and RPC grant contract were still running. The branch reported mergeable after GitHub recalculated metadata. Recheck all exact-head jobs before considering this correction validated.

### Shared-file integration risks

The seven PR patches were compared by changed path. Four paths are shared:
- `tools/test_migration_history_alignment.py`: #316–#321.
- `tools/test_supabase_migration_replay.sh`: #317–#321.
- `.github/workflows/ci.yml`: #317–#321.
- `docs/project-memory/README.md`: #316 and #322.

The replay script changes include multiple additions at the same end-of-file location, plus common Supabase auth-schema grants. The migration-history script has overlapping inventory edits. These are not proven conflicts in every merge order, but they are high-risk overlap points and must be reconciled on the actual resulting branch. A green standalone PR check is not a combined replay.

### Latest exact-head check refresh

- #316 `362d99b7a60ddd43a0d4c2b419bc13307b0d2e06`: TACResQ CI, Security, RPC grant contract, Frontend Review, and Render live smoke all completed successfully.
- #321 `c3f438525930d56133532366b2e35ee00cc81f43`: Frontend Review succeeded; CI, Security, and RPC grant contract were still running at the latest poll.
- #322 `dce8efa79a954b828a94729b6d9d19472ca00afa`: Frontend Review and RPC grant contract succeeded; CI and Security were still running at the latest poll.

No PR was merged. No live database migration, Auth setting change, or production deployment was performed.


## Exact-head and replay-script review refresh — 2026-11-17

This section supersedes earlier status snapshots above where the exact commit or workflow state differs.

### Exact-head workflow results

- PR #321 head `bd28f881739de571b024c9baa90a284ac443a280`: TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully.
- PR #322 head `1181f88b8b647e3a89f465eb4fd29d1a31c60962`: TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully.
- Both PRs remain open and unmerged. These are independent-branch results, not a combined integration run.

### Replay-script comparison

A line-level comparison of `tools/test_supabase_migration_replay.sh` across #317–#321 confirms additive test tails are not identical snapshots:
- #317 runs the field-claim two-connection race.
- #318 adds the authenticated requester location-edit SQL test.
- #319 adds the auth schema/function grants required by authenticated-role tests, the dispatcher claimability/cancellation race, and the authenticated incident-write boundary test.
- #320 adds the authenticated profile-metadata test.
- #321 adds the authenticated Admin user-list boundary test and includes the preceding role-grant setup.

The important integration requirement is to preserve all of these independent test invocations exactly once in the final replay script, with the auth schema/function grants established before tests that execute as `authenticated`. A merge that keeps only one branch's end-of-file tail would silently drop coverage. The final script must also retain all migration replay steps and fail-fast `ON_ERROR_STOP` behavior. This comparison identifies a real test-preservation risk; it does not claim a merge conflict has already occurred or that a combined replay has passed.

### Current gate

Do not merge based solely on the green exact-head workflows. Before merging the group, create or otherwise validate a combined integration candidate that preserves the #317–#321 replay tests and CI steps, runs the complete clean PostgreSQL migration replay, validates migration-history alignment against the live ledger snapshot, and executes the relevant backend/frontend/Android checks. Then rerun the same checks on the actual updated mainline after each approved merge. No live Supabase migration or production deployment is authorized by these checks.


## Migration-order and inventory review — 2026-11-17

### Ordered implementation migrations

The six implementation migrations are uniquely timestamped and sort in a deterministic sequence under the repository's filename-glob replay:

1. #316 — `20261122050811_api_operation_audit_and_fk_indexes.sql`
2. #317 — `20261122050813_harden_field_claim_exclusivity.sql`
3. #318 — `20261122050815_validate_requester_location_edits.sql`
4. #319 — `20261122050818_harden_dispatch_claimability_lifecycle.sql`
5. #320 — `20261122050820_protect_profile_identity_metadata.sql`
6. #321 — `20261122050823_restrict_legacy_admin_user_list_rpc.sql`

Static review found no duplicate migration filename and no obvious forward reference from these new migrations to a later migration. Their principal objects are based on earlier schema/functions: the audit migration adds a new table and indexes existing FK columns; field-claim and location-edit migrations replace existing RPCs; claimability adds a log table, trigger functions, and RPC replacement; profile metadata replaces an existing profile guard; the Admin migration revokes execution on the existing legacy function. This is a source-level dependency assessment, not proof of successful PostgreSQL execution.

### Migration-history inventory

The latest #321 `tools/test_migration_history_alignment.py` contains 42 EXPECTED filenames and 8 PENDING filenames. The sets are disjoint. EXPECTED includes the responder-claim migration confirmed in the live ledger; PENDING includes the two earlier un-applied hardening migrations, the six implementation migrations above, and the #321 legacy Admin RPC migration. The revised assertions require every EXPECTED file to exist and require every repository migration to be either EXPECTED or PENDING. They intentionally do not require every pending sibling migration to exist on each independent PR branch.

### Remaining proof required

- Run the actual replay from a clean disposable PostgreSQL database with all six migration files present.
- Preserve the five distinct PR-specific CI test commands and all five replay-script test invocations across #317–#321.
- Verify migration replay, authenticated-role tests, and RPC grants on the same combined candidate commit.
- After each approved merge, rerun the migration replay and relevant exact-head checks against the updated mainline; do not reuse standalone branch results as integration proof.

No migration was applied to the live Supabase project. No PR was merged.


### Concurrency-test coverage limitations

Review of #317's `tools/test_field_claim_concurrency.sh` confirms it uses two independent authenticated PostgreSQL sessions and checks two valuable invariants: one field unit cannot win claims on two different active incidents, and two different field units can join the same incident. The script deliberately holds transactions for one second after the claim call to widen contention.

Two limitations remain:
- The shell test does not use an explicit database start barrier. The one-second lock hold increases the chance of overlap, but it does not prove both sessions reached the contested operation before the first transaction committed. Treat it as a real concurrency test with a scheduling-determinism limitation, not a fully deterministic race harness.
- It does not exercise dispatcher assignment racing a field claim for the same unit, nor two dispatchers assigning the same unit to different incidents. The migration's incident/profile lock order and active-assignment recheck are designed to serialize those paths, but the specific cross-path races remain unproven by this fixture.

Recommended next test improvement: add synchronized two-session cases for (a) dispatcher assignment versus field claim and (b) dispatcher-versus-dispatcher selection of the same unit, then assert exactly one active incident membership for that unit. Prefer a database-coordinated barrier or explicit readiness handshake over relying only on process launch timing. Do not claim these cases are covered until the fixture runs successfully on a disposable clean replay.

Latest documentation commit workflow state at this review: Frontend Review succeeded; TACResQ CI, TACResQ Security, and RPC grant contract were still running. No merge or live migration was performed.


## Deterministic cross-path concurrency update — 2026-11-17

This section supersedes the earlier statement that PR #317 lacked a database-coordinated start barrier.

PR #317 exact head `c8100543efd73626b765c8c160bb0cfe48dea0a7` adds `tools/test_assignment_cross_path_concurrency.sh` to the clean migration replay and shell lint checks. On disposable PostgreSQL 16, the RPC grant contract job completed successfully and logged both new PASS assertions:

- Field self-claim versus dispatcher assignment for the same responder on different incidents: exactly one assignment committed.
- Two distinct dispatchers assigning the same responder to different incidents: exactly one assignment committed.

The test uses a coordinator transaction holding the shared responder profile row. It verifies both independent authenticated sessions are waiting in the lock queue before the coordinator releases the row. The barrier accounts for PostgreSQL's hard blockers and soft blockers (a session ahead in the wait queue can itself block a later waiter), rather than requiring both workers to be directly blocked by the coordinator PID. The existing two field-claim tests also passed in the same replay.

This proves the tested database paths serialize correctly under these controlled CI scenarios. It does not prove every possible concurrency interleaving, production Supabase behavior, physical-device behavior, or full seven-PR integration. TACResQ CI and TACResQ Security/CodeQL were still running at the latest poll; Frontend Review and RPC grant contract passed.

No PR was merged and no live Supabase migration or production deployment was performed.

**Relevant PostgreSQL 16 references:**
- Explicit row-level locking and transaction-end lock release: https://www.postgresql.org/docs/16/explicit-locking.html
- `pg_blocking_pids()` hard and soft blockers: https://www.postgresql.org/docs/16/functions-info.html
- `pg_stat_activity` wait events: https://www.postgresql.org/docs/16/monitoring-stats.html

## Exact-head PR status refresh — 2026-11-17 02:15 UTC

All seven implementation/documentation PRs #316–#322 remain open and unmerged. The current main base is `4405127e96973e1fdb902a0c37d6fbed3c4f3014`. The following is a per-head snapshot; a passing independent PR does not prove combined migration replay.

| PR | Current head | Exact-head workflow evidence at this refresh |
|---|---|---|
| #316 API operation audit/FK indexes | `362d99b7a60ddd43a0d4c2b419bc13307b0d2e06` | TACResQ CI, TACResQ Security, RPC grant contract, Frontend Review, and Render live smoke passed. |
| #317 Field-claim exclusivity | `d4668734f163fcfc5ecab24bf9e4a44c78638636` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review passed. Clean PostgreSQL 16 replay includes cross-incident claims, same-incident multi-responder join, field-claim-vs-dispatcher, and two-dispatcher races. |
| #318 Requester location edit/provenance | `3dacdc39edcb77f335080581891573c053a4ae39` | Frontend Review and RPC grant contract passed; TACResQ CI and Security were still running at this poll after a documentation-only provider-provenance update. |
| #319 Dispatcher claimability lifecycle | `92dc24e9da6124b3a465fd88d2bb0294e2afe146` | Frontend Review, RPC grant contract, TACResQ CI, and Security are being rerun after adding an explicit database lock barrier to the dispatcher-vs-requester race. Previous head `152a97e484fe5d8f92c7269026e248c96c537a25` passed RPC grant contract including the authenticated responder-status and claimability-audit assertions. |
| #320 Profile identity metadata | `e5ca8e7a45b490f9cc8195297ebe0d810865b065` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review passed. |
| #321 Legacy Admin user-list RPC restriction | `bd28f881739de571b024c9baa90a284ac443a280` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review passed. |
| #322 Security/reliability control matrix | `8ec303248e418a3f75a446586cb5246ea5750804` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review passed. |

### New test and provenance findings

- PR #317's deterministic cross-path test uses a coordinator transaction to hold the shared responder profile row and waits until both authenticated sessions are visible in PostgreSQL's lock queue before release. Its CI replay passed on PR #317 head `c8100543...`; the later `d4668734...` commit only reconciles documentation and passed all four workflows.
- The original PR #317 field-vs-field tests still do not have an explicit start barrier. They remain valid two-session concurrency tests, but scheduling determinism is weaker than the newer cross-path fixture.
- PR #319 now has an explicit coordinator-held incident-row barrier for dispatcher claimability versus requester cancellation. The current head is under validation; do not mark that strengthened fixture passed until the exact-head replay logs show success.
- PR #319's authenticated write-boundary test now also verifies that direct responder incident UPDATE is denied, the legacy status RPC wrapper still advances the assigned responder, and automatic claimability clearing creates one attributable audit event. The RPC grant-contract run passed this test on head `152a97e...`.
- PR #318's provider trace confirms that `location_source` is only `gps/manual/none`; `GeoFix` does not preserve `Location.provider`, and responder `location_logs` has no provider or recording-RPC field. Provider metadata remains a documented follow-up, not an implemented schema contract. Google documents that fused requests may combine sensors and clients cannot select exact sensors; any `fused` label is diagnostic and not proof of GNSS origin.

### Integration gate remains open

The PRs continue to overlap in `.github/workflows/ci.yml`, `tools/test_supabase_migration_replay.sh`, and `tools/test_migration_history_alignment.py`. The combined replay must preserve every distinct SQL/Python/shell regression invocation exactly once, apply all six implementation migrations in timestamp order, and execute authenticated-role tests only after the mock `auth` schema/function grants are established. Do not infer integration readiness from the individual mergeable flags or green PR workflows. No merge or live Supabase migration has been performed.
## Cross-PR SQL fixture namespace audit — 2026-11-17

While reconciling the clean replay test tails, a concrete fixture collision was found that would have surfaced only when independently green PRs were combined:
- PR #317's field-claim test uses synthetic auth user UUIDs ending in `0901`–`0912`; its new cross-path fixture uses `0920`–`0941`.
- PR #319 originally reused `0911`/`0912` in its claimability race, colliding with PR #317's responder fixtures.
- PR #319's authenticated write-boundary test originally used `0143`–`0145`, overlapping PR #320's profile metadata fixture (`0142`/`0143`) and PR #321's Admin fixture (`0145`/`0146`).
- PR #318's requester location test uses `0991`/`0992`.

PR #319's synthetic fixtures were reassigned to a separate range (`0970`, `0972`, `0973`, `0974`, `0975`) and checked against the known test fixtures in PRs #317, #318, #320, #321 and the existing base SQL tests. This removes the identified collisions; it does not replace a combined clean replay. Preserve distinct fixture IDs when reconciling replay scripts, and avoid reusing a test account UUID across scripts unless the test intentionally shares state.

PR #319's deterministic incident-lock barrier and the isolated fixture IDs are under exact-head CI validation. The earlier `152a97e...` RPC-contract run passed the authenticated responder-status/audit test before the fixture namespace change; the current head must pass again before this is considered verified.
## Latest exact-head validation follow-up — 2026-11-17 02:23 UTC

- **PR #318** head `3b9c08f5e498c86620e14a4b8f18ac20027a1303`: TACResQ CI, Security/CodeQL, RPC grant contract, Frontend Review, and automated static review all passed. Physical-device GPS/OEM behavior remains untested.
- **PR #319** head `2cb6aa7d2204fbd4ea2086a53c9d5d0a05126247`: TACResQ CI, Security/CodeQL, RPC grant contract, Frontend Review, and automated static review all passed. The clean replay explicitly logged both authenticated sessions waiting on the same incident lock before release, then confirmed `cancelled:false`. The authenticated write-boundary test also passed with responder status RPC and claimability audit attribution.
- **PR #319 fixture IDs** are now isolated in the `0970`–`0975` range, avoiding the earlier collisions with PR #317's `0901`–`0941`, PR #320's `0142`–`0143`, PR #321's `0145`–`0146`, and PR #318's `0991`–`0992` fixtures.
- **PR #316** has a new exact-head follow-up `c1f5fec2c11366ccb48ed7f62a48a5d892b704fe` that adds metadata-only audit logging for rejected, unallowlisted Laravel bridge RPC attempts. Its exact-head workflows are still running; do not carry forward the earlier all-green status until this new test/code head passes.
- **PR #322** control-matrix documentation head is `a5eb0a401c68980943efe2ddc50ea273b3644f3b`; its CI and Security workflows are still running after the fixture-collision documentation update.

All seven PRs remain open/unmerged. The individual PR heads are mergeable, but the shared replay/CI/history files and the full combined migration candidate remain unvalidated. No merge or live Supabase migration has been performed.
### Required replay-test union when reconciling PR branches

The per-branch `tools/test_supabase_migration_replay.sh` inspection confirms the combined candidate must preserve these invocations exactly once:
- Existing baseline: `test_incident_chat_storage_policies.sql`, `test_notification_rpc_security.sql`, `test_fcm_delivery_queue.sql`, `test_fcm_token_account_switch.sql`, and `test_responder_incident_claiming.sql`.
- PR #317: `test_field_claim_concurrency.sh` and `test_assignment_cross_path_concurrency.sh`.
- PR #318: `test_authenticated_requester_location_edit.sql`.
- PR #319: `test_claimability_cancellation_concurrency.sh` and `test_authenticated_incident_write_boundary.sql`.
- PR #320: `test_authenticated_profile_metadata.sql`.
- PR #321: `test_authenticated_admin_user_list_boundary.sql`.

PR #316 adds Laravel feature tests rather than a new replay SQL fixture. Ensure the integration workflow also retains its Laravel test suite. The replay script on each independent branch contains only its own PR's appended tests; merging branches without reconciling the script would silently omit sibling coverage or create conflicts.
## Exact-head check delta — 2026-11-17 02:29 UTC

- **PR #316** latest docs head `366825dc910e014b838f495d87ecd90cfd96f26e`: code-bearing head `2589af93...` passed TACResQ CI (75 Laravel tests/224 assertions, Android builds, clean migration replay), Security/CodeQL, RPC contract, Frontend Review, Render smoke, and automated review. The latest docs-only head has Frontend Review, Render smoke, RPC contract, and automated review passed; its CI and Security reruns are still in progress.
- **PR #317** head `d4668734...`: all four required workflows passed.
- **PR #318** head `3b9c08f5...`: all four required workflows passed.
- **PR #319** head `2cb6aa7d...`: all four required workflows passed, including Android builds, clean PostgreSQL replay, Laravel/dashboard integration, CodeQL, and the deterministic cancellation lock barrier.
- **PR #320** head `e5ca8e7a...` and **PR #321** head `bd28f881...`: all four required workflows passed.
- **PR #322** latest documentation head `c4593aa1...`: RPC grant contract, Frontend Review, and automated review passed; CI and Security are running.

All seven PRs remain open and individually mergeable. The combined replay candidate remains the outstanding integration gate; no merge or live deployment has been performed.

## Authoritative seven-PR status refresh — 2026-11-17 10:40 PHT

This section supersedes earlier head hashes and workflow-pending statements in the historical snapshots above. Exact current PR heads and completed workflow results were re-read from GitHub.

| PR | Current exact head | Current independent workflow evidence | State |
|---|---|---|---|
| #316 API operation audit and FK indexes | `efe28e42934784c852e9435199ad22c8ed09a88a` | TACResQ CI, TACResQ Security, RPC grant contract, Frontend Review, and Render live smoke all completed successfully | Open, unmerged |
| #317 Field-claim exclusivity | `d4668734f163fcfc5ecab24bf9e4a44c78638636` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully. CI includes clean PostgreSQL 16 replay and four concurrency dimensions. | Open, unmerged |
| #318 Requester location edit validation | `3b9c08f5e498c86620e14a4b8f18ac20027a1303` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully | Open, unmerged |
| #319 Dispatcher claimability lifecycle | `2cb6aa7d2204fbd4ea2086a53c9d5d0a05126247` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully. CI includes the deterministic dispatcher-toggle/requester-cancellation race and authenticated direct-write boundary test. | Open, unmerged |
| #320 Profile identity metadata | `e5ca8e7a45b490f9cc8195297ebe0d810865b065` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully | Open, unmerged |
| #321 Legacy Admin user-list RPC | `bd28f881739de571b024c9baa90a284ac443a280` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully. Clean replay invokes an authenticated SQL test proving legacy RPC denial, bounded Admin pagination access, and requester denial. | Open, unmerged |
| #322 Security/reliability control matrix | `c280fbd58496e159f13c23c96cc38f8d68f0eb9c` | TACResQ CI, TACResQ Security, RPC grant contract, and Frontend Review all completed successfully | Open, unmerged |

### New cross-path concurrency evidence

PR #317 now has four separately asserted PostgreSQL 16 concurrency invariants on its latest code head:

1. One responder racing to claim two different incidents: one succeeds and the other returns `UNIT_BUSY`.
2. Two different responders joining the same incident: both memberships succeed.
3. Field self-claim racing dispatcher assignment for the same responder across two incidents: exactly one operation commits.
4. Two dispatchers assigning the same responder to different incidents: exactly one operation commits.

The last two use a coordinator-held responder profile row and wait until both independent authenticated sessions are observed in PostgreSQL's lock queue before releasing it. The latest exact-head CI run passed the replay and assertions. PR #317's audit report and PR description were updated to match this evidence.

PR #319's dispatcher claimability versus requester cancellation race also passed in the latest exact-head CI replay. The test asserts the final state is `cancelled:false` regardless of which transaction acquires the incident row lock first. Its authenticated write-boundary test verifies direct incident UPDATE is denied while the existing responder status RPC remains functional and writes one attributable claimability transition event.

PR #321's authenticated Admin boundary test is part of clean migration replay. It verifies that even an authenticated admin cannot execute the unbounded legacy RPC, the bounded paginated RPC remains available to an admin, and an ordinary requester cannot call the paginated RPC.

### Integration gate remains open

These are independent branch checks, not a combined merge-candidate result. Before any merge, the six code-bearing PRs (#316–#321) still need one integrated migration replay and cross-PR test run. In particular:

- Replay all migrations in timestamp order from a single combined tree, rather than assuming each branch's replay proves their union.
- Verify each shared `.github/workflows/ci.yml`, `tools/test_supabase_migration_replay.sh`, and `tools/test_migration_history_alignment.py` change appears exactly once and all required test invocations are present.
- Verify #319's direct incident DML revocation is compatible with every shipped Android, dashboard, and Laravel/bridge write path, not just one API source file.
- Verify the #317 and #319 incident/profile lock orders remain compatible across claim, assignment, claimability, role/duty changes, and terminal status transitions.
- Run the complete SQL authenticated-role matrix and all concurrency tests against the same disposable PostgreSQL 16 database after combined replay.
- Re-run all relevant CI/security checks on the combined candidate and record exact commit hashes.

No PR has been merged. No production migration, Supabase data change, Render configuration change, or deployment was performed. This document update itself is documentation-only and does not close the integration gate.


## Integrated review correction — 2026-11-17

The prior sections preserve historical snapshots. The authoritative seven-PR refresh immediately above is the current independent-check snapshot. Additional source inspection confirmed:

- PR #319's direct-write revocation is paired with a clean-replay authenticated-role test: direct requester and responder incident UPDATE is denied, while requester cancellation and the legacy responder status RPC remain usable. The status transition also produces exactly one actor-attributed claimability audit event.
- PR #319's source contract checks the Android Retrofit RPC surface and dashboard Supabase client for direct `incidents` table writes. The currently inspected production Android transport uses RPC endpoints for incident create/edit/status/claim/claimability, and the dashboard uses RPC calls for incident reads and operations. The separate Laravel-owned backend routes operate against their own backend database workflow and are not the Supabase `public.incidents` PostgREST path affected by this migration.
- PR #321's SQL integration test is explicitly invoked by the migration replay script. It executes under `SET ROLE authenticated`: an authenticated admin is denied the legacy unbounded list function, can still use the bounded paginated RPC, and an ordinary requester is denied that paginated RPC.
- PR #317's latest exact head after the audit-document reconciliation is `d4668734f163fcfc5ecab24bf9e4a44c78638636`; all four standard workflows passed on that head. PR #322's previous head is superseded by this documentation update and must be rechecked after the resulting commit.

### Review status versus merge recommendation

The PRs are not yet approved as one integrated release. They contain meaningful security and lifecycle improvements, and their latest independent CI results are green. The current evidence does not justify merging all of them simultaneously without an integrated candidate because:

1. Several branches independently modify the same replay harness, CI workflow, and migration-history inventory.
2. The six SQL migrations have a timestamped order and are expected to compose, but the complete union has not yet been executed as one candidate.
3. Independent CI can miss an interaction where one PR changes a shared function or privilege assumption consumed by another PR's tests.
4. Green automated builds do not establish physical GPS, notification display, FCM delivery, production secrets, or live Supabase migration safety.

**Recommendation:** keep all PRs open until the integrated replay/test gate is run and the exact combined commit is reviewed. Do not merge based only on the green PR-by-PR checks. Do not apply any pending migration to live Supabase as part of this code-review stage.


## Combined PR integration candidate — 2026-11-17

**Temporary review branch:** `integration/review-pr-316-322-20261117`  
**Integrated candidate commit:** `cef63fa713dc3488b76952791742d91ebd0cf4bf`  
**Base:** `main` at `4405127e96973e1fdb902a0c37d6fbed3c4f3014`

The candidate tree was assembled from the current heads of PRs #316–#322. The four shared files were reconciled explicitly rather than taking an arbitrary branch version: CI workflow, migration replay script, migration-history alignment inventory, and project-memory README. The candidate includes all 42 changed paths across the seven PR snapshots and the six implementation migrations in timestamp order.

To validate a non-PR branch, the candidate-only copies of `ci.yml`, `security.yml`, and `rpc-grant-contract.yml` temporarily include `integration/**` in their push filters. This trigger adjustment exists only on the temporary branch; it is not part of any PR and must not be copied into production branches without a separate decision.

### Integrated validation results

All three workflows completed successfully on the exact candidate commit:

| Workflow | Result | Evidence |
|---|---|---|
| TACResQ CI | Success | Source checks, Android builds/tests/lint, clean PostgreSQL replay, Laravel/dashboard integration |
| TACResQ Security | Success | Repository security guardrails, CodeQL Web/GitHub Actions, CodeQL Android |
| RPC grant contract | Success | Anonymous grant checks, clean migration replay, runtime GPS RPC privilege/authorization checks |

Detailed evidence:
- All six migrations applied in timestamp order: API audit/FK indexes, field-claim exclusivity, requester location-edit validation, dispatcher claimability lifecycle, profile identity metadata protection, and legacy Admin RPC restriction.
- Clean PostgreSQL replay completed with the combined migration history and all replay-invoked authenticated SQL/concurrency suites.
- Four assignment concurrency dimensions passed: one responder versus two incidents; two responders joining one incident; field claim versus dispatcher assignment; and two dispatchers assigning one responder. The dispatcher-claimability versus requester-cancellation race also passed.
- The replay logs confirmed the expected losing operations were rejected as unavailable; the final-state assertions passed.
- Laravel/dashboard integration passed **77 PHP tests and 229 assertions**, including `SupabaseServerHeadersTest` and API audit tests.
- Android demo debug and Supabase debug builds, release APK build, JVM tests, instrumentation-test compilation, and debug lint passed. The release-signature verification step was skipped because no signing credentials were configured; no signing secret was introduced.
- Dashboard type-check/build, runtime configuration, and static asset smoke checks passed.
- Security guardrails and both language-specific CodeQL analyses passed.

### Interpretation and limitations

This is the first validation that demonstrates the union of the seven PR snapshots passes the shared clean-database and application integration workflows. It materially reduces the risk that the PRs' overlapping replay/CI changes conflict or that the six migrations fail when replayed together.

It is not a production deployment or a physical-device acceptance test. Android UI instrumentation tests were compiled but not executed; GPS behavior, OEM background restrictions, FCM device delivery, notification presentation, live routing-provider behavior, and physical map UX still require manual/device testing. The prototype-browser workflow is advisory and does not replace the user's manual UI acceptance. The candidate did not run the separate Render live smoke workflow or the PR-only Frontend Review workflow; PR #316's own latest head separately passed Render smoke.

The temporary candidate branch is not a PR and was not merged into `main`. No live Supabase migration, production data change, Render setting, secret, deployment, or notification was changed. All seven PRs remain open and unmerged.

### Merge recommendation after integrated evidence

The code-bearing PRs #316–#321 are now **technically worth merging**, subject to a final human diff review and the remaining manual/device checks relevant to release. Recommended merge order follows migration chronology: #316 → #317 → #318 → #319 → #320 → #321. Merge one at a time, wait for exact-head CI after each base update, and resolve shared CI/replay/history files using the validated integration-candidate versions. Merge #322 documentation after the code PRs or keep it as the release-control record. This is a recommendation, not authorization to merge; no merge was performed.


## Follow-up research fixes — 2026-11-17

Two independent-review findings were translated into source-level contract clarifications and bounded behavior:

- **PR #316 — bounded synchronous audit latency.** The audit writer remains fail-open and synchronous because the repository does not establish a durable queue/worker, retry, and dead-letter lifecycle. Its Supabase HTTP request now uses a 200 ms connection timeout and 250 ms total request timeout. One audit attempt is therefore bounded to approximately 250 ms; an Auth-plus-RPC request can still incur up to approximately 500 ms if both audit writes time out. This is an explicit reliability trade-off, not asynchronous or guaranteed audit delivery. Added p50/p95/p99 verification requirements for slow-success, connection timeout, read timeout, and HTTP 5xx conditions. Commits: `2aecbd15104a65d5b4f39ed791facf283ab7c205` and `a26f90f6fc36110deea82cb2843fae855a4dd17d`.
- **PR #318 — client timestamp trust boundary in the API schema.** Added a PostgreSQL function COMMENT defining `p_location_recorded_at` as client-reported capture/selection time only, not server receipt time, GPS attestation, physical-presence proof, or authorization evidence. Expanded the review/data-dictionary wording to require consumers to preserve that distinction and prohibit using the value for authorization, dispatch eligibility, or presence decisions. Commits: `1f6146dd1228ef04f74a46a25f9435d63906f41f` and `91fd727fb5e54ecefe313ee8b6664b6b5576bd18`.

Research basis:
- Laravel HTTP Client documents that `timeout` bounds response waiting and `connectTimeout` bounds connection establishment; connection exceptions are thrown on timeout. The implementation keeps explicit timeouts and fail-open handling.
- OWASP Logging Cheat Sheet says logging failures must not prevent the application from running and specifically calls for testing slow/disconnected logging dependencies, side effects, and resource exhaustion.
- Supabase API-key documentation distinguishes client-reported values and secret-key handling; secret keys remain server-only and API keys are not user identity.
- Android location APIs expose location accuracy/time as metadata; neither a client timestamp nor a provider label authenticates physical location.

Sources: Laravel HTTP Client (https://laravel.com/framework/docs/http-client); OWASP Logging Cheat Sheet (https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet); Supabase API Keys (https://supabase.com/docs/guides/getting-started/api-keys); Android Location APIs (https://developer.android.com/develop/sensors-and-location/location).

**Validation sequencing:** these are implementation/documentation commits only. Per the project instruction, do not run tests or trigger CI until all remaining implementation commits across the active PR scope are complete. The existing combined-candidate evidence predates these follow-up commits and does not validate them. All relevant exact-head checks must be rerun after implementation is closed.



## Post-follow-up implementation refresh — 2026-11-17

The following PR metadata and source diffs were re-read after the bounded-audit and location-timestamp contract commits. This refresh supersedes earlier head hashes above for PR metadata only; historical CI results remain tied to their recorded exact commits.

| PR | Current head | Current state | Latest source follow-up in this pass |
|---|---|---|---|
| #316 | `a26f90f6fc36110deea82cb2843fae855a4dd17d` | Open, unmerged, mergeable | Audit HTTP connection timeout 200 ms and total timeout 250 ms; documentation records the possible cumulative delay for Auth plus RPC |
| #317 | `d4668734f163fcfc5ecab24bf9e4a44c78638636` | Open, unmerged, mergeable | No new code change; field-claim and dispatcher assignment locking/test contracts re-reviewed |
| #318 | `91fd727fb5e54ecefe313ee8b6664b6b5576bd18` | Open, unmerged, mergeable | SQL function comment and API/data-dictionary wording clarify that location capture time is client-reported only |
| #319 | `2cb6aa7d2204fbd4ea2086a53c9d5d0a05126247` | Open, unmerged, mergeable | No new code change; lifecycle locking, direct-DML boundary, and audit-transition behavior re-reviewed |
| #320 | `e5ca8e7a45b490f9cc8195297ebe0d810865b065` | Open, unmerged, mergeable | No new code change; nested-trigger identity immutability and authenticated negative-test contract re-reviewed |
| #321 | `bd28f881739de571b024c9baa90a284ac443a280` | Open, unmerged, mergeable | No new code change; legacy unbounded Admin RPC revocation and paginated replacement re-reviewed |
| #322 | `8cfcb15326691a291dfb96444e6ac9be72443cf6` | Open, unmerged, mergeable | This refresh is being committed as a documentation-only follow-up |

### Integration and validation status

- The combined candidate `cef63fa713dc3488b76952791742d91ebd0cf4bf` is historical evidence for the snapshots it contained. It predates the two latest implementation commits on #316 and #318 and therefore does **not** validate the current seven-PR heads.
- The independently green workflow claims recorded above must not be carried forward to the new #316/#318 heads without exact-head reruns. No tests or CI were started during this implementation pass, in accordance with the instruction to finish implementation commits first.
- Shared-file integration remains a deliberate merge concern: #316–#321 independently touch some combination of `.github/workflows/ci.yml`, `tools/test_supabase_migration_replay.sh`, and `tools/test_migration_history_alignment.py`. The validated candidate versions preserve the full replay-test union, but the final merge sequence must reapply/retain that union after each base update and rerun checks on the resulting exact head.
- No production migration, Supabase data mutation, Render configuration/deployment, secret change, FCM send, or PR merge occurred.


## Source-level follow-up review — 2026-11-17

This review re-fetched live metadata and the exact current source for PRs #316–#322. All seven remain open, unmerged, and individually reported mergeable. Current heads at review time:

| PR | Head |
|---|---|
| #316 | `a26f90f6fc36110deea82cb2843fae855a4dd17d` |
| #317 | `d4668734f163fcfc5ecab24bf9e4a44c78638636` |
| #318 | `91fd727fb5e54ecefe313ee8b6664b6b5576bd18` |
| #319 | `2cb6aa7d2204fbd4ea2086a53c9d5d0a05126247` |
| #320 | `e5ca8e7a45b490f9cc8195297ebe0d810865b065` |
| #321 | `bd28f881739de571b024c9baa90a284ac443a280` |
| #322 | `e5411d1ec6579d08207663dc1526405b62bc41de` |

### Confirmed review outcomes

- **PR #317 claimable queue:** `api_claimable_incidents()` uses an unbounded `jsonb_agg` across all eligible incidents and returns incident details and coordinates. This is a response-size/scalability concern, not a confirmed authorization defect. No arbitrary cap was added because a cap without a continuation contract can hide eligible emergencies. A safe future change needs deterministic keyset pagination, bounded page size, continuation state, caller updates, and completeness tests. The current unpaginated behavior remains a documented scale risk.
- **PR #320 nested profile updates:** the core migration's incident-status audit trigger performs a nested update to the assigned field unit's `duty_status`. The incident update guard validates caller assignment and allowed lifecycle transitions; transaction failure rolls back nested writes. The PR's immutable identity checks execute at every trigger depth, while only operational role/duty validation is skipped for the nested system transition. No arbitrary user-controlled nested role mutation was found in the inspected trigger path. This remains subject to complete migration replay and authenticated SQL verification; it is not a claim that every live trigger has been exhaustively enumerated.
- **PR #316 audit latency:** remains synchronous and fail-open with 200 ms connect / 250 ms total HTTP timeouts per attempt. No durable queue/worker/retry/dead-letter path was found, so replacing it with an ungrounded asynchronous mechanism would weaken audit durability. An Auth-plus-RPC request may still incur two bounded attempts.
- **PR #318 location timestamp:** remains client-reported capture/selection time, not server receipt time or GPS attestation.

### Research basis

- PostgreSQL aggregate functions: `jsonb_agg` collects all input values into a JSON array; it does not supply pagination by itself. https://www.postgresql.org/docs/current/functions-aggregate.html
- Supabase database functions: RPC functions execute database logic and their input/output contract must explicitly support pagination. https://supabase.com/docs/guides/database/functions
- PostgreSQL trigger functions: https://www.postgresql.org/docs/current/plpgsql-trigger.html
- Supabase SECURITY DEFINER guidance: pin search_path, schema-qualify relations, and narrowly grant EXECUTE. https://supabase.com/docs/guides/database/functions

### Validation sequencing

No tests or CI were triggered during this implementation/review phase, per instruction to finish all implementation commits first. The historical integrated candidate predates the latest #316/#318 commits and does not validate the current seven-PR heads. After implementation closure, rerun exact-head workflows and the integrated replay against the reconciled shared CI/replay/history files. No merge, live Supabase migration, production data mutation, or Render deployment occurred.
