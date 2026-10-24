# TACResQ owned-backend migration plan

## Decision

TACResQ will move toward one backend that the project controls: Laravel on Render plus PostgreSQL, with server-owned authentication, authorization, incident workflows, and audit history. Supabase must not remain a second writable source of truth after cutover.

This document is an implementation plan, not a claim that migration is complete. The current Android and dispatcher clients use Supabase directly for real operations, so removing Supabase before parity tests and a controlled cutover would break them.

## Current-state audit (2026-10-24)

- Android's real API path is configured for direct Supabase access. Debug builds can use the fake API; this must not be confused with real-backend validation.
- The Render Laravel service currently serves the dispatcher console and runtime browser-safe Supabase configuration. The API routes in `backend/src/routes/api.php` also contain Laravel Sanctum and Eloquent endpoints, but these are not yet the single backend used by the Android app.
- The Render service is on the free plan and has no configured health-check path. Laravel's bootstrap defines `/up`, so the service can use that endpoint after a deployment check.
- A Render PostgreSQL instance named `TACResQ_DB` already exists, but it is on the free plan, is scheduled to expire on 2026-11-15, and currently has a broad external IP allow-list. Treat it as disposable staging only; do not call it production-ready or put real emergency/personal data there.
- Supabase currently holds the live project database and 23 applied migrations. It includes request idempotency, one-active-request enforcement, edit/cancel workflows, dispatch assignment, responder-specific assignment state, and location tracking/controls that must be accounted for.
- Supabase security advisors have flagged five callable SECURITY DEFINER RPCs, two internal tables with RLS enabled but no policies, and disabled leaked-password protection. These are audit items to verify, not proof that every flagged object is exploitable. No live permissions or auth settings were changed during this audit.

## Target architecture

```text
Android app / Dispatcher web
        | HTTPS + short-lived bearer access token
        v
Laravel API on Render
  - authentication and token/session validation
  - role and resource authorization
  - request validation, rate limiting, idempotency
  - workflow transactions and audit events
        | private connection / least-privilege DB role
        v
TACResQ PostgreSQL (single source of truth)
  - users and roles
  - incidents and idempotency keys
  - assignments and responder state
  - incident status/audit history
  - location updates with strict access/retention
```

No client may connect to PostgreSQL directly. Secrets and database credentials must never be shipped in Android builds or browser configuration.

## Required migration phases

### Phase 0 — protect current service and inventory contracts

- Preserve current Supabase deployment while recording all client calls, RPC input/output shapes, role rules, state transitions, uniqueness constraints, and current data volume.
- Verify the current Render deployment, `/up`, environment variables, and the existing database's tables/migration state without copying production data into a disposable database.
- Export schema and migration history through supported tools; document a rollback point.
- Do not change Supabase grants, Auth settings, production data, or client base URLs as part of the inventory.

### Phase 1 — establish durable owned infrastructure

- Select a PostgreSQL plan with an explicit retention/backup and restore path before storing real data. The existing expiring free database is for disposable integration tests only.
- Use Render's internal connection string from the same region; require TLS for external administration, narrow external access, and separate runtime and migration credentials where supported.
- Keep secrets in Render environment configuration. Never commit them to GitHub or embed them in APKs.
- Configure `/up` health checks and monitor deploys before switching any client traffic.

### Phase 2 — schema and identity

- Create versioned Laravel migrations for users/profiles, incidents, submission idempotency, assignments, status/audit logs, and responder location records.
- Preserve the existing user-facing role model (requester, responder/driver, dispatcher, administrator) and make elevated role grants administrator-only. Public registration must never accept a role.
- Store passwords using Laravel's supported password hashing; enforce verified email and safe password-reset/session flows. Use revocable, expiring access tokens and rate-limit login/reset endpoints.
- Never treat a client-provided user ID, role, requester ID, assignment ID, or status as authorization evidence.
- Use unique constraints and transactions for one-active-request rules, idempotent SOS retries, dispatch assignment, and legal status transitions.

### Phase 3 — API parity, one contract at a time

Implement and test these groups in sequence:
1. Auth: register, verified sign-in, sign-out, current profile, credential recovery.
2. Requester: create SOS, idempotent retry, one active request, edit/correction, cancellation limits, history.
3. Dispatcher: incident queue, unit roster, atomic assignment/reassignment, dispatch audit.
4. Responder/driver: duty availability, assigned incidents, validated status transitions.
5. Location: optional requester pin, explicit source and capture time, responder tracking only during authorized active assignments, scoped reads and retention.

Every endpoint needs tests for success, unauthenticated access, wrong role, another user's record, malformed values, replay/duplicate submissions, and concurrent state changes. The API must return safe inline errors and must not log passwords, tokens, or precise location payloads.

### Phase 4 — security and operational controls

- Deny by default; validate every request server-side and authorize each resource on every operation.
- Apply rate limits to authentication, SOS submission, location updates, and expensive queue/search endpoints.
- Enforce coordinate bounds, paired latitude/longitude, reasonable timestamps, and explicit GPS/manual/none source semantics.
- Minimize location collection; no continuous requester tracking. Restrict responder location visibility to authorized dispatch workflows and define retention/deletion rules.
- Add audit trails for role changes, assignments, cancellation, incident status transitions, and privileged actions.
- Add backups, restore drills, monitoring/alerting, dependency checks, and a documented incident-response owner before operational deployment.
- Do not claim emergency-service readiness from CI alone; require device/network failure tests, field validation, TACRU operational acceptance, and a staffed response procedure.

### Phase 5 — controlled client cutover

- Keep Supabase available until the new API passes parity and authorization tests.
- Migrate the dispatcher web console and Android app to the new API using a versioned contract; remove direct Supabase data operations only after both clients pass.
- Import existing records only after a reviewed export, schema mapping, integrity checks, and a rehearsed migration. Never dual-write incidents.
- Compare record counts, assignment/status histories, and audit data; retain a rollback window. Freeze writes or use a rehearsed delta migration during final cutover.
- Disable old credentials and direct access only after the cutover is verified and rollback is no longer required.

## Cutover exit criteria

- Clean-database migrations pass from zero to latest.
- Automated authorization matrix covers every role and resource boundary.
- Duplicate and concurrent SOS/assignment tests pass.
- Auth expiry/revocation, password reset, and account-role escalation tests pass.
- Android and dispatcher clients use only the owned API for live business data.
- Backup restoration and deployment rollback are rehearsed.
- TACRU has accepted the operating procedure and the service has an accountable monitor/response owner.

Until these criteria are met, the owned backend is development/staging and Supabase remains the current live source of truth.
