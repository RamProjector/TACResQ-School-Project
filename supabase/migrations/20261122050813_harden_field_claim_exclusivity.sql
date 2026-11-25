BEGIN;

-- Serialize claims per incident and per field unit. The incident lock is acquired
-- before the profile lock, matching api_assign_incident_team's lock order.
-- This prevents one unit from claiming two different active incidents concurrently.
CREATE OR REPLACE FUNCTION public.api_claim_incident(p_incident_id bigint)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_auth_uid uuid;
  v_actor_id bigint;
  v_actor_role text;
  v_duty_status text;
  v_status text;
  v_claimable boolean;
  v_assigned_to bigint;
  v_already_assigned boolean := false;
  v_assignment_count integer;
BEGIN
  v_auth_uid := auth.uid();
  IF v_auth_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
  END IF;

  -- Resolve identity first without taking a lock so an arbitrary signed-in user
  -- cannot lock incident rows. Role is checked again after the profile lock.
  SELECT id, role INTO v_actor_id, v_actor_role
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid;

  IF v_actor_id IS NULL OR v_actor_role IS NULL OR v_actor_role NOT IN ('responder', 'driver') THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'UNAUTHORIZED',
      'message', 'Only responders or drivers can claim incidents.'
    );
  END IF;

  -- Lock order is incident then profile, the same order used by dispatcher
  -- assignment. FOR UPDATE serializes competing changes to this incident.
  SELECT status, is_claimable, assigned_to
  INTO v_status, v_claimable, v_assigned_to
  FROM public.incidents
  WHERE id = p_incident_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'NOT_FOUND',
      'message', 'Incident not found.'
    );
  END IF;

  -- A field claim is offered only for pending/assigned incidents. Never accept
  -- a stale client request once a team has reached the scene or begun transport.
  IF NOT COALESCE(v_claimable, false) OR v_status NOT IN ('pending', 'assigned') THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'NOT_CLAIMABLE',
      'message', 'Incident is not available for field claiming.'
    );
  END IF;

  -- Lock and re-read the profile after the incident lock. Concurrent claims for
  -- different incidents by this unit now serialize on the same profile row.
  SELECT role, duty_status INTO v_actor_role, v_duty_status
  FROM public.profiles
  WHERE id = v_actor_id AND auth_user_id = v_auth_uid
  FOR UPDATE;

  IF v_actor_role IS NULL OR v_actor_role NOT IN ('responder', 'driver') THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'UNAUTHORIZED',
      'message', 'Only responders or drivers can claim incidents.'
    );
  END IF;

  IF v_duty_status IS DISTINCT FROM 'available' THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'OFF_DUTY',
      'message', 'You must be on duty to claim an incident.'
    );
  END IF;

  SELECT EXISTS (
    SELECT 1
    FROM public.incident_assignments a
    WHERE a.incident_id = p_incident_id AND a.user_id = v_actor_id
  ) OR v_assigned_to = v_actor_id
  INTO v_already_assigned;

  IF v_already_assigned THEN
    RETURN jsonb_build_object(
      'status', 'already_assigned',
      'incident_id', p_incident_id,
      'user_id', v_actor_id
    );
  END IF;

  -- A unit may belong to only one active incident at a time, whether it was
  -- assigned by a dispatcher or claimed through the field-claim workflow.
  IF EXISTS (
    SELECT 1
    FROM public.incident_assignments a
    JOIN public.incidents active_i ON active_i.id = a.incident_id
    WHERE a.user_id = v_actor_id
      AND a.incident_id <> p_incident_id
      AND active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) OR EXISTS (
    SELECT 1
    FROM public.incidents active_i
    WHERE active_i.assigned_to = v_actor_id
      AND active_i.id <> p_incident_id
      AND active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'UNIT_BUSY',
      'message', 'You are already assigned to another active incident.'
    );
  END IF;

  SELECT count(*) INTO v_assignment_count
  FROM public.incident_assignments
  WHERE incident_id = p_incident_id;

  -- Count the legacy lead assignment too if a historical row has not yet been
  -- backfilled into incident_assignments.
  IF v_assigned_to IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.incident_assignments a
    WHERE a.incident_id = p_incident_id AND a.user_id = v_assigned_to
  ) THEN
    v_assignment_count := v_assignment_count + 1;
  END IF;

  IF v_assignment_count >= 5 THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'GROUP_FULL',
      'message', 'Response team is already full (maximum 5 members).'
    );
  END IF;

  INSERT INTO public.incident_assignments (incident_id, user_id, assigned_by, assigned_at)
  VALUES (p_incident_id, v_actor_id, v_actor_id, clock_timestamp());

  -- Keep assigned_to dispatcher-managed. The team membership row is the
  -- authoritative assignment for field claims; only the first claim advances
  -- the shared incident lifecycle from pending to assigned.
  IF v_status = 'pending' THEN
    UPDATE public.incidents
    SET status = 'assigned',
        updated_at = clock_timestamp()
    WHERE id = p_incident_id;
  END IF;

  RETURN jsonb_build_object(
    'status', 'claimed',
    'incident_id', p_incident_id,
    'user_id', v_actor_id
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_claim_incident(bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_claim_incident(bigint) TO authenticated;

-- Do not show field-claim opportunities to a unit that already has an active
-- assignment, or incidents that this unit has already joined.
CREATE OR REPLACE FUNCTION public.api_claimable_incidents()
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_auth_uid uuid;
  v_actor_id bigint;
  v_actor_role text;
  v_duty_status text;
  v_result jsonb;
BEGIN
  v_auth_uid := auth.uid();
  IF v_auth_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
  END IF;

  SELECT id, role, duty_status
  INTO v_actor_id, v_actor_role, v_duty_status
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid;

  IF v_actor_id IS NULL OR v_actor_role IS NULL OR v_actor_role NOT IN ('responder', 'driver')
     OR v_duty_status IS DISTINCT FROM 'available' THEN
    RETURN '[]'::jsonb;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.incident_assignments a
    JOIN public.incidents active_i ON active_i.id = a.incident_id
    WHERE a.user_id = v_actor_id
      AND active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) OR EXISTS (
    SELECT 1
    FROM public.incidents active_i
    WHERE active_i.assigned_to = v_actor_id
      AND active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) THEN
    RETURN '[]'::jsonb;
  END IF;

  SELECT COALESCE(jsonb_agg(
    jsonb_build_object(
      'id', i.id,
      'type', i.type,
      'description', i.description,
      'latitude', i.latitude,
      'longitude', i.longitude,
      'status', i.status,
      'is_claimable', i.is_claimable,
      'created_at', i.created_at,
      'updated_at', i.updated_at,
      'assigned_user_ids', COALESCE((
        SELECT jsonb_agg(a.user_id ORDER BY a.assigned_at, a.user_id)
        FROM public.incident_assignments a WHERE a.incident_id = i.id
      ), '[]'::jsonb)
    ) ORDER BY i.created_at ASC
  ), '[]'::jsonb) INTO v_result
  FROM public.incidents i
  WHERE i.is_claimable = true
    AND i.status IN ('pending', 'assigned')
    AND i.assigned_to IS DISTINCT FROM v_actor_id
    AND NOT EXISTS (
      SELECT 1
      FROM public.incident_assignments own_assignment
      WHERE own_assignment.incident_id = i.id
        AND own_assignment.user_id = v_actor_id
    )
    AND (
      SELECT count(*) + CASE
        WHEN i.assigned_to IS NOT NULL AND NOT EXISTS (
          SELECT 1 FROM public.incident_assignments legacy_lead
          WHERE legacy_lead.incident_id = i.id AND legacy_lead.user_id = i.assigned_to
        ) THEN 1
        ELSE 0
      END
      FROM public.incident_assignments a
      WHERE a.incident_id = i.id
    ) < 5;

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.api_claimable_incidents() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_claimable_incidents() TO authenticated;



-- Keep dispatcher expansion compatible with a field-claimed incident. A self-claim
-- advances status to assigned but intentionally leaves assigned_to dispatcher-managed.
CREATE OR REPLACE FUNCTION private.tacresq_assign_incident_team(p_id bigint, p_user_ids bigint[])
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $function$
DECLARE
  actor public.profiles;
  actor_role text;
  incident_row public.incidents;
  primary_user_id bigint;
  selected_count integer;
  assigned_ids jsonb;
  current_team_count integer := 0;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;
  SELECT role INTO actor_role
  FROM public.profiles
  WHERE auth_user_id = auth.uid();
  IF actor_role IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can assign incidents' USING ERRCODE = '42501';
  END IF;
  IF p_user_ids IS NULL OR cardinality(p_user_ids) < 1 OR cardinality(p_user_ids) > 5
     OR array_position(p_user_ids, NULL) IS NOT NULL
     OR cardinality(ARRAY(SELECT DISTINCT unnest(p_user_ids))) <> cardinality(p_user_ids) THEN
    RAISE EXCEPTION 'Select between 1 and 5 different field units' USING ERRCODE = '22023';
  END IF;

  -- Use the same incident -> dispatcher-profile lock order as the
  -- claimability toggle, then re-check the dispatcher's current role.
  SELECT * INTO incident_row FROM public.incidents WHERE id = p_id FOR UPDATE;
  IF incident_row.id IS NULL THEN
    RAISE EXCEPTION 'Incident not found' USING ERRCODE = 'P0002';
  END IF;
  SELECT * INTO actor FROM public.profiles WHERE auth_user_id = auth.uid() FOR UPDATE;
  IF actor.id IS NULL OR actor.role IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can assign incidents' USING ERRCODE = '42501';
  END IF;
  IF incident_row.status NOT IN ('pending','assigned','en_route')
     OR (incident_row.status = 'pending' AND incident_row.assigned_to IS NOT NULL)
     OR (
       incident_row.status IN ('assigned','en_route')
       AND incident_row.assigned_to IS NULL
       AND NOT EXISTS (
         SELECT 1 FROM public.incident_assignments existing_team
         WHERE existing_team.incident_id = incident_row.id
       )
     ) THEN
    RAISE EXCEPTION 'A response team can only be assigned or expanded before the incident reaches the scene' USING ERRCODE = '22023';
  END IF;
  SELECT count(*) + CASE
      WHEN incident_row.assigned_to IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.incident_assignments legacy_lead
        WHERE legacy_lead.incident_id = incident_row.id
          AND legacy_lead.user_id = incident_row.assigned_to
      ) THEN 1
      ELSE 0
    END
    INTO current_team_count
    FROM public.incident_assignments WHERE incident_id = incident_row.id;
  IF current_team_count + cardinality(p_user_ids) > 5 THEN
    RAISE EXCEPTION 'A response team may contain at most 5 field units' USING ERRCODE = '22023';
  END IF;

  -- Stable lock order prevents competing dispatchers from deadlocking on overlapping teams.
  PERFORM id FROM public.profiles WHERE id = ANY(p_user_ids) ORDER BY id FOR UPDATE;
  SELECT count(*) INTO selected_count
  FROM public.profiles p
  WHERE p.id = ANY(p_user_ids)
    AND p.role IN ('responder','driver')
    AND p.duty_status = 'available'
    AND NOT EXISTS (
      SELECT 1 FROM public.incident_assignments a
      JOIN public.incidents active_i ON active_i.id = a.incident_id
      WHERE a.user_id = p.id AND active_i.status IN ('assigned','en_route','on_scene','transporting')
    )
    AND NOT EXISTS (
      SELECT 1 FROM public.incidents active_i
      WHERE active_i.assigned_to = p.id AND active_i.status IN ('assigned','en_route','on_scene','transporting')
    );
  IF selected_count <> cardinality(p_user_ids) THEN
    RAISE EXCEPTION 'One or more selected units are no longer available. Refresh the queue and choose again.' USING ERRCODE = '22023';
  END IF;

  IF incident_row.status = 'pending' THEN
    primary_user_id := p_user_ids[1];
    UPDATE public.incidents SET status = 'assigned', assigned_to = primary_user_id
    WHERE id = incident_row.id RETURNING * INTO incident_row;
  END IF;

  INSERT INTO public.incident_assignments (incident_id, user_id, assigned_by)
  SELECT incident_row.id, chosen.user_id, actor.id
  FROM unnest(p_user_ids) WITH ORDINALITY AS chosen(user_id, position)
  ORDER BY chosen.position
  ON CONFLICT (incident_id, user_id) DO NOTHING;

  SELECT coalesce(jsonb_agg(a.user_id ORDER BY a.assigned_at, a.user_id), '[]'::jsonb)
    INTO assigned_ids FROM public.incident_assignments a WHERE a.incident_id = incident_row.id;

  RETURN jsonb_build_object(
    'id', incident_row.id, 'type', incident_row.type, 'description', incident_row.description,
    'latitude', incident_row.latitude, 'longitude', incident_row.longitude, 'accuracy_m', incident_row.accuracy_m,
    'location_notes', incident_row.location_notes, 'location_recorded_at', incident_row.location_recorded_at,
    'location_source', incident_row.location_source, 'status', incident_row.status,
    'requester_name', (SELECT name FROM public.profiles WHERE id = incident_row.requester_id),
    'requester_phone', (SELECT phone FROM public.profiles WHERE id = incident_row.requester_id),
    'assigned_to', incident_row.assigned_to, 'assigned_user_ids', assigned_ids,
    'created_at', incident_row.created_at,
    'requester_edit_count', incident_row.requester_edit_count,
    'requester_last_edited_at', incident_row.requester_last_edited_at
  );
END;
$function$;

COMMIT;
