BEGIN;

-- Permit only the primary-member handoff caused by the release RPC:
-- the former primary must already be absent from membership and the replacement
-- must already be a member. All incident content and shared progress stay fixed.
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

  IF actor_role = 'dispatcher'
     AND NEW.assigned_to IS DISTINCT FROM OLD.assigned_to
     AND OLD.assigned_to IS NOT NULL
     AND NEW.assigned_to IS NOT NULL
     AND OLD.status IN ('assigned', 'en_route')
     AND NEW.status IS NOT DISTINCT FROM OLD.status
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
     AND NEW.is_claimable IS NOT DISTINCT FROM OLD.is_claimable
     AND NOT EXISTS (
       SELECT 1 FROM public.incident_assignments a
       WHERE a.incident_id = OLD.id AND a.user_id = OLD.assigned_to
     )
     AND EXISTS (
       SELECT 1 FROM public.incident_assignments a
       WHERE a.incident_id = OLD.id AND a.user_id = NEW.assigned_to
     ) THEN
    RETURN NEW;
  END IF;

  -- Existing dispatcher-owned incident milestone contract.
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
      RAISE EXCEPTION 'Only dispatchers can advance incident progress'
        USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN NEW;
END;
$function$;

-- Release one member from an active pre-arrival team without leaving an
-- incident orphaned. The incident row lock serializes this with team assignment
-- and dispatcher progress updates.
CREATE OR REPLACE FUNCTION private.tacresq_release_incident_assignment(
  p_id bigint,
  p_user_id bigint
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor public.profiles;
  incident_row public.incidents;
  released_assignment public.incident_assignments;
  remaining_count integer;
  has_other_legacy_reservation boolean := false;
  next_primary_user_id bigint;
  assigned_ids jsonb;
  assigned_statuses jsonb;
BEGIN
  SELECT * INTO actor
  FROM public.profiles
  WHERE auth_user_id = auth.uid();

  IF actor.id IS NULL OR actor.role <> 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can release incident assignments'
      USING ERRCODE = '42501';
  END IF;

  IF p_id IS NULL OR p_user_id IS NULL THEN
    RAISE EXCEPTION 'Incident and field-unit identifiers are required'
      USING ERRCODE = '22023';
  END IF;

  SELECT * INTO incident_row
  FROM public.incidents
  WHERE id = p_id
  FOR UPDATE;

  IF incident_row.id IS NULL THEN
    RAISE EXCEPTION 'Incident not found'
      USING ERRCODE = 'P0002';
  END IF;

  IF incident_row.status NOT IN ('assigned', 'en_route') THEN
    RAISE EXCEPTION 'Team members can only be released before the incident reaches the scene'
      USING ERRCODE = '22023';
  END IF;

  SELECT * INTO released_assignment
  FROM public.incident_assignments
  WHERE incident_id = p_id AND user_id = p_user_id
  FOR UPDATE;

  IF released_assignment.incident_id IS NULL THEN
    RAISE EXCEPTION 'The selected field unit is not assigned to this incident'
      USING ERRCODE = 'P0002';
  END IF;

  SELECT count(*) INTO remaining_count
  FROM public.incident_assignments
  WHERE incident_id = p_id;

  IF remaining_count <= 1 THEN
    RAISE EXCEPTION 'The last assigned field unit cannot be released. Assign a replacement or close the incident first.'
      USING ERRCODE = '22023';
  END IF;

  -- Capture legacy single-member reservations before changing this incident's
  -- membership. This preserves compatibility with older active incidents that
  -- reserve a profile through incidents.assigned_to without a team row.
  SELECT EXISTS (
    SELECT 1
    FROM public.incidents i
    WHERE i.assigned_to = p_user_id
      AND i.id <> p_id
      AND i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) INTO has_other_legacy_reservation;

  -- The DELETE trigger appends the release event in this same transaction.
  DELETE FROM public.incident_assignments
  WHERE incident_id = p_id AND user_id = p_user_id;

  SELECT a.user_id INTO next_primary_user_id
  FROM public.incident_assignments a
  WHERE a.incident_id = p_id
  ORDER BY a.assigned_at, a.user_id
  LIMIT 1;

  IF incident_row.assigned_to = p_user_id THEN
    UPDATE public.incidents
    SET assigned_to = next_primary_user_id
    WHERE id = p_id
    RETURNING * INTO incident_row;
  END IF;

  -- A released member returns to the available pool only when no other active
  -- incident still reserves them. Preserve any unrelated duty state.
  UPDATE public.profiles p
  SET duty_status = 'available'
  WHERE p.id = p_user_id
    AND p.duty_status IN ('assigned', 'en_route', 'on_scene', 'transporting')
    AND NOT EXISTS (
      SELECT 1
      FROM public.incident_assignments a
      JOIN public.incidents i ON i.id = a.incident_id
      WHERE a.user_id = p.id
        AND i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
    )
    AND NOT has_other_legacy_reservation;

  SELECT
    coalesce(jsonb_agg(a.user_id ORDER BY a.assigned_at, a.user_id), '[]'::jsonb),
    coalesce(jsonb_agg(
      jsonb_build_object(
        'user_id', a.user_id,
        'status', a.responder_status,
        'status_updated_at', a.status_updated_at
      ) ORDER BY a.assigned_at, a.user_id
    ), '[]'::jsonb)
  INTO assigned_ids, assigned_statuses
  FROM public.incident_assignments a
  WHERE a.incident_id = p_id;

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
    'assigned_user_ids', assigned_ids,
    'assigned_user_statuses', assigned_statuses,
    'created_at', incident_row.created_at,
    'requester_edit_count', incident_row.requester_edit_count,
    'requester_last_edited_at', incident_row.requester_last_edited_at
  );
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_release_incident_assignment(bigint,bigint)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_release_incident_assignment(bigint,bigint)
  TO authenticated;

-- Keep the REST-facing wrapper invoker-safe. The private implementation
-- validates dispatcher identity before performing privileged membership writes.
CREATE OR REPLACE FUNCTION public.api_release_incident_assignment(
  p_id bigint,
  p_user_id bigint
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT private.tacresq_release_incident_assignment(p_id, p_user_id);
$function$;

REVOKE ALL ON FUNCTION public.api_release_incident_assignment(bigint,bigint)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_release_incident_assignment(bigint,bigint)
  TO authenticated;

COMMIT;
