BEGIN;

-- Dispatch progress is recorded once per incident by the dispatcher. Field units
-- no longer need to duplicate operational updates in the app.
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
BEGIN
  SELECT role, id INTO actor_role, actor_id
  FROM public.profiles WHERE auth_user_id = auth.uid();

  -- Allow a dispatcher to advance only the incident status, using the same
  -- ordered milestones exposed by the dispatch workflow.
  IF actor_role = 'dispatcher'
     AND NEW.status IS DISTINCT FROM OLD.status
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
     AND NEW.assigned_to IS NOT DISTINCT FROM OLD.assigned_to
     AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
     AND NEW.requester_edit_count IS NOT DISTINCT FROM OLD.requester_edit_count
     AND NEW.requester_last_edited_at IS NOT DISTINCT FROM OLD.requester_last_edited_at
     AND NEW.is_claimable IS NOT DISTINCT FROM OLD.is_claimable
     AND (
          (OLD.status = 'assigned' AND NEW.status = 'en_route')
       OR (OLD.status = 'en_route' AND NEW.status = 'on_scene')
       OR (OLD.status = 'on_scene' AND NEW.status IN ('transporting','resolved'))
       OR (OLD.status = 'transporting' AND NEW.status = 'resolved')
     ) THEN
    RETURN NEW;
  END IF;

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
    OR NEW.is_claimable IS DISTINCT FROM OLD.is_claimable
  ) THEN
    RAISE EXCEPTION 'Assigned field units cannot modify the original emergency report'
      USING ERRCODE = '42501';
  END IF;

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
     AND NEW.is_claimable IS NOT DISTINCT FROM OLD.is_claimable
     AND NOT EXISTS (SELECT 1 FROM public.incident_assignments a WHERE a.incident_id = OLD.id) THEN
    RETURN NEW;
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status
     AND NEW.status = 'assigned'
     AND OLD.status = 'pending'
     AND actor_role IN ('responder', 'driver')
     AND OLD.is_claimable IS TRUE
     AND NEW.assigned_to IS NOT DISTINCT FROM OLD.assigned_to
     AND NEW.is_claimable IS NOT DISTINCT FROM OLD.is_claimable
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
     AND NEW.requester_edit_count IS NOT DISTINCT FROM OLD.requester_edit_count
     AND NEW.requester_last_edited_at IS NOT DISTINCT FROM OLD.requester_last_edited_at
     AND EXISTS (SELECT 1 FROM public.incident_assignments a WHERE a.incident_id = OLD.id AND a.user_id = actor_id)
     AND EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = actor_id AND p.duty_status = 'available') THEN
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
      -- Responder/driver progress writes are retired. The dispatcher-only
      -- milestone branch above and requester cancellation are the only
      -- permitted incident-status changes beyond a responder claim.
      RAISE EXCEPTION 'Only dispatchers can advance incident progress'
        USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN NEW;
END;
$function$;

