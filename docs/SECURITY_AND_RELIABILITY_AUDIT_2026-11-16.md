# TACResQ Security and Reliability Audit — 2026-11-16

## Executive summary

This review compares the repository's current implementation and the read-only live Supabase schema/advisors against OWASP API Security guidance, Supabase database-function/RLS guidance, PostgreSQL concurrency controls, Android location guidance, Firebase Cloud Messaging delivery guidance, and OWASP logging guidance.

The highest-priority confirmed defect was in field self-claiming:

- `api_claim_incident(bigint)` accepted any claimable incident except closed states, including `on_scene` and `transporting`, while `api_claimable_incidents()` exposed only `pending` and `assigned`.
- A unit's claim transaction locked only the target incident, not the unit profile, and did not check whether the unit already belonged to another active incident. Concurrent claims against different incidents could therefore both pass the availability check.
- The new migration `20261122050813_harden_field_claim_exclusivity.sql` fixes this by aligning lifecycle states, locking incident then profile (matching dispatcher assignment lock order), rechecking identity/duty, rejecting another active assignment, and hiding claim opportunities from busy/already-assigned units.

The migration is committed to a dedicated PR and has **not** been applied to live Supabase.

## Review scope and limitations

Reviewed:
- Current repository migration history and core assignment/lifecycle SQL.
- Dispatcher multi-unit assignment and per-responder status functions.
- Android repository/API call contracts and dispatcher UI source.
- Laravel bridge, FCM dispatcher/client, runtime configuration, and API route source.
- Current live Supabase migration ledger, selected catalog privileges/policies/functions, and security/performance/health advisors.
- Existing PR #316 API audit implementation and exact-head CI status.

Not performed:
- No external penetration test, fuzz test against a deployed endpoint, or credential scan of private deployment settings.
- No physical Android device/OEM/Doze/GPS test.
- No real FCM device delivery verification.
- No live Supabase migration, Render environment change, secret rotation, Cron activation, deployment, or production data modification.
- No claim that the project is certified or safe to operate as an emergency-service provider.

## Findings

### F-01 — Field self-claim did not enforce one active incident per unit
**Severity: High | Status: Fixed in this PR; migration pending deployment**

The original claim RPC locked the target incident and enforced the five-member incident capacity, but did not serialize claims by unit or check that the same responder/driver was already assigned elsewhere. It also accepted claimable states beyond the UI's own `pending`/`assigned` list.

Impact:
- A responder or driver could claim a second active incident.
- Two simultaneous requests against different incidents could both pass a stale availability check.
- A stale client could submit a claim after an incident had advanced to scene/transport.

Correction:
- Acquire the target incident row lock, then lock and re-read the actor's profile row. This follows the incident-then-profile order used by dispatcher team assignment.
- Recheck actor role and duty status after locking.
- Permit claims only for `pending` and `assigned`.
- Reject claims when the actor is already assigned to another active incident through either `incident_assignments` or legacy `assigned_to`.
- Keep the five-member limit, including a legacy lead assignment that has not yet been backfilled.
- Exclude busy and already-assigned units from claimable-incident discovery.

Concurrency validation now covers four distinct invariants:
- One responder racing to claim two different incidents: exactly one claim succeeds and the competing request returns `UNIT_BUSY`.
- Two distinct available responders racing to join the same incident: both valid memberships succeed, while the incident advances from `pending` to `assigned` once.
- A field self-claim racing a dispatcher assignment for the same responder on different incidents: exactly one operation commits.
- Two dispatchers assigning the same responder to different incidents: exactly one operation commits.

The tests use independent authenticated PostgreSQL sessions against the disposable PostgreSQL 16 replay database. The cross-path tests hold the shared responder profile row in a coordinator transaction and wait until both workers are observed in PostgreSQL's lock queue before releasing it. This makes the start condition deterministic and tests the actual database lock behavior. These are database concurrency tests, not live PostgREST or physical-device tests.

PostgreSQL documents that `SELECT ... FOR UPDATE` blocks conflicting lockers/writers on the same row until transaction end, and recommends consistent lock ordering to reduce deadlocks. The responder profile row is the shared serialization point for operations targeting different incidents. The migration replay and source-contract checks are included in CI; all four scenarios passed on exact PR head `c8100543efd73626b765c8c160bb0cfe48dea0a7`.

### F-02 — Live database still has pending hardening migrations
**Severity: Medium | Status: Outstanding; requires separately approved deployment**

The live Supabase advisors still report:
- Four unindexed foreign keys: `fcm_delivery_attempts.profile_id`, `incident_assignments.assigned_by`, `incident_request_edit_logs.requester_id`, and `user_notifications.incident_id`.
- Six RLS auth/initplan performance warnings.
- Nine unused-index candidates.

