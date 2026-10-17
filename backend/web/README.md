# TACRU Response Operations

A responsive dispatcher console for the TACResQ school-project prototype. The Laravel Docker image builds this Vite + React app into `public/dispatch/` and serves it at `/dispatch/`. The browser uses the existing Supabase Auth and RPC contract; Laravel's own API/PostgreSQL data path is unchanged.

## Run locally

1. Install Node.js 22+ and pnpm 10.18.0.
2. From this directory, run `pnpm install`.
3. Copy `.env.example` to `.env.local` and fill in `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` from the project's existing client configuration. These are browser-facing values. Never use a service-role/secret key here.
4. Run `pnpm dev` and open the Vite URL shown in the terminal.
5. `pnpm build` runs TypeScript checking and creates the static site in `dist/`.

For local Vite development, the `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` values are read from `.env.local`. In the Laravel deployment, configure `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` on the backend service instead; Laravel provides these browser-safe values through `/dispatch/config.json` at runtime, so they are not baked into the Docker build.

## Supabase behavior

After sign-in, the site calls `api_me()` and only renders the dispatch board for a profile with the `dispatcher` role. The board reads `api_all_incidents()` and `api_units()`; assigning a pending report calls `api_assign_incident(p_id, p_user_id)` and is confirmed in the interface before the write. Authorization and row-level security remain enforced by Supabase.

No account creation or role changes are offered here. Public sign-up is requester-only in the existing project; dispatcher, responder, and driver accounts must be provisioned through a trusted administrator. If the existing tables are empty, the board displays an empty state until reports and units exist.

## Prototype boundaries

This is a school prototype, not an operational emergency-dispatch service. It does not add FCM push, continuous/background GPS tracking, offline write synchronization, or map-based fleet visualization. Coordinates open in an external map link; assignment is the only write exposed by this console.
