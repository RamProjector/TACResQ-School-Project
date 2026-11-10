BEGIN;

-- The requester-owned cancellation RPC already verifies ownership and pending
-- status. The incident guard must permit that one transition, or it falls through
-- to the field-unit branch and returns the misleading "not assigned" error.
CREATE OR REPLACE FUNCTION public.tacresq_guard_incident_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_role text;
  actor_id bigint;
  unit_status text;
  actor_is_assigned boolean := false;
BEGIN
  SELECT role, id INTO actor_role, actor_id
  FROM public.profiles WHERE auth_user_id = auth.uid();

  IF actor_role IN ('responder', 'driver') AND (
       NEW.id IS DISTINCT FROM OLD.id
    OR NEW.requester_id IS DISTINCT FROM OLD.requester_id
    OR NEW.type IS DISTINCT FROM OLD.type
    OR NEW.description IS DISTINCT FROM OLD.description
    OR NEW.latitude IS DISTINCT FROM OLD.latitude
    OR NEW.longitude IS DISTINCT FROM OLD.longitude
    OR NEW.accuracy_m IS DISTINCT FROM OLD.accuracy_m
    OR NEW.location_notes IS DISTINCT FROM OLD.location_notes
    OR NEW.location_recorded_at IS DISTINCT FROM OLD.location_recorded_at
    OR NEW.location_source IS DISTINCT FROM OLD.location_source
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
  ) THEN
    RAISE EXCEPTION 'Assigned field units cannot modify the original emergency report'
      USING ERRCODE = '42501';
  END IF;

  -- Permit only the cancellation RPC's status-only update: authenticated owner,
  -- pending incident, no legacy assignment, and no assignment-team membership.
  -- All report fields and assignment metadata must remain byte-for-byte unchanged.
  IF NEW.status IS DISTINCT FROM OLD.status
     AND NEW.status = 'cancelled'
     AND OLD.status = 'pending'
     AND actor_role = 'requester'
     AND actor_id = OLD.requester_id
     AND OLD.assigned_to IS NULL
     AND NEW.assigned_to IS NOT DISTINCT FROM OLD.assigned_to
     AND NEW.id IS NOT DISTINCT FROM OLD.id
     AND NEW.requester_id IS NOT DISTINCT FROM OLD.requester_id
     AND NEW.type IS NOT DISTINCT FROM OLD.type
     AND NEW.description IS NOT DISTINCT FROM OLD.description
     AND NEW.latitude IS NOT DISTINCT FROM OLD.latitude
     AND NEW.longitude IS NOT DISTINCT FROM OLD.longitude
     AND NEW.accuracy_m IS NOT DISTINCT FROM OLD.accuracy_m
     AND NEW.location_notes IS NOT DISTINCT FROM OLD.location_notes
     AND NEW.location_recorded_at IS NOT DISTINCT FROM OLD.location_recorded_at
     AND NEW.location_source IS NOT DISTINCT FROM OLD.location_source
     AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
     AND NOT EXISTS (
       SELECT 1 FROM public.incident_assignments a WHERE a.incident_id = OLD.id
     ) THEN
    RETURN NEW;
  END IF;

  IF NEW.assigned_to IS DISTINCT FROM OLD.assigned_to THEN
    SELECT duty_status INTO unit_status FROM public.profiles WHERE id = NEW.assigned_to;
    IF actor_role <> 'dispatcher' OR OLD.status <> 'pending'
       OR NEW.status <> 'assigned' OR unit_status <> 'available' THEN
      RAISE EXCEPTION 'Only a dispatcher may assign a pending incident to an available unit'
        USING ERRCODE = '42501';
    END IF;
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status = 'assigned' THEN
      IF actor_role <> 'dispatcher' OR OLD.status <> 'pending' OR NEW.assigned_to IS NULL THEN
        RAISE EXCEPTION 'Invalid assignment' USING ERRCODE = '42501';
      END IF;
    ELSE
      SELECT EXISTS (
        SELECT 1 FROM public.incident_assignments a
        WHERE a.incident_id = OLD.id AND a.user_id = actor_id
      ) INTO actor_is_assigned;
      IF NOT actor_is_assigned AND actor_id IS DISTINCT FROM OLD.assigned_to THEN
        RAISE EXCEPTION 'Incident is not assigned to this field unit' USING ERRCODE = '42501';
      END IF;
      IF actor_role NOT IN ('responder','driver') OR NOT (
           (OLD.status = 'assigned' AND NEW.status = 'en_route')
        OR (OLD.status = 'en_route' AND NEW.status = 'on_scene')
        OR (OLD.status = 'on_scene' AND NEW.status IN ('transporting','resolved'))
        OR (OLD.status = 'transporting' AND NEW.status = 'resolved')
      ) THEN
        RAISE EXCEPTION 'Invalid incident status transition' USING ERRCODE = '42501';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION public.tacresq_guard_incident_update() FROM PUBLIC, anon, authenticated;

COMMIT;
