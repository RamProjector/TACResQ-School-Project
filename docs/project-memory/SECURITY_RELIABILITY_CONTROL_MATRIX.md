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
| Privileged functions | Supabase recommends SECURITY INVOKER where possible; SECURITY DEFINER needs a controlled search_path, schema-qualified references, and restricted EXECUTE. https://supabase.com/docs/guides/database/functions | api_units() is SECURITY DEFINER and schema-qualifies public/auth references. Main contains migration 20261116150000_pin_api_units_search_path.sql setting search_path=pg_catalog. | Live inspection on 2026-11-17 still showed search_path=pg_catalog, public, auth. The migration is in the repository but absent from the live migration ledger. Do not create a duplicate migration. Keep it pending until the authorized migration process; test exact proconfig and unchanged grants in isolated PostgreSQL. |
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
- Live Supabase migration ledger ended at 20261116042927_responder_incident_claiming. It did not contain 20261116150000_pin_api_units_search_path.sql or later PR migrations. No live migration was applied during this review.
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

- `20261116170000_api_operation_audit_and_fk_indexes.sql` from PR #316;
- `20261116180000_harden_field_claim_exclusivity.sql` from PR #317;
- `20261116200000_harden_dispatch_claimability_lifecycle.sql` from PR #319;
- `20261116210000_protect_profile_identity_metadata.sql` from PR #320; or
- `20261117010000_restrict_legacy_admin_user_list_rpc.sql` from PR #321.

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
