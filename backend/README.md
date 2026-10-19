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
4. Deploy. The container clears Laravel's config cache and serves HTTP on Render's assigned `$PORT`; it does not connect to or migrate a Render database.
5. Check `https://<your-service>.onrender.com/up`; expected response is `200`.
6. Open `https://<your-service>.onrender.com/dispatch/` for the TACRU dashboard. Its `/dispatch/config.json` endpoint returns only the configured Supabase URL and publishable key, with caching disabled.
7. Configure the Android app for direct Supabase mode: `USE_FAKE_API = false` and `USE_SUPABASE_API = true`.

Render free instances may spin down when idle; first request after inactivity can take a while. Supabase remains independent of Render instance lifecycle.

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
- This starter does not include FCM notifications, live GPS tracking, throttling policy, user/role administration UI, or incident cancellation. Before real emergency use, add operational monitoring, rate limits, backups, abuse controls, and review the privacy/security design.
- Never commit `.env`, production credentials, or real personal/incident data to Git.


## TACRU dispatcher web console

The same Laravel service serves the responsive dispatcher console at `/dispatch/`. Its source is in `backend/web/`; the Dockerfile installs the pinned pnpm toolchain, builds the React app under the `/dispatch/` base path, and copies the static assets into Laravel's public directory. The Laravel `GET /dispatch/config.json` endpoint exposes only `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` at runtime with `Cache-Control: no-store`.

The dashboard deliberately keeps its sign-in, incident/unit reads, and assignment RPC on Supabase. The Render Laravel service only hosts the dashboard and runtime configuration endpoint. Configure the Supabase URL and **publishable** key on the Laravel service; do not use a Supabase service-role/secret key.

For local Compose setup, copy `.env.example` to `.env`, then set the Supabase URL and publishable key along with a generated `APP_KEY`. To run the frontend separately during UI development, use `backend/web/README.md`: copy `web/.env.example` to `web/.env.local`, fill in the same browser-safe Supabase values, and run `pnpm dev` from `backend/web/`.

The console remains dispatcher-only, requires an administrator-provisioned dispatcher profile, and is a school prototype—not an operational emergency service. It does not add live GPS tracking, FCM, offline write synchronization, or map-based fleet visualization.


## Automated CI tests

GitHub Actions runs `tools/test_workflow.py`, type-checks/builds the dashboard with its `/dispatch/` base path, builds and starts the Docker Compose Laravel/PostgreSQL stack with disposable test settings, runs the Laravel feature tests, and smoke-tests the served dashboard and `/dispatch/config.json` response. The integration job uses placeholder Supabase settings and an ephemeral database; it does not contact or write to the project's live Supabase database.
