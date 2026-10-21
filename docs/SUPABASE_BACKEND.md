# TACResQ Supabase backend

The Android app uses Supabase Auth for accounts and Postgres RPC functions for app operations. The connected project schema is defined by the ordered migrations in `supabase/migrations/`.

## Data model

- `profiles`: one row per Supabase Auth user, TACResQ role, contact data, and field-unit duty status.
- `incidents`: requester, incident type/description, optional paired GPS coordinates, measured accuracy, one-time capture timestamp, optional landmark notes, status, and assigned field unit.
- `incident_status_logs`: append-only-by-trigger status history for audit/reporting.
- `location_logs`: storage for responder/driver GPS fixes. The Android source implements consent-gated foreground fixes for the assigned incident while en route; background tracking is not implemented and device behavior has not yet been validated.

All four tables have Row Level Security enabled. Public registration creates a `requester` profile only. Privileged roles must be provisioned by a trusted administrator; setting a field role requires setting `duty_status` in the same update to satisfy the profile constraint.

## RPC contract used by Android

All RPC calls require a signed-in Supabase user and use `POST /rest/v1/rpc/<function>`.

| Function | Access / purpose |
|---|---|
| `api_me()` | Return the caller's profile. |
| `api_my_incidents()` | Return the caller's requester- or assigned-unit incidents, including location accuracy, capture time, location source, and optional landmark notes. |
| `api_all_incidents()` | Dispatcher-only incident queue, including location metadata. |
| `api_units()` | Dispatcher-only responder/driver list. |
| `api_set_duty_status(p_duty_status)` | Field unit switches between `available` and `off_duty`; blocked with an active incident. |
| `api_create_incident_v3(p_type,p_description,p_latitude,p_longitude,p_accuracy_m,p_location_notes,p_location_recorded_at,p_location_source)` | Requester-only SOS submission; source is `gps`, `manual`, or `none`, and a manual pin cannot claim GPS accuracy. |
| `api_create_incident_v2(...)` | Preserved for older clients; infers `gps` versus `none` from the coordinate pair. |
| `api_create_incident(p_type,p_description,p_latitude,p_longitude,p_accuracy_m)` | Preserved for older clients. |
| `api_assign_incident(p_id,p_user_id)` | Dispatcher assigns a pending request to an available field unit. |
| `api_update_incident_status(p_id,p_status)` | Assigned unit advances a valid incident status. |
| `api_record_location_v2(p_incident_id,p_latitude,p_longitude,p_accuracy_m,p_recorded_at)` | Reserved incident-bound field-unit location write endpoint; accepts only the assigned unit while the incident is `en_route`. |

The v2 create function uses `SECURITY INVOKER`, checks the signed-in profile's requester role, and relies on the existing Row Level Security policy. The provenance migration adds `location_source`, preserves older create RPCs, and returns the source in incident history. The coordinate pair must be wholly present or wholly absent. Accuracy and `location_recorded_at` require coordinates; manual pins carry a selection timestamp and no GPS accuracy. The Android client uses an ISO-8601 device capture time and records no continuous requester path.

A database trigger writes status history and updates unit duty status when an incident changes. Database guards reject invalid role operations and status transitions, including direct table updates that bypass the intended RPCs. The one narrowly scoped `SECURITY DEFINER` helper lives in the non-API-exposed `private` schema; the Supabase security advisor returned no findings after the hardening migration.

## App setup and role provisioning

1. Apply the numbered Supabase migrations in order, including `202610200001_incident_one_time_location.sql`, `202610200002_responder_location_tracking.sql`, and `20261021150000_manual_incident_location_source.sql`. The responder-tracking migration defines the contract but does not start tracking by itself. The second migration defines the contract but does not start tracking by itself.
2. Set `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` in `AppConfig.kt`. Use only a publishable key in the app; never use a service-role/secret key.
3. In Supabase Dashboard → Authentication → URL Configuration, add `tacresq://auth/callback` to the allowed Redirect URLs. The Android sign-up request explicitly sends this redirect, so confirmation emails return to TACResQ rather than the old `http://localhost:3000` Site URL. The installed app handles this deep link; after confirming, the user can return to TACResQ and sign in.
4. Register requester accounts in the app. For example, to promote a previously registered account:
   ```sql
   update public.profiles
   set role = 'dispatcher', duty_status = null
   where email = 'dispatcher@example.com';
   ```
4. Keep Supabase Auth email-confirmation settings in mind; a newly registered account may need to confirm email before the first sign-in.

## Current limitations

The app supports requester SOS submission, manual dispatcher assignment, duty-status controls, incident status progression, polling, and a user-initiated one-time requester GPS capture. A requester fix is sent only with the SOS and remains subject to the incident access rules. If GPS is unavailable, the SOS can be submitted without coordinates and with an optional landmark; responders see that no pin was shared. The requester flow does **not** collect continuous or background location. The Android responder source calls the incident-bound, en-route-only location RPC after explicit consent and a successful transition to en route. This foreground tracking has not yet been device-validated; no background tracking, offline replay, or guaranteed delivery is implemented. This is a school prototype, not an emergency-services production deployment.
