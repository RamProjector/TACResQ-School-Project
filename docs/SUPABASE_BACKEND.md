# TACResQ Supabase backend

The Android app uses Supabase Auth for accounts and Postgres RPC functions for app operations. The connected project schema is defined by the ordered migrations in `supabase/migrations/`.

## Data model

- `profiles`: one row per Supabase Auth user, TACResQ role, contact data, and field-unit duty status.
- `incidents`: requester, incident type/description, optional paired GPS coordinates, measured accuracy, one-time capture timestamp, optional landmark notes, status, and a legacy `assigned_to` lead unit.
- `incident_assignments`: all responders/drivers assigned to each incident, with assignment actor/time audit metadata. Team assignment is atomic and limited to five field units total; dispatch can add available units while an incident is `assigned` or `en_route`, but not after arrival. `assigned_to` remains the lead/legacy field for compatibility.
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
| `api_assign_incident_team(p_id,p_user_ids)` | Dispatcher atomically assigns or expands a team up to five available field units; server locks and revalidates every selected unit. The exposed RPC is SECURITY INVOKER and delegates to a role-checked implementation in the non-exposed private schema. |
| `api_assign_incident(p_id,p_user_id)` | Backward-compatible single-unit wrapper over team assignment. |
| `api_update_incident_status(p_id,p_status)` | Assigned unit advances a valid incident status. |
| `api_record_location_v2(p_incident_id,p_latitude,p_longitude,p_accuracy_m,p_recorded_at)` | Reserved incident-bound field-unit location write endpoint; accepts only the assigned unit while the incident is `en_route`. |

The v2 create function uses `SECURITY INVOKER`, checks the signed-in profile's requester role, and relies on the existing Row Level Security policy. The provenance migration adds `location_source`, preserves older create RPCs, and returns the source in incident history. The coordinate pair must be wholly present or wholly absent. Accuracy and `location_recorded_at` require coordinates; manual pins carry a selection timestamp and no GPS accuracy. The Android client uses an ISO-8601 device capture time and records no continuous requester path.

A database trigger writes status history and updates unit duty status when an incident changes. Database guards reject invalid role operations and status transitions, including direct table updates that bypass the intended RPCs. Privileged implementation helpers are intended to live in the non-API-exposed `private` schema, with public RPC wrappers using `SECURITY INVOKER` where possible. Do not assume the live project still matches this intended migration state; the latest read-only audit below found function-security drift and outstanding advisor warnings.

## App setup and role provisioning

1. Apply all numbered Supabase migrations in order, including `20261015080806_tacresq_core_schema.sql`, `20261015081107_harden_rpc_exposure.sql`, `20261015081241_rls_and_index_tuning.sql`, `20261015092225_location_and_push.sql`, `20261019214218_incident_one_time_location.sql`, `20261020062532_responder_location_tracking.sql`, the 2026-10-21 hardening migrations, and `20261021155540_manual_incident_location_source.sql`. The responder-tracking migration defines the API/database contract; the Android app starts sharing only after the field unit explicitly chooses to share, and only while the app is in the foreground.
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

The app supports requester SOS submission, atomic multi-responder dispatcher assignment, duty-status controls, shared incident status progression for assigned team members, polling, and a user-initiated one-time requester GPS capture. A requester fix is sent only with the SOS and remains subject to the incident access rules. If GPS is unavailable, the SOS can be submitted without coordinates and with an optional landmark; responders see that no pin was shared. The requester flow does **not** collect continuous or background location. The Android responder source calls the incident-bound, en-route-only location RPC after explicit consent and a successful transition to en route. This foreground tracking has not yet been device-validated; no background tracking, offline replay, or guaranteed delivery is implemented. This is a school prototype, not an emergency-services production deployment.


## Live security posture audit

Read-only audit date: 2026-10-25. These observations describe the connected project at audit time; re-run the advisors and compare deployed definitions before making security decisions.

- `public.profiles.role` currently permits requester, responder, driver, and dispatcher. A separate admin role is not implemented.
- `public.fcm_tokens` and token registration/unregistration RPCs exist, but token storage is not a notification inbox or proof that push delivery is implemented. There is no public table named for notifications, messages, chat, or conversations in the inspected schema.
- Supabase Security Advisor reported RLS-enabled tables with no direct policies: `incident_request_edit_logs` and `incident_submission_keys`. This may be intentional because these tables are accessed through constrained RPCs; verify the RPC contract before changing the policy model.
- Advisor reported authenticated-callable `SECURITY DEFINER` functions: `api_cancel_my_incident`, `api_create_incident_v4`, `api_record_location_v2`, `api_units`, and `api_update_my_incident`. Each must be reviewed individually for ownership, grants, search path, input validation, and caller-role checks; do not blindly revoke access or change security mode.
- The latest recorded migration, `20261023103737_per_responder_assignment_status.sql`, intentionally redefines `api_units()` and `api_record_location_v2` as `SECURITY DEFINER` functions to support per-responder status and assignment validation. The live catalog matches those latest definitions; this is not unexplained migration drift. However, `api_record_location_v2` has `public, pg_temp` in its search path, and both RPCs are authenticated-callable definer functions. Their inspected bodies contain explicit role/assignment checks and anonymous EXECUTE is false, so the advisor warning is a review item, not proof of an exploit. Consider moving the location write implementation into `private` while preserving the newer per-responder checks, minimizing search paths, and adding authorization regression tests before any migration.
- Auth Advisor reports leaked-password protection disabled. Enablement should be reviewed in project Auth settings.
- Performance Advisor reports two unindexed foreign keys and several RLS policies that can optimize auth-function evaluation. Treat these as separate performance hardening tasks.

No production schema or Auth settings were changed during this audit. Follow the repository's PR workflow; do not make direct production changes to bypass migration review. See [Supabase Securing your API](https://supabase.com/docs/guides/api/securing-your-api) and [Supabase Database Advisor: SECURITY DEFINER functions](https://supabase.com/docs/guides/observability/advisors?queryGroups=lint&lint=0029_authenticated_security_definer_function_executable).