-- Keep the legacy RPC name for the Android/API contract, but make it
-- dispatcher-only. All assigned members receive the same incident milestone
-- so existing GPS authorization and older clients remain consistent.
CREATE OR REPLACE FUNCTION private.tacresq_dispatch_update_incident_status(p_id bigint, p_status text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, auth
AS $function$
DECLARE
  actor_role text;
  incident_row public.incidents;
  next_duty_status text;
BEGIN
  SELECT p.role INTO actor_role
  FROM public.profiles p
  WHERE p.auth_user_id = auth.uid();

  IF actor_role IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can update incident progress'
      USING ERRCODE = '42501';
  END IF;

  IF p_status NOT IN ('en_route','on_scene','transporting','resolved') THEN
    RAISE EXCEPTION 'Unsupported incident progress update'
      USING ERRCODE = '22023';
  END IF;

  SELECT * INTO incident_row
  FROM public.incidents
  WHERE id = p_id
  FOR UPDATE;

  IF incident_row.id IS NULL THEN
    RAISE EXCEPTION 'Incident not found' USING ERRCODE = 'P0002';
  END IF;

  IF NOT (
       (incident_row.status = 'assigned' AND p_status = 'en_route')
    OR (incident_row.status = 'en_route' AND p_status = 'on_scene')
    OR (incident_row.status = 'on_scene' AND p_status IN ('transporting','resolved'))
    OR (incident_row.status = 'transporting' AND p_status = 'resolved')
  ) THEN
    RAISE EXCEPTION 'Invalid incident progress transition'
      USING ERRCODE = '22023';
  END IF;

  UPDATE public.incidents
  SET status = p_status
  WHERE id = p_id
  RETURNING * INTO incident_row;

  UPDATE public.incident_assignments
  SET responder_status = p_status, status_updated_at = now()
  WHERE incident_id = p_id;

  next_duty_status := CASE
    WHEN p_status = 'en_route' THEN 'en_route'
    WHEN p_status = 'on_scene' THEN 'on_scene'
    WHEN p_status = 'transporting' THEN 'transporting'
    ELSE 'available'
  END;

  UPDATE public.profiles p
  SET duty_status = next_duty_status
  WHERE p.id IN (SELECT a.user_id FROM public.incident_assignments a WHERE a.incident_id = p_id)
    AND (
      p_status <> 'resolved'
      OR NOT EXISTS (
        SELECT 1
        FROM public.incident_assignments other_a
        JOIN public.incidents other_i ON other_i.id = other_a.incident_id
        WHERE other_a.user_id = p.id
          AND other_i.id <> p_id
          AND other_i.status IN ('assigned','en_route','on_scene','transporting')
      )
    );

  RETURN jsonb_build_object(
    'id', incident_row.id,
    'type', incident_row.type,
    'description', incident_row.description,
    'latitude', incident_row.latitude,
    'longitude', incident_row.longitude,
    'accuracy_m', incident_row.accuracy_m,
    'location_notes', incident_row.location_notes,
    'location_recorded_at', incident_row.location_recorded_at,
    'location_source', incident_row.location_source,
    'status', incident_row.status,
    'requester_name', (SELECT p.name FROM public.profiles p WHERE p.id = incident_row.requester_id),
    'requester_phone', (SELECT p.phone FROM public.profiles p WHERE p.id = incident_row.requester_id),
    'assigned_to', incident_row.assigned_to,
    'assigned_user_ids', (SELECT coalesce(jsonb_agg(a.user_id ORDER BY a.assigned_at, a.user_id), '[]'::jsonb)
                          FROM public.incident_assignments a WHERE a.incident_id = p_id),
    'assigned_user_statuses', (SELECT coalesce(jsonb_agg(jsonb_build_object(
      'user_id', a.user_id, 'status', a.responder_status, 'status_updated_at', a.status_updated_at
    ) ORDER BY a.assigned_at, a.user_id), '[]'::jsonb)
      FROM public.incident_assignments a WHERE a.incident_id = p_id),
    'created_at', incident_row.created_at,
    'requester_edit_count', incident_row.requester_edit_count,
    'requester_last_edited_at', incident_row.requester_last_edited_at
  );
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_dispatch_update_incident_status(bigint,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_dispatch_update_incident_status(bigint,text) TO authenticated;

-- Keep the REST-facing wrapper invoker-safe. The private implementation checks
-- the authenticated caller's dispatcher role before performing privileged writes.
CREATE OR REPLACE FUNCTION public.api_update_incident_status(p_id bigint, p_status text)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT private.tacresq_dispatch_update_incident_status(p_id, p_status);
$function$;
REVOKE ALL ON FUNCTION public.api_update_incident_status(bigint,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_update_incident_status(bigint,text) TO authenticated;

-- New clients must not call the legacy per-responder status mutation.
REVOKE ALL ON FUNCTION public.api_update_my_assignment_status(bigint,text) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION private.tacresq_update_my_assignment_status(bigint,text) FROM PUBLIC, anon, authenticated;

COMMIT;