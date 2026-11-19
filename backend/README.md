# TACResQ admin portal host (Render)

For durable project decisions, current implementation status, and research references, see [`docs/project-memory/`](../docs/project-memory/README.md).

Laravel 13 hosts the TACResQ administrator portal on Render. Dispatch operations are handled in the Android app; this web portal is limited to administrator account management and audit review. Supabase is the source of truth for authentication, data, and RPC operations; the temporary Render PostgreSQL database is not required.

## Deploy on Render

1. Push this repository to GitHub.
2. Create a **Web Service** from the GitHub repository. Choose **Docker** and set **Root Directory** to `backend`.
3. Add environment variables to the Web Service:
   - `APP_KEY`: generate a fresh key once with `printf 'base64:%s' "$(openssl rand -base64 32)"` and set the complete output. Keep it private and stable across deploys.
   - `APP_ENV=production`
   - `APP_DEBUG=false`
   - `LOG_CHANNEL=stderr`
   - `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`: the browser-safe settings for the TACResQ Admin Portal at `/admin/`. Never use a service-role key.
   - `ROUTE_PREVIEW_ENABLED=false`: backend kill switch; leave disabled unless provider configuration, privacy review, synthetic tests, and device validation are complete.
   - `ROUTE_PROVIDER=geoapify`: current source default when routing is enabled. Add `GEOAPIFY_API_KEY` as a backend-only secret; never expose it to Android, the browser dashboard, or source control. Geoapify receives responder-origin and assigned-incident destination coordinates.
   - Optional `OSRM_BASE_URL`: only when `ROUTE_PROVIDER=osrm` is explicitly selected. Use a private internal OSRM hostname such as `http://tacresq-osrm:5000`; never point it at a public routing API. See [`routing/README.md`](routing/README.md) for the optional self-hosted service. Provisioning requires separate authorization and may require paid infrastructure.
4. Deploy. The container clears Laravel's config cache and serves HTTP on Render's assigned `$PORT`; it does not connect to or migrate a Render database.
5. Check `https://<your-service>.onrender.com/up`; expected response is `200`.
6. Open `https://<your-service>.onrender.com/admin/` for the TACResQ Admin Portal. Its `/runtime-config.json` endpoint returns only the configured Supabase URL and publishable key, with caching disabled. `/admin/config.json` is also available.
7. Configure the Android app for direct Supabase mode: `USE_FAKE_API = false` and `USE_SUPABASE_API = true`.

Render free instances may spin down when idle; first request after inactivity can take a while. Supabase remains independent of Render instance lifecycle.

The mobile route-preview proxy validates the caller's Supabase session and responder/driver assignment using `api_me` and `api_my_incidents`, then derives the destination from the authorized incident. Provider selection is server-side: Geoapify is the configuration default and receives the origin/destination waypoints plus the backend-only API key; private OSRM is an explicit alternative selected with `ROUTE_PROVIDER=osrm` and a private `OSRM_BASE_URL`. For OSRM, coordinates are sent in a POST JSON body rather than its URL, and the optional router container disables access logging. The proxy does not forward the bearer token, incident ID, narrative, or profile data. No automatic provider failover occurs because it would disclose location coordinates to another provider. Backend `ROUTE_PREVIEW_ENABLED` and Android route preview both default to disabled; Android also requires an explicit build flag. External Maps remains a user-invoked fallback. A preview is static driving guidance, not turn-by-turn navigation or proof of road passability, closures, flooding, emergency-vehicle access, or current traffic. No live Render environment variable, provider secret, private service, graph volume, or deployment was changed.

## Seed demo users (optional; demo password is not production-safe)

The seeder creates four demo accounts with password `demo123`. **Do not run this on a public production service** unless you deliberately want these well-known credentials. For local Docker Compose, first set a Laravel key in your shell and start the services, then seed:

```sh
export APP_KEY="base64:$(openssl rand -base64 32)"
docker compose up -d --build
docker compose exec api php artisan db:seed --class=Database\\Seeders\\DemoUsersSeeder
```

Run these commands from `backend/`. The included `compose.yaml` is for local development only; Render deployment uses the dashboard environment variables above.

## Local verification

Run the existing offline behavioral test:

```sh
python3 tools/test_workflow.py
```

To exercise the live HTTP API, first add demo users to a test database and start the service, then:

```sh
python3 tools/test_workflow.py --base-url http://127.0.0.1:8000/api/
```

## Dependency health monitoring

