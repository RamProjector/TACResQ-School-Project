-- Prevent assigned mobile units from rewriting the original emergency report.
-- This migration is intentionally review-only until CI and a disposable-database test pass.
CREATE OR REPLACE FUNCTION public.tacresq_guard_incident_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'auth', 'pg_temp'
AS $function$
DECLARE
  actor_role text;
  actor_id bigint;
  unit_status text;
BEGIN
  SELECT role, id
    INTO actor_role, actor_id
    FROM public.profiles
   WHERE auth_user_id = auth.uid();

  -- Responders and drivers may progress an assigned incident, but must not alter
  -- the requester's report, original coordinates, or original report timestamps.
  IF actor_role IN ('responder', 'driver') AND (
       NEW.requester_id IS DISTINCT FROM OLD.requester_id
    OR NEW.type IS DISTINCT FROM OLD.type
    OR NEW.description IS DISTINCT FROM OLD.description
    OR NEW.latitude IS DISTINCT FROM OLD.latitude
    OR NEW.longitude IS DISTINCT FROM OLD.longitude
    OR NEW.accuracy_m IS DISTINCT FROM OLD.accuracy_m
    OR NEW.location_notes IS DISTINCT FROM OLD.location_notes
    OR NEW.location_recorded_at IS DISTINCT FROM OLD.location_recorded_at
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
  ) THEN
    RAISE EXCEPTION 'Assigned field units cannot modify the original emergency report'
      USING ERRCODE = '42501';
  END IF;

  IF NEW.assigned_to IS DISTINCT FROM OLD.assigned_to THEN
    SELECT duty_status INTO unit_status
      FROM public.profiles
     WHERE id = NEW.assigned_to;

    IF actor_role <> 'dispatcher'
       OR OLD.status <> 'pending'
       OR NEW.status <> 'assigned'
       OR unit_status <> 'available' THEN
      RAISE EXCEPTION 'Only a dispatcher may assign a pending incident to an available unit'
        USING ERRCODE = '42501';
    END IF;
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status = 'assigned' THEN
      IF actor_role <> 'dispatcher'
         OR OLD.status <> 'pending'
         OR NEW.assigned_to IS NULL THEN
        RAISE EXCEPTION 'Invalid assignment' USING ERRCODE = '42501';
      END IF;
    ELSIF actor_id <> OLD.assigned_to
       OR actor_role NOT IN ('responder', 'driver')
       OR NOT (
            (OLD.status = 'assigned' AND NEW.status = 'en_route')
         OR (OLD.status = 'en_route' AND NEW.status = 'on_scene')
         OR (OLD.status = 'on_scene' AND NEW.status IN ('transporting', 'resolved'))
         OR (OLD.status = 'transporting' AND NEW.status = 'resolved')
       ) THEN
      RAISE EXCEPTION 'Invalid incident status transition' USING ERRCODE = '42501';
    END IF;
  END IF;

  RETURN NEW;
END;
$function$;

COMMENT ON FUNCTION public.tacresq_guard_incident_update() IS
  'Enforces incident assignment/status transitions and prevents assigned responders/drivers from rewriting the original emergency report.';
