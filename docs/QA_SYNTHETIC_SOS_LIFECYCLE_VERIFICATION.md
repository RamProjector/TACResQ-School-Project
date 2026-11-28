# TACResQ synthetic SOS lifecycle verification specification

**Research date:** 2026-11-26  
**Status:** Test design only; no synthetic workflow has been executed against a live service.  
**Scope:** One isolated, reproducible requester-to-dispatch-to-field-unit lifecycle, with source-backed evidence at each stage.  
**Relationship:** Complements Sections 47–50 of `docs/TACRESQ_UI_FUNCTIONALITY_MASTER_PLAN.md`. This document is a test plan, not a claim of production readiness or a new Admin dispatcher feature.

## 1. Objective and safety boundary

Verify that a synthetic SOS can be created once, become visible to the Android dispatcher, be assigned or claimed through the correct supported path, progress through the authorized incident lifecycle, produce the expected in-app notifications/chat records, accept only valid incident-bound location observations, and close without leaving stale permissions or active work behind.

Run only against:
- a disposable local Supabase stack with the repository's migrations replayed; or
- a dedicated non-production Supabase/Firebase project and synthetic accounts explicitly approved for testing.

Do not run this test with real people, live incidents, real emergency coordinates, production credentials, or production notifications. Never send a test push to a real user's device. Do not enable production cron, pg_net, Render workers, FCM scheduling, a paid service, or a production migration for this test.

**Hard safety rules**
1. A test timeout after commit must retry the same saved SOS request ID and exact payload. It must never create a replacement incident.
2. A test account may read or mutate only records allowed to its role, ownership, and assignment.
3. An empty queue after a failed refresh is not evidence that there are no incidents.
4. FCM provider acceptance is not device receipt, display, read, acknowledgement, or response.
5. A dispatcher progress update is not an independent responder acceptance.
6. GPS writes require the correct assigned identity, incident state, explicit foreground sharing, and valid fresh location data.
7. Test teardown must remove only fixtures created by that run; never delete unrelated or production records.

## 2. Critical workflow distinction: two assignment paths

These are separate supported paths and must be tested independently.

### Path A — dispatcher-managed response-team assignment

The dispatcher assigns one bounded response team through the atomic team-assignment RPC. The dispatcher owns shared incident progress. The database mirrors the shared status into the assigned team membership records. The current app does **not** expose a separate responder "accept dispatch" action or durable responder-acknowledgement event in this path.

Expected lifecycle:
`pending → assigned → en_route → on_scene → transporting → resolved`

The assigned field units can explicitly start/stop GPS sharing when the incident is `en_route`. Their own GPS-sharing action is not an acceptance or arrival event.

### Path B — field-unit claim of a dispatcher-enabled incident

A dispatcher first makes a pending incident claimable. An available responder or driver can then claim it through the guarded claim RPC. The claim inserts the caller's assignment and transitions the incident to `assigned`. This is not the same operation as dispatcher assigning a response team. Do not run both assignment mechanisms against the same fixture or treat a claim as a team-wide assignment.

Expected lifecycle begins:
`pending + is_claimable → assigned (claimed by one field unit) → authorized progress`

The claim RPC and source guards must be tested independently from the dispatcher team assignment path.

## 3. Synthetic test identities and fixture manifest

Create distinct synthetic Auth users and linked `public.profiles` rows in the disposable environment:

| Fixture | Profile role | Initial state | Purpose |
|---|---|---|---|
| `sos-requester-A` | requester | active | Creates the test incident |
| `sos-dispatcher-A` | dispatcher | active | Reads queue and controls assignment/progress |
| `sos-responder-A` | responder | active, available | Assigned field unit |
| `sos-driver-A` | driver | active, available | Second team member |
| `sos-outsider-requester` | requester | active | Negative cross-owner access tests |
| `sos-outsider-unit` | responder | active, available | Negative cross-assignment tests |
| `sos-admin-A` | admin | active, required AAL2 for Admin-only test surfaces | Confirms Admin IPS boundaries; not a dispatcher |

Use `example.test` email addresses and clearly synthetic profile metadata. Keep Supabase Auth UUIDs and profile IDs as separate values. Never create a matching Laravel Sanctum identity by email and assume it is the same person. Laravel tests use a separate test database and separate Eloquent users.

