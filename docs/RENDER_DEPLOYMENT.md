# Render backend deployment and migrations

The TACResQ Render web service builds from `backend/Dockerfile` and runs `backend/docker/entrypoint.sh`.

## Startup behavior

Before the Laravel API begins serving requests, the entrypoint clears/caches configuration and runs the checked-in Laravel migrations with `--force`. The migration runner holds a PostgreSQL session advisory lock for the duration of the command so overlapping Render instances do not attempt to apply the same migration concurrently.

If a migration fails, the runner exits non-zero and the entrypoint does not start the web server. This is intentionally fail-closed: an instance must not serve API traffic against a partially migrated schema.

The migrations include the incident location fields, owned-backend workflow tables, one-active-requester invariant, and Laravel `cache` / `cache_locks` tables required by the database-backed rate limiter. CI replays these migrations against a clean PostgreSQL database and runs Laravel feature tests.

## Post-deploy verification

After a deployment that changes migrations:

1. Confirm the Render deploy is marked live and inspect its application logs for successful migration output.
2. Read the `public.migrations` ledger in the Render PostgreSQL database and confirm all expected migration versions are present.
3. Confirm `cache` and `cache_locks` exist before expecting the database-backed rate limiter to work.
4. Verify `GET /up` returns healthy and an unauthenticated `GET /api/bridge/me` returns the expected 401 (not a database error).
5. Run authenticated end-to-end tests using synthetic accounts/incidents before directing any real user traffic.

Do not run production-like emergency workflows on the free/staging service. A successful CI run or Render deploy does not replace physical-device testing, operational monitoring, or TACRU's acceptance of the service.
