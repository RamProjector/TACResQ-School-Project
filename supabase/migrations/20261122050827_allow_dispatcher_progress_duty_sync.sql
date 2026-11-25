BEGIN;

-- A dispatcher RPC updates incident_assignments and the assigned profiles as
-- one transaction. Permit only the duty-status mirror that exactly matches
-- an incident already advanced by that dispatcher; keep ordinary client
-- changes limited to available/off_duty and preserve active-incident locks.
CREATE OR REPLACE FUNCTION public.tacresq_guard_profile_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_role text;
  dispatcher_progress_sync boolean := false;
BEGIN
  IF auth.uid() IS NOT NULL THEN
    -- Identity metadata remains immutable even during nested trusted writes.
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.auth_user_id IS DISTINCT FROM OLD.auth_user_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.name IS DISTINCT FROM OLD.name
       OR NEW.email IS DISTINCT FROM OLD.email
       OR NEW.phone IS DISTINCT FROM OLD.phone THEN
      RAISE EXCEPTION 'Profile identifiers, creation timestamps, and identity fields cannot be changed through the application API'
        USING ERRCODE = '42501';
    END IF;

    IF pg_trigger_depth() = 1 THEN
      SELECT p.role INTO actor_role
      FROM public.profiles p
      WHERE p.auth_user_id = auth.uid();

      IF NEW.role IS DISTINCT FROM OLD.role
         AND actor_role IS DISTINCT FROM 'admin' THEN
        RAISE EXCEPTION 'Only an administrator can change account roles'
          USING ERRCODE = '42501';
      END IF;

      IF NEW.role IS DISTINCT FROM OLD.role
         AND (OLD.role = 'admin' OR NEW.role = 'admin') THEN
        RAISE EXCEPTION 'Administrator promotion and demotion require trusted operator provisioning'
          USING ERRCODE = '42501';
      END IF;

      IF NEW.role IN ('responder', 'driver') AND NEW.duty_status IS NULL THEN
        RAISE EXCEPTION 'Field-unit accounts require an explicit duty status'
          USING ERRCODE = '22023';
      ELSIF NEW.role IN ('requester', 'dispatcher', 'admin') AND NEW.duty_status IS NOT NULL THEN
        RAISE EXCEPTION 'This role cannot have a field-unit duty status'
          USING ERRCODE = '22023';
      END IF;

      dispatcher_progress_sync := coalesce(
        actor_role = 'dispatcher'
        AND NEW.role IN ('responder', 'driver')
        AND NEW.duty_status IN ('en_route', 'on_scene', 'transporting')
        AND (
          EXISTS (
            SELECT 1
            FROM public.incidents i
            WHERE i.assigned_to = OLD.id
              AND i.status = NEW.duty_status
          )
          OR EXISTS (
            SELECT 1
            FROM public.incident_assignments ia
            JOIN public.incidents i ON i.id = ia.incident_id
            WHERE ia.user_id = OLD.id
              AND i.status = NEW.duty_status
          )
        ),
        false
      );

      IF NEW.duty_status IS DISTINCT FROM OLD.duty_status
         AND NEW.role IN ('responder', 'driver')
         AND NEW.duty_status NOT IN ('available', 'off_duty')
         AND NOT dispatcher_progress_sync THEN
        RAISE EXCEPTION 'Invalid user-set duty status'
          USING ERRCODE = '22023';
      END IF;

      IF (
        EXISTS (
          SELECT 1 FROM public.incidents
          WHERE assigned_to = OLD.id
            AND status IN ('assigned', 'en_route', 'on_scene', 'transporting')
        )
        OR EXISTS (
          SELECT 1
          FROM public.incident_assignments ia
          JOIN public.incidents i ON i.id = ia.incident_id
          WHERE ia.user_id = OLD.id
            AND i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
        )
      )
      AND (NEW.role IS DISTINCT FROM OLD.role OR NEW.duty_status IS DISTINCT FROM OLD.duty_status)
      AND NOT dispatcher_progress_sync THEN
        RAISE EXCEPTION 'Finish the active incident before changing a field unit role or duty status'
          USING ERRCODE = '22023';
      END IF;
    END IF;
  END IF;

  NEW.updated_at := now();
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION public.tacresq_guard_profile_update() FROM PUBLIC, anon, authenticated;

COMMIT;