Each run should record a random test-run ID and a manifest containing:
- environment/project identifier (never credentials);
- exact repository commit and migration set;
- synthetic Auth UUIDs and profile IDs;
- generated incident ID;
- client SOS request UUID and payload fingerprint;
- assignment/event/notification/message/location row IDs created by the run;
- test start/end timestamps and test result;
- cleanup outcome.

Do not put passwords, bearer tokens, refresh tokens, FCM registration tokens, message bodies, precise coordinates, or attachment URLs in the manifest.

## 4. Primary scenario A — requester SOS through dispatcher team closure

### Step 0 — fixture preconditions

- Migrations replay successfully in the disposable database.
- Synthetic identities are authenticated using the same Supabase Auth/RPC paths as the app.
- Requester has no active incident that would block a new SOS.
- Responder and driver are active and available.
- No existing assignment links either field unit to another active incident.
- Test notification tokens are either absent or registered only to controlled test devices.
- All clocks and test timestamps are captured in UTC; server timestamps remain authoritative.

**Pass:** every precondition is asserted, not assumed from UI labels.

### Step 1 — submit one SOS

Requester sends a valid synthetic incident through Android repository/API using `api_create_incident_v4`.

Assert:
- one incident is created with requester ownership and initial `pending` status;
- one `incident_submission_keys` row binds requester ID + client request UUID to the exact payload and saved response;
- incident status history contains the authoritative creation/status event where the trigger applies;
- response incident ID matches the persisted row;
- location fields match the test fixture's approved synthetic location input and location source metadata.

**Negative/retry assertions**
- Double tap does not create two incidents.
- Repeating the same request UUID and identical payload returns the original saved response.
- Reusing the request UUID with a changed payload is rejected.
- Simulate a response timeout after database commit, then retry the original encrypted pending envelope; assert one incident only.
- A different requester cannot read, retry, or discard the pending envelope.
- A second new SOS is blocked while the original outcome is unresolved.

Evidence: `incidents`, `incident_submission_keys`, `incident_status_logs`, requester client result, test-run manifest.

### Step 2 — dispatcher queue visibility

Authenticate as the dispatcher and perform a fresh queue read through the same Android dispatcher repository/API path.

Assert:
- the pending incident is returned to the dispatcher;
- unrelated requester cannot read it;
- requester sees only their own incident;
- responder/driver do not see a claimable incident unless the dispatcher has enabled that path;
- queue read failure is represented as unavailable/stale, not a successful empty queue.

Important: the current schema does not establish a durable "dispatcher viewed incident" event merely because a read RPC returned a row. Record the test client's successful read result in the test harness; do not invent a production view event.

Evidence: dispatcher read response, authenticated role, incident ID, response timestamp, source query/result metadata. This is test evidence, not a new audit table.

### Step 3 — dispatcher assigns a response team atomically

Dispatcher selects the synthetic responder and driver and submits the bounded team assignment through the existing assignment RPC.

Assert:
- server rechecks dispatcher role, active account state, incident state, each unit's active account state and availability under the transaction's locking rules;
- assignment either commits as one complete team operation or fails without partial membership;
- `incident_assignments` contains exactly the intended team members;
- legacy lead/assigned fields are consistent with the current compatibility contract;
- `incident_assignment_events` records trigger-written `assigned` events for the membership additions;
- incident progress is `assigned`;
- requester and assigned field units receive the expected generic `user_notifications` rows according to trigger policy;
- unrelated users receive no notification and cannot read the assignment.

Negative cases:
- dispatcher selects an off-duty, suspended, non-field, already-busy, or nonexistent account;
- non-dispatcher calls the assignment RPC directly;
- concurrent dispatcher assignments race for one field unit;
- duplicate assignment submission is retried after an ambiguous timeout;
- a selected unit becomes unavailable before the transaction commits.

Assert no partial team membership, unauthorized notification, or false success on any rejected attempt.

Evidence: assignment RPC result, `incident_assignments`, `incident_assignment_events`, `incident_status_logs`, `user_notifications`, source-local event IDs.

### Step 4 — dispatcher-owned progress and field-unit GPS

Dispatcher advances the shared incident through legal status transitions. Do not simulate a separate responder acceptance action; the current workflow does not provide one in this path.

For each status:
- `assigned → en_route`
- `en_route → on_scene`
- `on_scene → transporting`
- `transporting → resolved`