The four FK indexes are included in PR #316. The six RLS policy optimizations are in PR #315. These migrations are repository changes only until an operator approves and applies them to live Supabase.

Do not drop the nine unused-index candidates from advisor output alone. The live project has only a handful of rows in the inspected core tables, so the workload evidence is insufficient to justify index removal.

### F-03 — SECURITY DEFINER advisor warnings require per-function review, not blanket revocation
**Severity: High impact if misconfigured | Status: Partially reviewed; follow-up required**

Supabase reports 18 public functions callable by `authenticated` with `SECURITY DEFINER`. A SECURITY DEFINER function runs with its owner's privileges and can bypass ordinary RLS, so every such function must enforce identity, role, and object-level authorization in its body and have narrow EXECUTE grants.

The prior read-only catalog review found:
- The reviewed public functions are owned by `postgres`.
- None of the 18 reviewed public functions is executable by `anon`.
- All reference `auth.uid()`.
- Seventeen had a pinned `search_path`; `api_units()` was the exception and has a repository migration in PR #313 to pin it.

These facts do not prove every function's authorization logic is correct. Continue body-level review and add allow/deny tests for each role and object boundary. Do not indiscriminately revoke all authenticated RPCs or convert them to invoker mode; that can break legitimate workflows. Supabase specifically recommends narrow EXECUTE grants, pinned search paths, and explicit ownership checks.

### F-04 — Five RLS-enabled tables have no policies
**Severity: Context-dependent | Status: Reviewed as intentional server/RPC-only access**

The live advisor reports no policies on:
- `fcm_delivery_attempts`
- `incident_messages`
- `incident_request_edit_logs`
- `incident_submission_keys`
- `user_notifications`

The prior grant inspection found no direct `anon` or `authenticated` table grants on these tables. They are accessed through narrowly scoped RPCs or server-side service-role operations. This is an intentional deny-by-default design, not automatically a vulnerability. Do not add broad policies to clear the advisor warning.

