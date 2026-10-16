# TACResQ API backend (Render)

Laravel 11 + Sanctum API implementing [`../docs/API.md`](../docs/API.md), prepared for deployment as a **Render Web Service** with a managed Render PostgreSQL database. The Android app can use the same endpoints and JSON shapes as its Retrofit interface.

## Deploy on Render

1. Push this repository to GitHub.
2. In Render, create a **PostgreSQL** database in the region you want. Keep its internal connection URL private.
3. Create a **Web Service** from the GitHub repository. Choose **Docker** and set **Root Directory** to `backend`.
4. Add environment variables to the Web Service:
   - `APP_KEY`: generate a fresh key once with `printf 'base64:%s' "$(openssl rand -base64 32)"` and set the complete output. Keep it private and stable across deploys.
   - `APP_ENV=production`
   - `APP_DEBUG=false`
   - `LOG_CHANNEL=stderr`
   - `DATABASE_URL`: the Render PostgreSQL **internal** database URL (copy it from the database page; do not commit it).
5. Deploy. The container runs `php artisan migrate --force` before serving HTTP on Render's assigned `$PORT`.
6. Check `https://<your-service>.onrender.com/up`; expected response is `200`.
7. Set the Android app's `BASE_URL` to `https://<your-service>.onrender.com/api/` and set `USE_FAKE_API = false`.

Render free instances may spin down when idle; first request after inactivity can take a while. Keep the database in the same region and use its internal URL. Configure backups/retention according to the needs of your deployment.

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
- Role checks, ownership checks, allowed status transitions, coordinate bounds, and available-unit checks are enforced server-side.
- Assignments and status changes use database transactions/row locks to prevent conflicting dispatch operations.
- This starter does not include FCM notifications, live GPS tracking, throttling policy, admin UI, or incident cancellation. Before real emergency use, add operational monitoring, rate limits, backups, abuse controls, and review the privacy/security design.
- Never commit `.env`, production credentials, or real personal/incident data to Git.