Assert:
- only dispatcher can use the dispatcher-managed progress RPC;
- `incident_status_logs` records the server-authoritative transition and actor;
- `incident_assignments.responder_status` mirrors the shared progress as specified by the dispatcher-managed migration;
- profile duty-status mirror changes are consistent with the active assignment and closure policy;
- requester and assigned team inboxes receive only the expected generic status notifications;
- unrelated incidents and team members remain unchanged.

GPS subtest while the incident is `en_route`:
- assigned responder explicitly starts the foreground location service with permission granted;
- the location writer is bound to that responder's own assignment and incident;
- one fresh synthetic fix is accepted with valid coordinates, accuracy and recorded timestamp;
- dispatcher/authorized participant can read only the permitted incident-bound observation;
- unassigned responder, driver not in the team, requester, and Admin cannot use a client UI path to write another person's location;
- stale/future/invalid fixes and writes after closure are rejected;
- after the incident leaves `en_route`, assignment is revoked, permission is removed, or account changes, tracking stops and no further location writes are accepted.

Do not use real coordinates or claim that one test fix represents continuous GPS availability or physical arrival.

Evidence: incident and assignment status rows, `incident_status_logs`, `location_logs`, location writer response, device tracking state and timestamps.

### Step 5 — incident-scoped chat

Use only synthetic text in the baseline test. Test attachments separately because Storage upload reservations, object policies and cleanup are separate boundaries.

Assert:
- requester and currently assigned responders/drivers can list and send within the incident conversation;
- dispatcher access matches the explicit chat policy;
- unrelated user and Admin are denied unless a reviewed policy explicitly changes this boundary;
- each send uses a client message UUID;
- retry with the same sender + client message UUID does not create a duplicate;
- reusing the same UUID with conflicting content is rejected;
- message text and private content do not appear in general notification bodies or audit logs;
- message-created notification is written for eligible participants, excluding the sender;
- mark-read is caller-scoped.

Evidence: `incident_messages`, `user_notifications`, authenticated caller, client message UUID and source row IDs.

### Step 6 — notification inbox and Realtime reconciliation

Assert:
- persistent `user_notifications` rows are the authoritative inbox;
- each recipient can list/read only their own notifications through caller-scoped RPCs;
- `api_mark_notification_read` updates only the caller's notification;
- Realtime payload contains only the allowed notification ID/event hint;
- disconnect/reconnect and missed broadcast are recovered by refreshing the authenticated inbox;
- no broadcast is interpreted as proof that the recipient read or acted on the notification.

Evidence: inbox RPC response, notification row/read timestamp, Realtime connection state and refresh result. A Realtime event is a hint, not the durable notification itself.

### Step 7 — FCM provider and device boundaries

**Automated isolated test:** use Laravel's fake FCM client and the existing feature tests to verify claim/send/ledger update behavior. Verify queue claim, lease, duplicate suppression, backoff, invalid-token path, attempt ceiling and sanitized errors.

**Controlled device test:** only if a non-production worker and test Firebase project are explicitly configured, send one generic synthetic notification to a dedicated test device. Record separately:
1. notification row created;
2. delivery attempt claimed;
3. FCM provider accepted/rejected;
4. Android FCM SDK received callback, where observable;
5. notification displayed;
6. user opened notification;
7. authenticated inbox marked read;
8. responder performed an operational action.

Do not infer stages 4–8 from stage 3. Firebase delivery reports distinguish sends, Android receives, impressions and opens; some metrics are delayed or aggregated. Device-specific evidence requires an appropriate client-side callback/test observation. Do not enable BigQuery export or any new analytics collection as part of this baseline.

**Current deployment limitation:** repository and Render execution audit state that the current Free Render web-service startup does not run the FCM dispatcher or a scheduler, and the reviewed Supabase project did not have the required cron/net trigger configured. Therefore automated FCM delivery in the current deployment is not a passing test prerequisite; mark it **Blocked / Not configured**, not Pass. Do not activate a worker, trigger, paid Render service, or production scheduler without approval.

Evidence: `fcm_delivery_attempts`, sanitized dispatcher stats, fake-client assertions, optional dedicated test-device observation. Provider `sent` means provider accepted the send, not delivered/read/acknowledged.

### Step 8 — close and verify terminal state

Dispatcher advances the shared incident to `resolved` through the authorized transition.

Assert:
- incident status is `resolved`;
- authoritative incident status history contains the transition;
- assignment closure history records the expected `incident_closed` events;
- notifications are generated only for eligible recipients;
- no further location writes or status mutations are accepted for the closed incident;
- GPS tracking service stops;
- the incident disappears from active queues but remains in authorized history;
- requester and team see a clearly closed final status;
- all test fixture IDs can be enumerated for safe teardown.