### F-05 — API operation audit writes are synchronous and add request latency
**Severity: Medium | Status: Follow-up required (PR #316)**

PR #316's Laravel audit logger performs an additional HTTP request to Supabase for each logged operation, with a two-second timeout. It is fail-open for errors, but a slow audit sink can still delay the Laravel request path before the timeout expires. This is especially relevant when several bridge/RPC operations occur in one user workflow.

Improve this with a bounded asynchronous writer or a safe post-response/worker mechanism that is already supported by the deployment. Do not introduce an unconfigured queue or silently depend on paid infrastructure. Keep correlation IDs and metadata-only logging. Add latency and failure tests before rollout.

### F-06 — API audit coverage is intentionally incomplete
**Severity: Medium | Status: Documented limitation (PR #316)**

Android and the dispatcher dashboard call Supabase directly, bypassing Laravel's `api_operation_logs` writer. Supabase platform/API logs remain the source for those calls. Do not describe the Laravel table as end-to-end tracing.

A later enhancement should correlate direct client operations through safe request IDs and server-side events without recording access tokens, precise coordinates, incident narratives, or contact information.

### F-07 — Audit retention and operational access policy are undefined
**Severity: Medium | Status: Follow-up required (PR #316)**

The new audit table has no retention/cleanup job or approved retention period. Define retention, authorized readers, export/audit access, and a bounded cleanup process before sustained production use. Do not add an automatic deletion job until the retention policy is approved.

### F-08 — FCM acceptance is not proof of device delivery
**Severity: High operational impact | Status: Known platform limitation; device validation required**

The backend records provider send outcomes and maintains a durable in-app notification inbox. A successful FCM HTTP response means the provider accepted the message, not that the device displayed it. Android Doze, disabled notification permission, connectivity, token state, OEM power management, and app lifecycle can delay or prevent display. High priority can improve urgency but can be deprioritized if messages do not result in user-visible notifications.

Keep the in-app inbox authoritative. FCM must remain supplementary/best-effort, never the sole emergency channel. Validate notification permission denial, Doze, force-stop, offline/reconnect, token rotation, and actual device display with synthetic test accounts.

### F-09 — FCM delivery lease duration needs worst-case timing validation
**Severity: Medium | Status: Risk to test**

The worker claims batches up to 50 and uses a 600-second lease. Sending and updating the ledger are sequential network operations with individual timeouts. Under repeated slow-provider or slow-ledger responses, total batch processing time could exceed the lease and allow work to be reclaimed while the first worker is still processing it.

Measure worst-case batch duration and either renew leases, reduce batch size, or move to a bounded concurrent worker with per-attempt lease ownership. Do not increase concurrency without checking provider quotas and duplicate-delivery behavior.

### F-10 — Location freshness must be visible, not inferred from a pin
**Severity: High operational impact | Status: Device/UI validation required**

Location recording has server-side coordinate bounds, timestamp bounds, and rate limiting. However, a latest-known location is not automatically a live location. The dispatcher experience must prominently distinguish fresh, delayed, stale, and unavailable updates using the recorded timestamp and an explicit freshness threshold. Never imply a stale coordinate is the responder's current position.

Validate foreground tracking, screen-off behavior, permission revocation, approximate location, battery saver, network loss, and OEM process restrictions. Android recommends foreground location access with a visible location foreground service when continuous background-capable tracking is genuinely core to the feature; background access is subject to platform restrictions and Play policy.

### F-11 — Routing provider boundaries are privacy-sensitive
**Severity: Medium | Status: Documented design constraint**

The current route preview proxy sends responder-origin and assigned-incident destination coordinates to the selected routing provider. It avoids forwarding bearer tokens, incident IDs, narratives, requester identity, and profile data. Geoapify requires a server-side API key; private OSRM is an explicit alternative. Automatic failover is intentionally avoided because it would disclose location to an additional provider.

Route preview is not turn-by-turn navigation and does not prove road passability, flood/closure conditions, emergency-vehicle access, or live traffic. Keep the feature disabled by default until provider configuration and user-facing limitations are clear.

### F-12 — Five RLS/no-policy warnings and unused-index notices should not be "fixed" mechanically
**Severity: Low by themselves | Status: No change recommended**

Supabase advisors are deterministic checks, not a substitute for understanding the access model and workload. Retain RPC-only tables without client policies when direct access is intentionally revoked. Retain unused indexes until representative query volume and query plans demonstrate they are unnecessary.

## Comparison against guidance

| Area | TACResQ implementation | Guidance comparison | Improvement |
|---|---|---|---|
| Object-level authorization | Scoped RPCs, role/ownership checks, assignment membership | OWASP API1/API5 emphasize per-object and per-function authorization | Add negative tests for every role and object identifier |
| Sensitive business flows | SOS idempotency, one-active-request guard, assignment capacity | OWASP API6 warns about abuse of sensitive business flows | Add concurrent claim/submission tests and rate-limit review |
| Database privilege boundaries | RLS, server/RPC-only tables, SECURITY DEFINER RPCs | Supabase recommends invoker by default, narrow grants, pinned search paths, explicit ownership checks | Continue function-by-function review |
| Concurrency | Incident row locks; new unit profile lock for self-claim | PostgreSQL row locks serialize conflicting writers/lockers | Add true two-session concurrency tests in a controlled test database |
| Logging | Metadata-only audit and request correlation in PR #316 | OWASP recommends logging security events while excluding secrets/PII and testing logging failure/resource effects | Add retention, latency, and log-flood controls |
| Location | Bounded coordinates, timestamp validation, rate limiting, foreground tracking path | Android restricts background location and recommends visible foreground service when required | Device/OEM matrix and visible freshness labels |
| Notifications | Durable inbox + FCM delivery ledger | FCM acceptance does not equal device display; priority can be delayed/deprioritized | Treat push as supplementary; device delivery validation |
| Routing | Server proxy, explicit provider, no automatic provider failover | Privacy-minimizing provider selection reduces unnecessary location disclosure | Keep route preview opt-in/disabled by default and label limits |
| Mobile app security | Account-bound encrypted session/pending-message/SOS persistence is present in reviewed Android paths; full mobile assessment not completed | OWASP MASVS covers storage, crypto, auth, network, platform, code, resilience, and privacy | Run MASVS/MASTG checks for exported components, deep links, local data remnants, TLS, session expiry, and permission/privacy flows |

## Prioritized follow-up work

1. Complete per-function authorization review and role/object negative tests for the 18 SECURITY DEFINER functions.
2. Extend the two-session PostgreSQL race matrix to duty-status changes and cancellation/assignment interactions; retain the existing concurrent SOS idempotency regression test.
3. Reduce audit write latency and define audit retention/access policy.
4. Validate FCM batch lease timing and actual device delivery.
5. Add explicit location freshness states and test the Android foreground tracking lifecycle on physical devices.
6. Review Laravel transitional API exposure and endpoint throttling against the actual deployed route configuration.
7. Re-run Supabase advisors after separately approved repository migrations are applied.
8. Review Storage object authorization, upload size/type enforcement, and retry durability with synthetic accounts.

## Research references

- OWASP API Security Top 10: https://owasp.org/www-project-api-security/
- OWASP Mobile Application Security Verification Standard (MASVS): https://mas.owasp.org/MASVS/
- OWASP Logging Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- Supabase Database Functions — SECURITY DEFINER/INVOKER, search_path, and EXECUTE grants: https://supabase.com/docs/guides/database/functions
- Supabase Row Level Security: https://supabase.com/docs/guides/database/postgres/row-level-security
- Supabase Advisors: https://supabase.com/docs/guides/observability/advisors
- PostgreSQL Explicit Locking: https://www.postgresql.org/docs/current/explicit-locking.html
- Android location permissions: https://developer.android.com/develop/sensors-and-location/location/permissions
- Android background location: https://developer.android.com/develop/sensors-and-location/location/background
- Firebase Cloud Messaging Android priority: https://firebase.google.com/docs/cloud-messaging/android-message-priority
- Firebase Cloud Messaging message lifespan: https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan

## Deployment safety

This report and the associated migration are repository artifacts only. No live Supabase migration, Render configuration, secret, deployment, Cron job, notification, or production data was changed.


## Cross-RPC lifecycle compatibility finding

The field-claim lifecycle deliberately leaves `incidents.assigned_to` dispatcher-managed while adding the first self-claim to `incident_assignments` and advancing `pending` to `assigned`. The existing private dispatcher assignment RPC previously rejected every `assigned`/ `en_route` incident with a null `assigned_to`, which prevented dispatchers from expanding a field-claimed team.

The field-claim migration now replaces the private dispatcher assignment implementation to:
- allow `assigned` or `en_route` with a null dispatcher lead only when at least one authoritative team membership already exists;
- preserve the dispatcher-managed lead for ordinary dispatcher-originated assignment;
- lock the incident before re-reading and locking the dispatcher profile, matching the claimability-toggle lock order;
- include a legacy `assigned_to` lead missing from `incident_assignments` when enforcing the five-unit capacity;
- retain stable sorted locks for selected units, availability checks, and duplicate-safe inserts.

This compatibility correction is covered by the field-claim regression contract. It does not assign a self-claiming responder as a dispatcher leader.


## Two-session concurrency validation added — 2026-11-17

The field-claim PR now includes `tools/test_field_claim_concurrency.sh`, invoked after clean migration replay. It starts two independent authenticated PostgreSQL sessions and races the same available responder against two different claimable incidents. Each incident uses a different requester fixture so the existing one-active-request-per-requester guard remains part of the test rather than being bypassed.

Expected result:
- exactly one RPC response is `claimed`;
- exactly one competing response is `UNIT_BUSY`;
- exactly one active `incident_assignments` membership exists for the responder across the two test incidents;
- exactly one of the two incidents advances to `assigned`.

The test deliberately holds the first transaction open briefly after its claim so the competing session must contend on the shared responder profile lock. This validates a real PostgreSQL lock race rather than merely asserting SQL source order. It runs only against the disposable PostgreSQL database in CI; it does not touch live Supabase.

**Status:** passed on exact PR head `c8100543efd73626b765c8c160bb0cfe48dea0a7` in the clean PostgreSQL 16 migration-replay job. The CI log confirms the cross-incident claim race, same-incident multi-responder race, field-claim-versus-dispatcher race, and two-dispatcher race. The first attempted fixture had reused one requester for two active requests; that test-fixture defect was corrected by assigning separate requester accounts before the passing run.


## Claimable queue response-size review — 2026-11-17

The current `api_claimable_incidents()` implementation builds one `jsonb_agg` over every incident that is claimable, in `pending` or `assigned`, below team capacity, and not already joined by the caller. It returns incident identifiers, type, description, coordinates, status, timestamps, and team member IDs. The response is therefore unpaginated and grows with the eligible queue.

This is a confirmed scalability/response-size concern, not a confirmed authorization defect: the function checks authentication, responder/driver role, available duty status, and whether the caller already has an active assignment before returning the queue. The coordinate/details exposure is part of the operational field-unit queue contract.

No arbitrary `LIMIT` was added in this pass. A hard cap without a cursor/continuation contract can silently hide eligible emergency incidents; the current RPC returns a JSON array and no Android/web caller contract for pagination was established from this migration review. A safe follow-up should introduce deterministic keyset pagination (stable ordering with a unique tie-breaker), explicit page-size bounds, and a continuation token while updating every caller and proving queue completeness across pages. Until then, record the unbounded response as a scale risk and monitor eligible queue cardinality/response bytes.

Research basis:
- PostgreSQL documents `jsonb_agg` as collecting input values into a single JSON array; the aggregate itself does not impose a row bound: https://www.postgresql.org/docs/current/functions-aggregate.html
- Supabase documents database functions as RPC endpoints and supports returning datasets; pagination must be part of the function/API contract rather than assumed from a JSON aggregate: https://supabase.com/docs/guides/database/functions

No migration, test, or CI was run as part of this source review.