The built-in `/up` endpoint confirms Laravel booted; it does not verify Supabase Auth, database RPCs, FCM, or routing. Run `php artisan tacru:dependency-health` for a local, configuration-only report. It performs no network requests, database queries, queue claims, or provider actions, and it never prints secret values. `configuration_present` is not a provider health guarantee. The component-level monitoring research and staged implementation plan is in [`docs/security/dependency-health-monitoring-research-and-implementation-plan.md`](../docs/security/dependency-health-monitoring-research-and-implementation-plan.md). No external dependency probes, alerts, or scheduled monitoring are active.

## API and safety notes

- Auth uses Laravel Sanctum personal access tokens; logout deletes the current token.
- Self-service account recovery: `POST /logout-all` revokes every Laravel Sanctum personal access token; `POST /change-password` requires the current password and a confirmed replacement, revokes prior Sanctum tokens, and issues one fresh token. These legacy Laravel API endpoints do not revoke Supabase Auth sessions.
- Self-registration always creates a requester. Elevated roles should be created through a trusted administrative process, not public registration.
- Role checks, ownership checks, allowed status transitions, coordinate bounds, paired GPS/null semantics, and available-unit checks are enforced server-side.
- An SOS may include one optional coordinate pair, measured accuracy, and the device fix timestamp, or no pin plus an optional 500-character landmark note. No-GPS SOS submission is valid; the backend does not synthesize coordinates. Requester location is attached to the incident only, not continuously tracked.
- Assignments and status changes use database transactions/row locks to prevent conflicting dispatch operations.
- FCM client registration and a trusted, server-only HTTP v1 sender are implemented. Preserve the existing Render Firebase service-account secret file and `GOOGLE_APPLICATION_CREDENTIALS` path. The `tacresq:dispatch-fcm` command exists, but the current web-service Docker startup does not run it; automatic delivery remains unverified. See [`docs/FCM_RENDER_EXECUTION_AUDIT.md`](../docs/FCM_RENDER_EXECUTION_AUDIT.md) for the deployment audit, free-tier constraints, and safe validation sequence. The `--check-config` option validates ADC without sending a push. The starter still does not include offline write synchronization or guaranteed emergency delivery. Before real emergency use, add operational monitoring, rate limits, backups, abuse controls, and review the privacy/security design.
- Never commit `.env`, production credentials, or real personal/incident data to Git.


## TACResQ administrator web portal

The same Laravel service serves the responsive administrator portal at `/admin/`. Its source is in `backend/web/`; the Dockerfile installs the pinned pnpm toolchain, builds the React app under the `/admin/` base path, and copies the static assets into Laravel's public directory. The Laravel `GET /runtime-config.json` endpoint exposes only `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` at runtime with `Cache-Control: no-store`; `/admin/config.json` is an alias.

The portal uses Supabase Auth and administrator-only user-management and audit RPCs. It does not expose the incident queue, field-unit board, or assignment controls; dispatchers must use the TACResQ Android app. The Render Laravel service only hosts the portal and runtime configuration endpoint. Configure the Supabase URL and **publishable** key on the Laravel service; do not use a Supabase service-role/secret key.

For local Compose setup, copy `.env.example` to `.env`, then set the Supabase URL and publishable key along with a generated `APP_KEY`. To run the frontend separately during UI development, use `backend/web/README.md`: copy `web/.env.example` to `web/.env.local`, fill in the same browser-safe Supabase values, and run `pnpm dev` from `backend/web/`.

The portal is administrator-only and remains a school-project prototype—not an operational emergency service. Dispatcher accounts are shown a clear message directing them to the Android app. It does not provide offline write synchronization or guaranteed emergency delivery; FCM delivery is handled by the separate trusted worker rather than the public dashboard route.


## Automated CI tests

GitHub Actions runs `tools/test_workflow.py`, type-checks/builds the admin portal with its `/admin/` base path, builds and starts the Docker Compose Laravel/PostgreSQL stack with disposable test settings, runs the Laravel feature tests, and smoke-tests the served dashboard and `/runtime-config.json` response. The integration job uses placeholder Supabase settings and an ephemeral database; it does not contact or write to the project's live Supabase database.

The free-compatible Supabase Cron trigger is documented in [docs/FCM_SUPABASE_CRON_TRIGGER.md](../docs/FCM_SUPABASE_CRON_TRIGGER.md). It is not active until the Render webhook secret, Supabase Vault secrets, pg_cron/pg_net extensions, and scheduled job are configured by an authorized operator. It is best-effort only; the in-app inbox remains authoritative.