A resolved TACResQ record proves the application recorded closure. It does not prove external emergency-service contact, arrival, treatment, rescue, or real-world outcome.

## 5. Primary scenario B — claimable field-unit path

Use a fresh requester/incident fixture; do not reuse the team-assignment incident.

1. Requester creates one synthetic SOS and the same idempotency assertions from Scenario A pass.
2. Dispatcher explicitly enables claimability through the dispatcher-only RPC.
3. Available synthetic responder sees the incident in the claimable query; unrelated units and requester cannot claim it.
4. Responder claims through `api_claim_incident`.
5. Assert exactly one assignment row, incident status `assigned`, and correct actor/member identity.
6. A second concurrent claimant loses safely; no duplicate assignment or partial state.
7. Off-duty, suspended, wrong-role, unassigned, or stale-session claimant is denied.
8. Dispatcher can refresh and see the updated incident/assignment.
9. Continue only through progress transitions supported by the current source contract. Do not assume the claim path automatically grants a second team member or creates a separate acknowledgement event.
10. Close using the authorized lifecycle and verify claimability cannot be reused for a closed incident.

Evidence: `is_claimable`, `incident_assignments`, `incident_assignment_events`, `incident_status_logs`, claim RPC results.

## 6. Cross-cutting failure-injection matrix

| Failure | Expected safe result | Required assertion |
|---|---|---|
| Network loss before SOS commit | Saved local envelope, not confirmed | No incident falsely shown as server-confirmed |
| Timeout after SOS commit | Outcome unknown; explicit retry same key | Exactly one incident, original response returned |
| Same key, different payload | Reject | No second incident or payload overwrite |
| App process death | Restore owner-bound encrypted pending envelope | Same key and payload; no auto-send |
| Account switch | Pending envelope inaccessible to other account | No retry/discard under wrong identity |
| Dispatcher refresh failure | Queue stale/unavailable | No false empty queue or assignment action |
| Concurrent team assignment | One atomic winner or explicit rejection | No partial team membership |
| Unit suspended after selection | Server rejects under active-account guard | No assignment to suspended profile |
| Claim race | One claimant wins | One assignment row only |
| Realtime disconnect | Inbox refresh fallback | Missed event recovered from authoritative inbox |
| FCM provider accepts but device offline | Provider acceptance only | No "delivered/read/accepted" claim |
| FCM worker absent | Not configured/blocked | No claim that queue is automatically processed |
| GPS permission revoked | Tracking stops | No later writes |
| Stale/future GPS fix | Reject | No location row accepted |
| Incident closes during GPS | Tracking stops and writer rejects | No post-closure observation |
| Chat response timeout | Retry same client message UUID | No duplicate message |
| Unauthorized chat participant | Deny | No message or notification side effect |
| Notification read by another user | Deny | Original recipient row unchanged |
| Status transition conflict | Refresh/re-authorize | No stale transition applied |
| Telemetry writer fails | Workflow outcome remains separate | No false success audit record |

## 7. Existing test inventory and what it proves

Existing repository checks provide valuable component-level coverage, but they do not collectively constitute one real end-to-end execution:

| Existing test/source | Current proof scope | Not proven |
|---|---|---|
| `tools/test_workflow.py` | Deterministic local prototype model; optional HTTP mode for Laravel-style API | Actual Android-to-Supabase end-to-end workflow |
| `tools/test_durable_sos_idempotency.py` | Source-contract checks for persisted envelope, same-key retry and database idempotency migration | Live Supabase concurrent commit/timeout behavior |
| `tools/test_dispatch_team_assignment.py` | Source contracts across Android, repository, RPC migration and account guard | Live transaction race with multiple real sessions |
| `tools/test_per_responder_assignment_status.py` | Dispatcher-owned shared progress contract and compatibility mirror | Device-level team progress or physical response |
| `tools/test_dispatcher_assignment_freshness.py` | UI/source guard against assigning from stale queue/unit reads | Atomic snapshot across multiple server reads |
| `tools/test_assignment_event_history_contract.py` | Assignment history trigger, append-only and read boundary contract | Every production assignment event was captured |
| `tools/test_responder_incident_claiming.py` | Claimable workflow source contract | Live concurrent claim execution |
| `tools/test_requester_offline_cache.py` | Owner-scoped, encrypted, bounded requester status cache contract | Actual offline behavior on all Android devices |
| `tools/test_notification_realtime_contract.py` | Realtime topic, minimized payload and inbox refresh contract | Live Supabase Realtime reconnect and authorization |
| `tools/test_fcm_delivery_queue.sql` | Disposable Postgres queue/lease/retry/role contract | Worker scheduling or Firebase delivery |
| `backend/src/tests/Feature/FcmNotificationDispatcherTest.php` | Laravel dispatcher behavior with fake HTTP/FCM responses | Real FCM acceptance or device receipt |
| Android instrumented persistence tests | Encrypted pending SOS persistence across test lifecycle | Whole distributed workflow and OEM-specific behavior |

