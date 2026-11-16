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
