# TACResQ School Project — analysis

## Repository shape

The repository contains a Kotlin/Jetpack Compose Android application and a separate browser-based TACRU dispatcher dashboard. The Android Gradle project and wrapper use Android API 37. The mobile screens define requester, responder/driver, and dispatcher roles; `app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt` is the closest existing interface model for a mobile dispatcher console.

The app has an in-memory fake backend and both Laravel and Supabase adapters. `AppConfig.kt` currently selects Supabase Auth plus Postgres RPCs; the offline fake backend must be selected intentionally for demos.

The dispatcher website is now located under `backend/web/` and is built into the Laravel image for `/dispatch/`. Laravel serves the frontend and supplies its public Supabase settings at runtime; the website continues to use Supabase Auth/RPCs for identity and dispatch data. The Laravel API's own PostgreSQL/Sanctum path remains separate and unchanged.

## Existing Supabase contract

The repository documents and migrates the `profiles`, `incidents`, `incident_status_logs`, and `location_logs` tables. Row Level Security and role/status guards are enabled. The dispatcher flow uses these RPCs:

- `api_me()` to read the signed-in profile.
- `api_all_incidents()` to read the dispatcher queue.
- `api_units()` to list responder/driver units.
- `api_assign_incident(p_id, p_user_id)` to assign a pending incident to an available unit.

The database enforces field-unit status transitions and records incident status history. Public sign-up is restricted to requesters; privileged dispatcher/unit accounts are provisioned by a trusted administrator. The website therefore provides sign-in, not privileged role creation, and uses only the browser-safe publishable key. No schema changes or service-role credentials are needed.

## Live database inspection

The matching Supabase project `TACResQ-School-Project` is active and healthy. The four documented application tables are present, RLS is enabled, and the inspected row counts were zero. The live schema also contains an `fcm_tokens` table not included in the repository's checked-in migrations; the website does not depend on it. Table inspection did not read or modify application records.

## Product boundaries

The repository describes this as a school prototype, not an operational emergency-service system. It does not implement FCM push delivery, continuous/background unit tracking, an offline write queue, or map-based fleet visualization. The website supports the existing dispatch workflow without implying those capabilities or weakening the database's authorization rules.
