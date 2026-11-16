# TACResQ security, API, and database audit

**Audit date:** 2026-11-16  
**Repository:** `RamProjector/TACResQ-School-Project`  
**Scope:** Android, Laravel/Render, Supabase, routing, notifications, and operational readiness.

This is a point-in-time engineering audit, not a penetration test or production-readiness certification. All Supabase inspection was read-only. No migration, data mutation, Auth setting, Render configuration, notification, or infrastructure was changed.

## Executive summary

TACResQ has a substantial role-based foundation: requester SOS, dispatcher assignments, per-unit status, consent-gated location, incident chat, persistent inbox, FCM delivery bookkeeping, routing proxy, and protected administrative role changes. The most important gaps are observability across API boundaries, individual review of privileged RPCs, unverified production configuration, and several database performance warnings.

## Findings and this PR's changes

### API operation auditing — implemented for server-mediated calls

`admin_audit_logs` records privileged role changes; it is not a general API audit trail. `fcm_delivery_attempts` already records notification/token delivery attempts and provider outcomes.

This PR adds `public.api_operation_logs` with correlation ID, provider, operation, source, outcome, HTTP status, duration, provider request ID, error code, and timestamp. RLS is enabled; no policies or direct `anon`/`authenticated` grants exist; the service role receives SELECT/INSERT only. No request/response body, credential, token, coordinate, incident narrative, URL, or contact detail is stored.

Laravel now validates or generates `X-Request-ID`, returns it in the response, and records Supabase Auth/RPC calls plus Geoapify/private OSRM route-preview outcomes. The logger is fail-open: telemetry failure does not break the operational request. Database writes are skipped if the server-only Supabase secret is not configured. This PR does not add or configure that secret.

**Coverage limitation:** Android and dispatcher web call Supabase directly for many operations. Those requests bypass Laravel and are not written to this application table by this change; Supabase platform/API logs remain the source for those direct requests. FCM remains covered by its existing delivery ledger. This is not complete end-to-end API tracing.

### Foreign-key indexes — migration added

The live performance advisor identified four unindexed FK columns:
- `fcm_delivery_attempts.profile_id`
- `incident_assignments.assigned_by`
- `incident_request_edit_logs.requester_id`
- `user_notifications.incident_id`

The migration adds a btree index for each. They support referencing-row lookups and relevant filters, but the inspected dataset was very small, so no measurable latency improvement is claimed.

Nine unused-index candidates were also reported. None is removed: low traffic does not establish that an index is safe to drop.

## Broader audit findings

### RLS and SECURITY DEFINER

The live security advisor reported five RLS-enabled tables without policies: `fcm_delivery_attempts`, `incident_messages`, `incident_request_edit_logs`, `incident_submission_keys`, and `user_notifications`. A read-only grants inspection confirmed that these tables had no direct table privileges granted to `anon` or `authenticated`; they are intended to be accessed through scoped RPCs or trusted server operations. Do not add broad policies merely to silence the advisor.

The advisor also reported 18 authenticated-callable SECURITY DEFINER functions. Authenticated-callable SECURITY DEFINER functions are not automatically vulnerabilities. Each needs an individual review of owner, fixed search path, explicit EXECUTE grants, actor/role checks, and row/object scoping. This PR does not blanket-revoke them or change their security mode. A pinned search path does not replace authorization.

The merged repository migrations for `api_units()` search-path hardening and RLS InitPlan optimization were not yet applied to live Supabase at the time of inspection.

### Auth password security

The Auth advisor reports leaked-password protection disabled. This is a project Auth setting, not a schema migration. It is documented for operator review; this PR does not change live Auth settings.

### Routing and location privacy

- Geoapify is the configured hosted routing option; private OSRM is an explicit alternative.
- The routing proxy sends responder-origin and assigned-incident destination coordinates to the selected provider. It does not forward the bearer token, incident ID, narrative, requester identity, or profile data.
- Geoapify requires its API key in the query string. Application code must continue suppressing full URL and exception logging; provider-side handling remains a separate data-recipient consideration.
- No automatic provider failover is appropriate because it would disclose the same coordinates to another recipient without a new user decision.
- Android fused location cannot reliably identify the underlying GNSS/Wi-Fi/cellular sensor. Store only a verified API/provider label if exposed; do not infer sensor provenance.
- Route preview remains disabled by default and is not turn-by-turn navigation, live traffic, flood/closure data, or proof of road passability.

### Notifications and emergency safety

- `user_notifications` is the durable in-app inbox; FCM is supplementary and best-effort.
- `fcm_delivery_attempts` already records attempts, status, provider message names, and sanitized error codes.
- The Render FCM dispatcher is not proof of successful device delivery. Production scheduling and physical-device delivery remain unverified.
- FCM must not be treated as a life-critical alert channel or delivery SLA.

### Documentation drift

`docs/API.md` describes an older optional Laravel REST adapter proposal and suggested tables. The current Android production path is Supabase RPC; the dashboard uses Supabase Auth/RPC directly, while Laravel hosts the dashboard, optional bridge, routing proxy, and FCM worker. Use `docs/SUPABASE_BACKEND.md`, `backend/README.md`, and `docs/IN_APP_ROUTING_FOUNDATION.md` for current contracts rather than treating the proposal's suggested tables as the live schema.

## Live database observations

The read-only migration ledger contained migrations through `20261116042927_responder_incident_claiming`. The merged `api_units()` search-path migration, merged RLS InitPlan migration, and this PR's migration are repository changes only until separately applied by an authorized operator.

The advisor snapshot was observed around 2026-11-16 06:39 UTC. Re-run advisors after approved migration application; advisor output is time-sensitive.

## Prioritized follow-up backlog

| Priority | Follow-up | Reason |
|---|---|---|
| P0 | Individually review all authenticated-callable SECURITY DEFINER RPCs | Blanket privilege changes could break or expose workflows |
| P0 | Review and enable leaked-password protection | Live project Auth setting; operator-controlled |
| P1 | Extend audit coverage to direct Android/dashboard Supabase calls | These calls bypass Laravel |
| P1 | Add append-only assignment lifecycle event history | Separate business audit model requiring transaction and visibility design |
| P1 | Validate FCM scheduling/device delivery with synthetic accounts | CI cannot prove external delivery |
| P1 | Validate location freshness and foreground lifecycle on physical devices | OEM, permission, battery, and process behavior |
| P2 | Evaluate unit presence and explicit location-sharing sessions | Requires agreed semantics and retention policy |
| P2 | Add durable image attachment retry after process death | Client encrypted-storage/lifecycle work |
| P2 | Revisit index utilization after representative staging workload | Avoid decisions from a nearly empty dataset |

## Validation requirements

- Clean PostgreSQL migration replay includes the new migration.
- Laravel tests cover correlation IDs, metadata-only payloads, missing-secret behavior, and fail-open audit writes.
- Android, dashboard, Laravel integration, source/security guardrails, and RPC grant contract pass on the exact PR head.
- Live migration application, live Auth configuration, Render secret provisioning, real provider calls, and device/UI validation are outside this PR.
