# TACResQ dispatcher host (Render)

Laravel 13 hosts the TACRU dispatcher console on Render. Supabase is the source of truth for authentication, data, and RPC operations; the temporary Render PostgreSQL database is not required.

## Deploy on Render

1. Push this repository to GitHub.
2. Create a **Web Service** from the GitHub repository. Choose **Docker** and set **Root Directory** to `backend`.
3. Add environment variables to the Web Service:
   - `APP_KEY`: generate a fresh key once with `printf 'base64:%s' "$(openssl rand -base64 32)"` and set the complete output. Keep it private and stable across deploys.
   - `APP_ENV=production`
   - `APP_DEBUG=false`
   - `LOG_CHANNEL=stderr`
   - `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`: the browser-safe settings for the TACRU dashboard at `/dispatch/`. Never use a service-role key.
   - Optional `OSRM_BASE_URL`: the internal URL of a separately hosted private OSRM service (for example, `http://tacresq-osrm:5000`). Do not set this to a public routing API. Route preview stays unavailable if the variable is blank or points to a public host. See [`routing/README.md`](routing/README.md) for the optional service; provisioning requires separate authorization and is not part of this deployment.
4. Deploy. The container clears Laravel's config cache and serves HTTP on Render's assigned `$PORT`; it does not connect to or migrate a Render database.
5. Check `https://<your-service>.onrender.com/up`; expected response is `200`.
6. Open `https://<your-service>.onrender.com/dispatch/` for the TACRU dashboard. Its `/runtime-config.json` endpoint returns only the configured Supabase URL and publishable key, with caching disabled. `/dispatch/config.json` remains an alias.
7. Configure the Android app for direct Supabase mode: `USE_FAKE_API = false` and `USE_SUPABASE_API = true`.

Render free instances may spin down when idle; first request after inactivity can take a while. Supabase remains independent of Render instance lifecycle.

The mobile route-preview proxy validates the caller's Supabase session and responder/driver assignment using `api_me` and `api_my_incidents`, then derives the destination from the authorized incident. It posts only the current origin and trusted destination coordinates to a private OSRM service; the body excludes the bearer token, incident ID, narrative, and profile data. Coordinates are not placed in the OSRM URL, and the optional router container disables access logging. With `OSRM_BASE_URL` unset, route previews return an unavailable response; Android routing is also disabled by default and requires an explicit build flag. External Maps remains a user-invoked fallback. No live Render environment variable, private service, graph volume, or deployment was changed.

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

## API and safety notes

- Auth uses Laravel Sanctum personal access tokens; logout deletes the current token.
- Self-registration always creates a requester. Elevated roles should be created through a trusted administrative process, not public registration.
- Role checks, ownership checks, allowed status transitions, coordinate bounds, paired GPS/null semantics, and available-unit checks are enforced server-side.
- An SOS may include one optional coordinate pair, measured accuracy, and the device fix timestamp, or no pin plus an optional 500-character landmark note. No-GPS SOS submission is valid; the backend does not synthesize coordinates. Requester location is attached to the incident only, not continuously tracked.
- Assignments and status changes use database transactions/row locks to prevent conflicting dispatch operations.
- FCM client registration and a trusted, server-only HTTP v1 sender are implemented. Delivery remains unverified until the private Render worker is configured; validate it without sending by running `php artisan tacresq:dispatch-fcm --check-config`. The starter still does not include offline write synchronization or guaranteed emergency delivery. Before real emergency use, add operational monitoring, rate limits, backups, abuse controls, and review the privacy/security design.
- Never commit `.env`, production credentials, or real personal/incident data to Git.


## TACRU dispatcher web console

The same Laravel service serves the responsive dispatcher console at `/dispatch/`. Its source is in `backend/web/`; the Dockerfile installs the pinned pnpm toolchain, builds the React app under the `/dispatch/` base path, and copies the static assets into Laravel's public directory. The Laravel `GET /runtime-config.json` endpoint exposes only `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` at runtime with `Cache-Control: no-store`; `/dispatch/config.json` remains an alias.

The dashboard deliberately keeps its sign-in, incident/unit reads, and assignment RPC on Supabase. The Render Laravel service only hosts the dashboard and runtime configuration endpoint. Configure the Supabase URL and **publishable** key on the Laravel service; do not use a Supabase service-role/secret key.

For local Compose setup, copy `.env.example` to `.env`, then set the Supabase URL and publishable key along with a generated `APP_KEY`. To run the frontend separately during UI development, use `backend/web/README.md`: copy `web/.env.example` to `web/.env.local`, fill in the same browser-safe Supabase values, and run `pnpm dev` from `backend/web/`.

The console remains dispatcher-only, requires an administrator-provisioned dispatcher profile, and is a school prototype—not an operational emergency service. It does not provide offline write synchronization or guaranteed emergency delivery; FCM delivery is handled by the separate trusted worker rather than the public dashboard route.


## Automated CI tests

GitHub Actions runs `tools/test_workflow.py`, type-checks/builds the dashboard with its `/dispatch/` base path, builds and starts the Docker Compose Laravel/PostgreSQL stack with disposable test settings, runs the Laravel feature tests, and smoke-tests the served dashboard and `/runtime-config.json` response. The integration job uses placeholder Supabase settings and an ephemeral database; it does not contact or write to the project's live Supabase database.
