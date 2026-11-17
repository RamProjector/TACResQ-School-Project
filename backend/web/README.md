# TACResQ Admin Portal

A responsive administrator-only portal for the TACResQ school-project prototype. The Laravel Docker image builds this Vite + React app into `public/admin/` and serves it at `/admin/`. Dispatchers do not use this web portal for incident operations; the incident queue, team assignments, and shared response progression remain in the TACResQ Android app.

## Run locally

1. Install Node.js 22+ and pnpm 10.18.0.
2. From this directory, run `pnpm install`.
3. Copy `.env.example` to `.env.local` and fill in `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` from the project's existing client configuration. These are browser-facing values. Never use a service-role/secret key here.
4. Run `pnpm dev` and open the Vite URL shown in the terminal.
5. `pnpm build` runs TypeScript checking and creates the static site in `dist/`.

For local Vite development, the `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` values are read from `.env.local`. In the Laravel deployment, configure `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` on the backend service instead; Laravel provides these browser-safe values through `/runtime-config.json` at runtime.

## Supabase behavior

After sign-in, the site calls `api_me()` and renders the Admin Console only for a profile with the `admin` role. The Admin Console uses administrator-only RPCs for paginated user management, role changes, and audit-log review. Authorization and row-level security remain enforced by Supabase.

No account creation or administrator role changes are offered here. Public sign-up remains requester-only; elevated accounts must be provisioned through a trusted administrator process. Dispatcher accounts are directed to the TACResQ Android app.

## Scope and prototype boundaries

This web portal is for administrator account governance only. It does not contain an incident queue, response-unit board, incident assignment controls, or dispatcher workflow. All dispatch operations are performed in the Android app to avoid competing web and mobile control surfaces.

This is a school prototype, not an operational emergency-dispatch service. It does not provide guaranteed emergency delivery or offline write synchronization.
