BEGIN;

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
    AND NOT EXISTS (
      SELECT 1
      FROM public.incidents i
      WHERE i.assigned_to = p.id
        AND i.id <> p_id
        AND i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
    );

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
