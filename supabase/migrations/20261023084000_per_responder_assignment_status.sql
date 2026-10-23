BEGIN;

-- Keep each unit's progress independent from the incident-wide aggregate.
ALTER TABLE public.incident_assignments
  ADD COLUMN IF NOT EXISTS responder_status text NOT NULL DEFAULT 'assigned',
  ADD COLUMN IF NOT EXISTS status_updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE public.incident_assignments
  DROP CONSTRAINT IF EXISTS incident_assignments_responder_status_check;
ALTER TABLE public.incident_assignments
  ADD CONSTRAINT incident_assignments_responder_status_check
  CHECK (responder_status IN ('assigned','en_route','on_scene','transporting','resolved'));

-- Backfill the unit state from the incident state already recorded in production.
UPDATE public.incident_assignments a
SET responder_status = CASE i.status
      WHEN 'en_route' THEN 'en_route'
      WHEN 'on_scene' THEN 'on_scene'
      WHEN 'transporting' THEN 'transporting'
      WHEN 'resolved' THEN 'resolved'
      ELSE 'assigned'
    END,
    status_updated_at = now()
FROM public.incidents i
WHERE i.id = a.incident_id;

CREATE OR REPLACE FUNCTION public.tacresq_seed_assignment_status()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
DECLARE incident_status text;
BEGIN
  SELECT i.status INTO incident_status
  FROM public.incidents i
  WHERE i.id = NEW.incident_id;

  -- New units start assigned even when joining an already en-route team.
  -- They must explicitly acknowledge that they are en route themselves.
  NEW.responder_status := 'assigned';
  NEW.status_updated_at := now();
  RETURN NEW;
END;
$function$;
DROP TRIGGER IF EXISTS incident_assignment_seed_status ON public.incident_assignments;
CREATE TRIGGER incident_assignment_seed_status
BEFORE INSERT ON public.incident_assignments
FOR EACH ROW EXECUTE FUNCTION public.tacresq_seed_assignment_status();

