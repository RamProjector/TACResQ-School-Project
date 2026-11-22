# TACResQ Implementation Baseline

**Snapshot date:** 2026-11-16  
**Repository:** `RamProjector/TACResQ-School-Project`  
**Canonical scope:** [PRODUCT_SCOPE.md](../PRODUCT_SCOPE.md)  
**Roadmap:** [APP_FEATURE_GAPS_AND_ROADMAP.md](../APP_FEATURE_GAPS_AND_ROADMAP.md)

This is a point-in-time summary, not a live status dashboard. Check the repository's current `main`, PR heads, CI, Supabase project, and Render deployment before acting.

## Implemented in repository source

- Kotlin/Jetpack Compose Android app with requester, responder/driver, dispatcher, and limited administrator workflows.
- Supabase Auth and Postgres RPC data path; in-memory fake API for explicit demo/testing.
- Requester SOS with optional one-time GPS coordinates, accuracy/time metadata, landmark fallback, duplicate-submission safeguards, and encrypted pending recovery.
- Role/ownership/assignment checks, status transition guards, incident audit history, single-active-requester workflow, and multi-responder incident assignments.
- Per-responder assignment status and responder incident claiming.
- Persistent in-app notification inbox, server notification records, FCM token registration, trusted HTTP v1 sender, and delivery-attempt ledger.
- Incident-scoped chat with server idempotency, private image attachment storage, and encrypted/account-bound Android text retry outbox (PR #312 merged 2026-11-16).
- Centered GPS Map destination, incident pins, device-only locate-me preview, and dispatcher visibility of fresh consented en-route field-unit reports.
- Authenticated Laravel route-preview proxy with Geoapify and private OSRM provider support. Backend route preview and Android route preview are disabled by default.
- Administrator-only web portal served by Laravel for account/security/analytics/audit functions; dispatcher web access is retired and dispatch workflows remain in the Android app.
- Admin role management foundation, bounded user listing, and append-only audit records.

## Implemented but not fully operationally verified

- Responder foreground location service: code is consent-gated and designed for foreground use; physical-device permission, battery, process, and OEM behavior still need validation.
- Map tile loading, GPS accuracy, permission recovery, and location freshness on real phones.
- Geoapify and private OSRM route rendering end-to-end with authorized synthetic coordinates.
- FCM foreground/background delivery, Doze, token rotation, permission revocation/regrant, account switching, and device notification display.
- Render FCM dispatch endpoint is implemented/deployed, but scheduled production dispatch remains inactive pending operator setup/approval.
- Encrypted DataStore/Keystore outbox persistence after real process death and on physical devices; CI compiles the instrumentation tests, but device execution remains outstanding.
- Android UI acceptance across representative screen sizes and devices.

The current incident location-source values describe broad capture mode (such as GPS/manual/none), not the underlying sensor/provider. Google documents that the fused provider may combine multiple signals and that apps cannot select the exact sensors. If provider provenance is added, store only a verified API/provider label and do not infer GNSS/Wi-Fi/cellular origin. See [RESEARCH_EVIDENCE.md](RESEARCH_EVIDENCE.md).

## Deliberately not implemented or not guaranteed

- Guaranteed emergency response or life-critical push delivery.
- Automatic SOS submission in the background or general offline write synchronization.
- Turn-by-turn voice navigation, live traffic, road closures, flooding, emergency-vehicle access, or guaranteed route ETA.
- Automatic routing-provider failover.
- Continuous/background responder tracking.
- Production FCM Cron dispatch unless explicitly configured and verified by an authorized operator.
- A formal incident team-leader role or standing roster.

## Live environment observations (read-only, 2026-11-16)

The connected Supabase project was inspected read-only during the project review:

- The live migration ledger matched checked-in migrations through `20261116042927_responder_incident_claiming` at the time of inspection. PR #313 subsequently merged a forward-only migration that pins `public.api_units()` to `pg_catalog`; that new migration was not applied to the live project as part of this work.
- The private incident-chat image bucket was configured with a 5 MiB limit and JPEG/PNG/WebP allowlist.
- `pg_cron` and `pg_net` were absent; Vault was present among the queried dispatch extensions. No production Cron job was active.
- Security advisor findings included disabled leaked-password protection and function search-path hardening opportunities. See the specific PR/review notes; do not treat advisor counts as proof of exploitable vulnerabilities.
- Performance advisor details (observed 2026-11-16): missing covering indexes on `fcm_delivery_attempts.profile_id`, `incident_assignments.assigned_by`, `incident_request_edit_logs.requester_id`, and `user_notifications.incident_id`; six RLS initplan warnings across `incident_assignments`, `incidents`, `location_logs`, and `realtime.messages`; nine unused-index candidates. The inspected public tables had only 0–2 live rows each, so there is not enough workload to claim measurable gains or justify dropping indexes. No performance indexes or policies were changed. The six RLS initplan policies were `incident_assignments_read_participants`, `incidents_read_participant_or_dispatcher`, `incidents_update_dispatcher_or_assigned_unit`, `location_logs_read_dispatcher_or_owner`, `location_logs_insert_owner`, and `realtime.messages.tacresq_authenticated_notification_broadcast_read`. Their live expressions include row-independent auth/helper lookups; any rewrite must preserve participant, dispatcher, and per-responder authorization exactly.
- No production migration, data mutation, extension activation, Vault secret, Render variable, Cron job, or live notification was created by that audit.

These observations can change. Re-run read-only checks before any deployment or production setup.

## Validation vocabulary

- **Source present:** code/migration exists.
- **Static contract passed:** source-level assertions passed.
- **Build passed:** specified Gradle/Laravel/frontend build completed.
- **Integration passed:** named integration workflow completed.
- **Device verified:** behavior observed on a named physical device/emulator with test details.
- **Production verified:** deployed configuration and live behavior explicitly checked.
- **Not verified:** evidence is missing or incomplete.

Never substitute one label for another.