A passing static source-contract test means the expected source text/structure is present; it is not a substitute for executing the actual RPC, database transaction, Android device, or provider.

## 8. Execution layers and release evidence

### Layer 1 — deterministic CI/source contracts
Run existing Python source-contract scripts, Android unit tests/build, Laravel PHPUnit feature tests, and static/security checks. Record exact commit, command, exit status and artifacts. Keep test results separated by subsystem.

### Layer 2 — disposable Supabase/Postgres integration
Replay the exact migration set in a clean local Supabase stack. Run pgTAP tests and authenticated client-level RPC tests using synthetic identities. Include concurrent transactions and rollback assertions. Supabase's testing guidance recommends both pgTAP database tests and application-level tests through the same client/policies the app uses.

### Layer 3 — Android integration and manual role walkthrough
Use dedicated synthetic accounts and a test build pointed at the disposable/staging project. Test process death, network transitions, permission denial/revocation, foreground/background FCM behavior, account switching, notification taps and UI state. Compose UI tests can verify semantics and user actions; manual physical-device testing remains necessary for OS permissions, GPS, background delivery and OEM behavior.

### Layer 4 — provider integration
Use a fake FCM client for automated worker tests. Only use a real Firebase non-production project/device after a separate approved setup. Do not count the current Render deployment as automatically dispatching pushes; its scheduler/worker gap remains.

### Layer 5 — production readiness
Out of scope for this plan. Requires separately authorized production validation, a real operational owner, verified worker execution, independent monitoring, privacy/retention controls, and explicit service-level commitments. A synthetic test pass does not create an emergency-response SLA.

## 9. Test result format

For every step report:
- **Status:** Pass / Fail / Blocked / Not run / Not applicable / Inconclusive.
- **Environment:** local disposable / staging / production (production only if separately authorized).
- **Commit and migrations:** exact SHA and applied migration set.
- **Identity:** synthetic role and source namespace; never credentials.
- **Action:** exact RPC/API/UI action.
- **Expected evidence:** table/source and key fields.
- **Observed evidence:** source-local IDs and sanitized result.
- **Negative side effects:** explicitly verify absence of unauthorized rows, notifications, assignments or writes.
- **Limitations:** missing telemetry, provider, device or human observation.
- **Cleanup:** fixture IDs and confirmation that only this run's data was removed.

No aggregate "100% end-to-end verified" badge. Report per-stage coverage and blockers.

## 10. Acceptance gate

The synthetic workflow specification is ready to be implemented as a test suite only when:
- the environment is isolated and its identity namespace is explicit;
- the correct assignment path is selected;
- every mutation has a server-authoritative expected record and a negative side-effect assertion;
- the same idempotency key is used across ambiguous SOS retries;
- source-local event IDs and correlation confidence are preserved;
- notification, FCM, device receipt, read, responder action, and incident closure are distinct;
- test fixtures are deterministic, non-sensitive and safely cleanable;
- failures do not create duplicate SOS, assignment, message, GPS, or notification effects;
- automated, emulator, physical-device, provider and production checks are reported separately.

## 11. References

- Supabase Testing Overview (pgTAP, application-level tests, CI and negative cases): https://supabase.com/docs/guides/local-development/testing/overview
- Supabase Testing Your Database: https://supabase.com/docs/guides/database/testing
- Android Compose UI testing: https://developer.android.com/develop/ui/compose/testing
- Android WorkManager integration testing: https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing
- Firebase Cloud Messaging — Receive messages in Android apps: https://firebase.google.com/docs/cloud-messaging/android/receive-messages
- Firebase Cloud Messaging — Understanding message delivery: https://firebase.google.com/docs/cloud-messaging/understand-delivery
- W3C Trace Context: https://www.w3.org/TR/trace-context/
