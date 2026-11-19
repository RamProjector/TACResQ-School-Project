# TACResQ dependency health monitoring: research and implementation plan

## Current state

Laravel exposes `/up` through Laravel's built-in health route. This confirms that the Laravel application booted; it does not prove that Supabase Auth, the Supabase Data API/PostgreSQL RPC path, FCM delivery, or route providers are available.

The application currently has:
- Supabase Auth and RPC traffic through the Supabase bridge for selected backend operations.
- FCM delivery through a signed internal Render trigger and a separate dispatcher.
- Route preview through a configured Geoapify endpoint or optional private OSRM service.
- Bounded connection/total request timeouts for the bridge and route providers.
- Sanitized failures and operation audit records for route-preview provider calls.

No independent dependency monitor, scheduled probe, alert destination, or service-level objective is currently configured by this change.

## Implemented offline diagnostic boundary

The Laravel backend now exposes the operator CLI command `php artisan tacru:dependency-health`. It is intentionally a **configuration-presence report**, not a live health probe:

- It reports whether the app booted and whether required configuration values are present.
- It reports routing as `disabled`, `configuration_present`, or `not_configured`.
- It does not open network connections, query the database, claim queue work, send notifications, or call routing providers.
- It never prints configured URLs, keys, service-account data, tokens, or other credential values.
- A `configuration_present` result must not be interpreted as proof that the provider is reachable or healthy.

The command is local/operator-invoked only. It is not exposed as an HTTP route, is not scheduled, and does not change the public `/up` response. Runtime probe authorization, outbound checks, alerting, and dashboards remain unimplemented.

## Design principles

1. **Separate liveness from dependency readiness.** Keep `GET /up` cheap and local. A dependency outage must not make the process appear dead to the platform or trigger restart loops.
2. **Probe the real path, not merely DNS/TCP.** Use the same configured host, TLS verification, and key type as the production client.
3. **Never create or mutate emergency data to test availability.** No synthetic SOS, assignment, status transition, user signup, password reset, notification, or route request against a real incident.
4. **Use read-only, bounded probes.** Every network probe has a short connection timeout, a total timeout, a strict response-size limit, and no automatic retry storm.
5. **Do not expose topology or secrets publicly.** Public `/up` stays minimal. Detailed dependency status belongs in restricted operator monitoring, with no URLs, project refs, credentials, exception strings, user IDs, incident IDs, or coordinates in the response.
6. **Distinguish degraded from failed.** A failed notification provider does not mean authentication is down; a routing outage must not block SOS creation or dispatch workflows that do not require route preview.

## Component probe matrix

| Component | Safe signal | What it proves | What it does not prove |
|---|---|---|---|
| Laravel process | Existing `GET /up` | App booted and can answer HTTP | Any external dependency works |
| Supabase Auth | `GET /auth/v1/health` using the configured publishable key in the `apikey` header | Auth gateway responds | A particular user's credentials or session is valid |
| Supabase Data API / database | The dedicated `public.api_dependency_health()` RPC (implemented in `20261119110000_public_data_free_dependency_health_rpc.sql`) through the same bridge/auth path | API gateway, JWT validation, RPC routing, and database execution respond | All business RPCs, writes, or realtime services work |
| FCM | Local dispatcher configuration validation plus provider OAuth token acquisition without sending a message; separately inspect delivery outcomes | Credentials can be loaded and Google token endpoint can be reached | A device received a push or that FCM will deliver a future message |
| Routing | Only when routing is enabled: request a route between fixed, non-sensitive synthetic coordinates against the configured provider; otherwise report disabled | Provider can calculate a basic route | Real incident destination accessibility, road closures, traffic, or emergency-vehicle suitability |

Supabase documents `/auth/v1/health` as a way to check whether a publishable key works. The Auth health endpoint is not an authenticated-user test. The Data API root is not a valid publishable-key health probe; do not use it as a substitute for a dedicated read-only RPC.

## Recommended implementation sequence

### Phase 1 — observability contract
- Define stable component identifiers: `app`, `supabase_auth`, `supabase_rpc`, `fcm`, `routing`.
- Define states: `healthy`, `degraded`, `unavailable`, `disabled`, and `not_configured`.
- Record probe timestamp, duration, outcome class, and a correlation ID only.
- Never store raw provider response bodies, bearer tokens, API keys, coordinates, or incident/user identifiers.

### Phase 2 — restricted operator probe
- Add a separate operator-only probe command or signed internal endpoint. Do not add detailed status to the public `/up` response.
- Authenticate the probe with a dedicated high-entropy secret, constant-time signature comparison, timestamp window, request UUID replay protection, and strict rate limiting.
- Require an explicit opt-in for outbound dependency checks; default to local/configuration-only checks.
- Keep each probe independent so one timeout does not prevent the remaining components from being checked.
- Do not run expensive dependency probes on every user request.

### Phase 3 — safe probe implementations
- Auth: GET `/auth/v1/health`, publishable key only, no user credentials.
- RPC: implemented `public.api_dependency_health()` as a `SECURITY INVOKER`, immutable SQL function pinned to `pg_catalog`; it returns only the fixed literal `tacresq_database_ok`, reads no application tables, and mutates no state. `anon` and `authenticated` can execute it with the publishable key; `PUBLIC` and `service_role` receive no function grant. Runtime checks execute it under both client roles and verify the privilege boundary. Never use a business RPC as a health probe.
- FCM: validate service-account loading and obtain an OAuth access token; do not call the FCM send endpoint.
- Routing: use fixed synthetic coordinates only, never coordinates from an incident or user. If the provider charges per request, keep the probe disabled until cost/allowance is verified.

### Phase 4 — alerting and operations
- Alert on sustained failure windows, not a single transient timeout; use per-component thresholds and cooldowns.
- Alert separately for Auth, database RPC, FCM, and routing. Do not report a global outage when only an optional component is degraded.
- Record when the alert fired, recovered, and was acknowledged. Avoid alert storms.
- Publish operator runbooks for Auth outage, database/RPC outage, FCM outage, and routing outage.
- Test alerting with local fakes and controlled failure injection; never disable production services to test the monitor.

## Safety and deployment constraints

- No Render environment variables, secrets, Supabase settings, Cron schedules, or production services are changed by this plan.
- Do not add a service-role key to Render or Android. The current backend environment does not have a verified service-role secret.
- Do not claim FCM delivery from OAuth token acquisition alone.
- Do not treat a successful route probe as proof of a safe or passable emergency route.
- A dependency monitor must not block requester SOS submission. If the database itself is unavailable, the client must clearly show that the request was not server-confirmed and must not display it as dispatched.
- The data-free database health RPC and its static/runtime privilege tests are committed. The CLI now supports explicit `--live` checks for Supabase Auth and that RPC with bounded timeouts, sanitized outcomes, and no retries. The administrator portal now displays bounded, read-only Supabase Auth and database RPC results after the existing server-verified admin profile gate. The browser uses only the publishable key and current administrator session; no service-role credential or application data is involved. A static CI contract protects the role gate and probe scope. Durable diagnostic audit history, FCM/routing live probes, alerting, and scheduled monitoring remain future work; do not imply they are active.

## References

- Laravel health route and `DiagnosingHealth` event: https://laravel.com/docs/13.x/deployment#the-health-route
- Supabase Data API architecture and endpoint: https://supabase.com/docs/guides/api
- Supabase API keys and publishable-key health-check guidance: https://supabase.com/docs/guides/getting-started/api-keys
- Supabase Auth rate limits and endpoint behavior: https://supabase.com/docs/guides/auth/rate-limits
