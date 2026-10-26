# TACResQ Admin Access Verification

**Verified:** 2026-10-26  
**Repository:** `RamProjector/TACResQ-School-Project`  
**Live Supabase project inspected:** `TACResQ-School-Project` (`kttguamhznoflzagfutr`)  
**Scope:** Read-only inspection. No live configuration, accounts, roles, or database objects were changed.

## Answer: there is no verified website admin-panel URL

The deployed URL visible in the user's screenshot is:

- https://tacresq-school-project.onrender.com

It serves the TACRU dispatcher sign-in page. The following repository files were inspected on the `main` branch:

- `backend/src/routes/web.php`: declares `/`, `/runtime-config.json`, and `/dispatch/config.json`; no `/admin` route is declared there.
- `backend/web/public/manus-routes.json`: lists only `/` titled `TACRU Dispatch Board`.
- `backend/web/README.md`: describes the dispatcher console, says it checks the Supabase `dispatcher` role, and says the console offers no account creation or role changes.
- `backend/web/src/pages/Home.tsx`: presents a `DISPATCH ACCESS` sign-in form and explains that dispatcher accounts must be provisioned by a trusted administrator.
- `backend/web/src/App.tsx`: loads the dispatcher dashboard app; no separate admin app is wired here.

Repository links:
- [Web routes](https://github.com/RamProjector/TACResQ-School-Project/blob/main/backend/src/routes/web.php)
- [Dashboard route manifest](https://github.com/RamProjector/TACResQ-School-Project/blob/main/backend/web/public/manus-routes.json)
- [Dashboard README](https://github.com/RamProjector/TACResQ-School-Project/blob/main/backend/web/README.md)
- [Dashboard sign-in / gate code](https://github.com/RamProjector/TACResQ-School-Project/blob/main/backend/web/src/pages/Home.tsx)

## Live Supabase role verification

Read-only queries against the connected project confirmed:

- `public.profiles.role` has a database CHECK constraint allowing only `requester`, `responder`, `driver`, and `dispatcher`.
- The live `profiles` table has no `admin` role in that constraint and no admin-specific role column.
- The profile table's self-update policy limits its target rows to responder/driver profiles; a trigger blocks authenticated clients from changing profile identity or role fields.
- The live profile role counts were: 2 requesters, 1 responder, 1 driver, and 1 dispatcher. This records aggregate counts only, not account identities.
- The live `api_me()` function returns the signed-in user's profile and role; it does not provide an admin panel.
- The live migration history currently lists migrations through `20261023103737_per_responder_assignment_status`. The newer location-hardening migrations under review in PR #169 have not been applied to the live project.

These facts support the conclusion that **a dedicated TACResQ web admin role/panel is not currently implemented in the inspected live schema and current web source**. This does not mean the Render account owner or Supabase project owner lacks provider-level administrative access; those are separate provider dashboards.

## What to use now

- **Dispatcher console:** https://tacresq-school-project.onrender.com
- **Render service/provider administration:** https://dashboard.render.com/
- **Supabase project/provider administration:** https://supabase.com/dashboard/project/kttguamhznoflzagfutr

The latter two are infrastructure consoles, not an in-app TACResQ admin panel. Do not try guessed paths such as `/admin`, and do not treat a public signup as an admin provisioning method.

## Recommended implementation, not yet applied

If TACResQ needs an in-product administrator interface, implement it as a separate feature via a reviewed PR:

1. Add an explicit admin role to the data model only after defining who is allowed to grant/revoke it.
2. Provision the first admin out-of-band through a trusted owner-controlled process; never via public signup or user-editable metadata.
3. Enforce admin authorization in backend/database functions and RLS, not only by hiding frontend pages.
4. Add a distinct admin route/dashboard for user-role management and audit logs, with least privilege and auditable role changes.
5. Add tests proving ordinary authenticated users cannot grant themselves admin, promote another user, or access admin operations.
6. Keep dispatcher permissions separate from administrator permissions.

No admin role, route, credentials, or live database changes were created during this verification.
