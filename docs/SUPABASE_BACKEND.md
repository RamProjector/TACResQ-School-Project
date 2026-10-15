# TACResQ Supabase backend

The Android app uses Supabase Auth for accounts and Postgres RPC functions for app operations. The connected project schema is defined by the ordered migrations in `supabase/migrations/`.

## Data model

- `profiles`: one row per Supabase Auth user, TACResQ role, contact data, and field-unit duty status.
- `incidents`: requester, incident type/description, captured coordinates, status, and assigned field unit.
- `incident_status_logs`: append-only-by-trigger status history for audit/reporting.
- `location_logs`: storage for responder/driver GPS fixes. The current app does not yet collect continuous or background fixes.

All four tables have Row Level Security enabled. Public registration creates a `requester` profile only. Privileged roles must be provisioned by a trusted administrator; setting a field role requires setting `duty_status` in the same update to satisfy the profile constraint.

## RPC contract used by Android

All RPC calls require a signed-in Supabase user and use `POST /rest/v1/rpc/<function>`.

| Function | Access / purpose |
|---|---|
| `api_me()` | Return the caller's profile. |
| `api_my_incidents()` | Return the caller's requester- or assigned-unit incidents. |
| `api_all_incidents()` | Dispatcher-only incident queue. |
| `api_units()` | Dispatcher-only responder/driver list. |
| `api_set_duty_status(p_duty_status)` | Field unit switches between `available` and `off_duty`; blocked with an active incident. |
| `api_create_incident(p_type,p_description,p_latitude,p_longitude,p_accuracy_m)` | Requester-only SOS submission. |
| `api_assign_incident(p_id,p_user_id)` | Dispatcher assigns a pending request to an available field unit. |
| `api_update_incident_status(p_id,p_status)` | Assigned unit advances a valid incident status. |
| `api_record_location(p_latitude,p_longitude,p_accuracy_m,p_recorded_at)` | Authenticated field-unit location write endpoint for a future client feature. |

A database trigger writes status history and updates unit duty status when an incident changes. Database guards reject invalid role operations and status transitions, including direct table updates that bypass the intended RPCs. The one narrowly scoped `SECURITY DEFINER` helper lives in the non-API-exposed `private` schema; the Supabase security advisor returned no findings after the hardening migration.

## App setup and role provisioning

1. Apply the numbered migrations in order.
2. Set `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` in `AppConfig.kt`. Use only a publishable key in the app; never use a service-role/secret key.
3. Register requester accounts in the app. For example, to promote a previously registered account:
   ```sql
   update public.profiles
   set role = 'dispatcher', duty_status = null
   where email = 'dispatcher@example.com';
   ```
4. Keep Supabase Auth email-confirmation settings in mind; a newly registered account may need to confirm email before the first sign-in.

## Current limitations

The app currently supports requester SOS submission, manual dispatcher assignment, duty-status controls, incident status progression, polling, and one-time GPS capture for the requester. It does **not** yet implement continuous/background responder tracking, map-based dispatcher fleet display, push notifications/FCM, an offline write queue, or a web dashboard. The location table and RPC are groundwork only. This implementation is a school prototype and is not an emergency-services production deployment.
