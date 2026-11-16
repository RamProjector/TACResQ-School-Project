# Dispatcher Claimability Lifecycle Review — 2026-11-16

## Confirmed finding

The live `public.api_set_incident_claimable(bigint, boolean)` SECURITY DEFINER RPC checked the incident's status with an ordinary SELECT, then updated `is_claimable` without first locking the incident row.

A concurrent cancellation/completion could therefore occur after the dispatcher read the active status but before the claimability update. The later update could leave a terminal incident marked claimable. The field-claim RPC separately rejects terminal incidents, so this is a lifecycle-consistency defect rather than a demonstrated way to claim a closed incident.

The RPC also did not explicitly reject a null incident ID or null claimability argument.

## Correction in this PR

Migration `20261116200000_harden_dispatch_claimability_lifecycle.sql`:
- validates incident ID and claimability input;
- locks the incident row with `FOR UPDATE` before reading lifecycle state;
- rejects non-dispatchers before allowing them to contend on an incident row, then locks and re-reads the dispatcher profile after the incident lock;
- rejects callers whose current role is not dispatcher;
- permits enabling claims only while the incident is `pending` or `assigned`, while allowing dispatchers to clear stale flags;
- clears `is_claimable` automatically as status progresses to `en_route` or later, and records that transition;
- repairs existing stale claimability flags and records those repairs as `migration_backfill` audit events;
- revokes authenticated direct INSERT/UPDATE/DELETE on `incidents`, enforcing the RPC write path used by the Android and web clients;
- updates and returns the persisted row values;
- preserves authenticated-only execution grants and a pinned `pg_catalog` search path.

Lock order is incident then actor profile, matching the field-claim path in the current hardening work. This avoids introducing a reverse-order lock dependency with that RPC.

## Migration history test adjustment

The repository's migration-history source test previously required every pending PR migration to exist in every other branch. That made independent migration PRs order-dependent: merging one PR could cause another still-open PR's CI to fail because its expected migration was not present on that branch.

The test now requires all applied migrations, rejects unknown migration files, and allowlists known pending PR migrations as optional until merged. It does not claim any pending migration is deployed.

## Claimability audit trail

The migration adds a restricted `incident_claimability_logs` table. A real state change records the incident, actor profile, exact source RPC (`api_set_incident_claimable`), previous/new claimability values, incident status, and timestamp. No-op requests do not touch `updated_at` or create duplicate state-change entries. RLS is enabled, direct access is revoked from `PUBLIC`, `anon`, and `authenticated`, and only `service_role` receives SELECT access for trusted operational review. The historical numeric IDs are snapshots rather than foreign keys, so audit retention does not block profile or incident deletion. The SECURITY DEFINER RPC writes the row in the same transaction as the state change.

This is an internal audit record; it does not yet add a dispatcher-facing audit-history screen or read RPC. Retention and cleanup remain policy decisions and no automatic deletion is introduced.

## Validation and limitations

- A focused Python source-contract test is added to CI.
- Exact-head CI and PostgreSQL migration replay must pass before merge.
- This PR does not apply any migration to live Supabase.
- No production data, Render configuration, secrets, notifications, or infrastructure were changed.
- Static source-contract tests do not replace a two-session PostgreSQL race test. That remains a follow-up validation.

## Research comparison

- PostgreSQL row-level locking documents `SELECT ... FOR UPDATE` as the mechanism to serialize conflicting writers and lockers on the same row.
- PostgreSQL fires same-kind triggers in alphabetical trigger-name order; the `zzz_` BEFORE trigger is deliberately ordered after the existing incident guard so the guard validates the caller's original row change before the database clears claimability during status progression.
- OWASP API Security emphasizes server-side authorization and state validation for privileged API operations.
- Supabase documents that SECURITY DEFINER functions execute with elevated privileges, making explicit authorization and safe search paths important.

References:
- PostgreSQL explicit locking: https://www.postgresql.org/docs/current/explicit-locking.html
- PostgreSQL trigger behavior: https://www.postgresql.org/docs/current/trigger-definition.html
- OWASP API Security Top 10: https://owasp.org/www-project-api-security/
- Supabase database functions: https://supabase.com/docs/guides/database/functions