CREATE OR REPLACE FUNCTION private.tacresq_update_my_assignment_status(
  p_incident_id bigint,
  p_status text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
  actor_role text;
  assignment_row public.incident_assignments;
  incident_row public.incidents;
  aggregate_status text;
  team_statuses jsonb;
BEGIN
  SELECT p.id, p.role INTO actor_id, actor_role
  FROM public.profiles p
  WHERE p.auth_user_id = auth.uid();

  IF actor_id IS NULL OR actor_role NOT IN ('responder','driver') THEN
    RAISE EXCEPTION 'Only assigned field units can update their response status'
      USING ERRCODE = '42501';
  END IF;

  IF p_status NOT IN ('en_route','on_scene','transporting','resolved') THEN
    RAISE EXCEPTION 'Unsupported responder status'
      USING ERRCODE = '22023';
  END IF;

  SELECT * INTO incident_row
  FROM public.incidents i
  WHERE i.id = p_incident_id
  FOR UPDATE;

  IF incident_row.id IS NULL THEN
    RAISE EXCEPTION 'Incident not found' USING ERRCODE = 'P0002';
  END IF;

  SELECT * INTO assignment_row
  FROM public.incident_assignments a
  WHERE a.incident_id = p_incident_id AND a.user_id = actor_id
  FOR UPDATE;

  IF incident_row.status IN ('resolved','cancelled') THEN
    RAISE EXCEPTION 'A closed incident cannot accept further unit status updates' USING ERRCODE = '22023';
  END IF;

  IF assignment_row.user_id IS NULL THEN
    RAISE EXCEPTION 'This incident is not assigned to your unit'
      USING ERRCODE = '42501';
  END IF;

  IF NOT (
       (assignment_row.responder_status = 'assigned' AND p_status = 'en_route')
    OR (assignment_row.responder_status = 'en_route' AND p_status = 'on_scene')
    OR (assignment_row.responder_status = 'on_scene' AND p_status IN ('transporting','resolved'))
    OR (assignment_row.responder_status = 'transporting' AND p_status = 'resolved')
  ) THEN
    RAISE EXCEPTION 'Invalid response status transition for this unit'
      USING ERRCODE = '22023';
  END IF;

  UPDATE public.incident_assignments
  SET responder_status = p_status, status_updated_at = now()
  WHERE incident_id = p_incident_id AND user_id = actor_id;

  -- The incident summary advances only when team progress supports it.
  -- In particular, keep it en_route until every assigned unit has arrived,
  -- so other en-route units can continue sending permitted foreground GPS.
  SELECT CASE
    WHEN bool_and(a.responder_status = 'resolved') THEN 'resolved'
    WHEN bool_and(a.responder_status IN ('on_scene','transporting','resolved'))
         AND bool_or(a.responder_status = 'transporting') THEN 'transporting'
    WHEN bool_and(a.responder_status IN ('on_scene','transporting','resolved')) THEN 'on_scene'
    WHEN bool_or(a.responder_status IN ('en_route','on_scene','transporting','resolved')) THEN 'en_route'
    ELSE 'assigned'
  END
  INTO aggregate_status
  FROM public.incident_assignments a
  WHERE a.incident_id = p_incident_id;

  IF aggregate_status IS DISTINCT FROM incident_row.status THEN
    -- The incident transition guard requires legal intermediate transitions.
    -- If every unit has arrived and one is already transporting, advance through
    -- on_scene before transporting rather than skipping a state.
    IF incident_row.status = 'en_route' AND aggregate_status = 'transporting' THEN
      UPDATE public.incidents SET status = 'on_scene'
      WHERE id = p_incident_id
      RETURNING * INTO incident_row;
    END IF;
    UPDATE public.incidents
    SET status = aggregate_status
    WHERE id = p_incident_id
    RETURNING * INTO incident_row;
  END IF;

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'user_id', a.user_id,
    'status', a.responder_status,
    'status_updated_at', a.status_updated_at
  ) ORDER BY a.assigned_at, a.user_id), '[]'::jsonb)
  INTO team_statuses
  FROM public.incident_assignments a
  WHERE a.incident_id = p_incident_id;

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
                          FROM public.incident_assignments a WHERE a.incident_id = p_incident_id),
    'assigned_user_statuses', team_statuses,
    'created_at', incident_row.created_at,
    'requester_edit_count', incident_row.requester_edit_count,
    'requester_last_edited_at', incident_row.requester_last_edited_at
  );
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_update_my_assignment_status(bigint, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_update_my_assignment_status(bigint, text) TO authenticated;

-- Invoker-safe Data API wrapper; authorization and transitions are enforced again
-- inside the private implementation.
CREATE OR REPLACE FUNCTION public.api_update_my_assignment_status(
  p_incident_id bigint,
  p_status text
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT private.tacresq_update_my_assignment_status(p_incident_id, p_status);
$function$;
REVOKE ALL ON FUNCTION public.api_update_my_assignment_status(bigint, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_update_my_assignment_status(bigint, text) TO authenticated;


-- Return per-unit progress in the existing queue and "my incidents" payloads.
CREATE OR REPLACE FUNCTION public.api_my_incidents()
RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER
SET search_path = pg_catalog, public
AS $function$
  SELECT coalesce(jsonb_agg(
    jsonb_build_object(
      'id', i.id, 'type', i.type, 'description', i.description,
      'latitude', i.latitude, 'longitude', i.longitude, 'accuracy_m', i.accuracy_m,
      'location_notes', i.location_notes, 'location_recorded_at', i.location_recorded_at,
      'location_source', i.location_source, 'status', i.status,
      'requester_name', r.name, 'requester_phone', r.phone,
      'assigned_to', i.assigned_to,
      'assigned_user_ids', coalesce((SELECT jsonb_agg(a.user_id ORDER BY a.assigned_at, a.user_id)
        FROM public.incident_assignments a WHERE a.incident_id = i.id), '[]'::jsonb),
      'assigned_user_statuses', coalesce((SELECT jsonb_agg(jsonb_build_object(
        'user_id', a.user_id, 'status', a.responder_status, 'status_updated_at', a.status_updated_at
      ) ORDER BY a.assigned_at, a.user_id)
        FROM public.incident_assignments a WHERE a.incident_id = i.id), '[]'::jsonb),
      'created_at', i.created_at,
      'requester_edit_count', i.requester_edit_count,
      'requester_last_edited_at', i.requester_last_edited_at
    ) ORDER BY i.created_at DESC
  ), '[]'::jsonb)
  FROM public.incidents i
  JOIN public.profiles r ON r.id = i.requester_id
  WHERE i.requester_id IN (SELECT id FROM public.profiles WHERE auth_user_id = auth.uid())
     OR i.assigned_to IN (SELECT id FROM public.profiles WHERE auth_user_id = auth.uid())
     OR private.tacresq_is_incident_assignee(i.id);
$function$;

CREATE OR REPLACE FUNCTION public.api_all_incidents()
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER
SET search_path = pg_catalog, public
AS $function$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE auth_user_id = auth.uid() AND role = 'dispatcher') THEN
    RAISE EXCEPTION 'Only dispatchers can view the queue' USING ERRCODE = '42501';
  END IF;
  RETURN (
    SELECT coalesce(jsonb_agg(
      jsonb_build_object(
        'id', i.id, 'type', i.type, 'description', i.description,
        'latitude', i.latitude, 'longitude', i.longitude, 'accuracy_m', i.accuracy_m,
        'location_notes', i.location_notes, 'location_recorded_at', i.location_recorded_at,
        'location_source', i.location_source, 'status', i.status,
        'requester_name', r.name, 'requester_phone', r.phone,
        'assigned_to', i.assigned_to,
        'assigned_user_ids', coalesce((SELECT jsonb_agg(a.user_id ORDER BY a.assigned_at, a.user_id)
          FROM public.incident_assignments a WHERE a.incident_id = i.id), '[]'::jsonb),
        'assigned_user_statuses', coalesce((SELECT jsonb_agg(jsonb_build_object(
          'user_id', a.user_id, 'status', a.responder_status, 'status_updated_at', a.status_updated_at
        ) ORDER BY a.assigned_at, a.user_id)
          FROM public.incident_assignments a WHERE a.incident_id = i.id), '[]'::jsonb),
        'created_at', i.created_at,
        'requester_edit_count', i.requester_edit_count,
        'requester_last_edited_at', i.requester_last_edited_at
      ) ORDER BY i.created_at DESC
    ), '[]'::jsonb)
    FROM public.incidents i JOIN public.profiles r ON r.id = i.requester_id
  );
END;
$function$;

-- Unit cards now report each member's own progress instead of copying the
-- shared incident status to every assigned responder.
CREATE OR REPLACE FUNCTION public.api_units()
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public, auth
AS $function$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE auth_user_id = auth.uid() AND role = 'dispatcher') THEN
    RAISE EXCEPTION 'Only dispatchers can view field units' USING ERRCODE = '42501';
  END IF;
  RETURN (
    SELECT coalesce(jsonb_agg(jsonb_build_object(
      'id', p.id, 'name', p.name, 'email', p.email, 'phone', p.phone, 'role', p.role,
      'duty_status', coalesce((
        SELECT a.responder_status
        FROM public.incident_assignments a
        JOIN public.incidents i ON i.id = a.incident_id
        WHERE a.user_id = p.id AND i.status IN ('assigned','en_route','on_scene','transporting')
        ORDER BY i.created_at DESC LIMIT 1
      ), p.duty_status)
    ) ORDER BY p.name), '[]'::jsonb)
    FROM public.profiles p WHERE p.role IN ('responder','driver')
  );
END;
$function$;

COMMIT;
