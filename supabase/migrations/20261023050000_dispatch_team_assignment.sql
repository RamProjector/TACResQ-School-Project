BEGIN;

-- Keep assigned_to as the legacy/lead unit while storing every member of a response team.
CREATE TABLE IF NOT EXISTS public.incident_assignments (
  incident_id bigint NOT NULL REFERENCES public.incidents(id) ON DELETE CASCADE,
  user_id bigint NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
  assigned_by bigint REFERENCES public.profiles(id) ON DELETE SET NULL,
  assigned_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (incident_id, user_id)
);
CREATE INDEX IF NOT EXISTS incident_assignments_user_idx
  ON public.incident_assignments (user_id, incident_id);

-- Backfill existing single-unit assignments without changing their incident state.
INSERT INTO public.incident_assignments (incident_id, user_id, assigned_by)
SELECT id, assigned_to, NULL
FROM public.incidents
WHERE assigned_to IS NOT NULL AND status IN ('assigned','en_route','on_scene','transporting','resolved')
ON CONFLICT (incident_id, user_id) DO NOTHING;

CREATE OR REPLACE FUNCTION private.tacresq_is_incident_assignee(p_incident_id bigint)
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.incident_assignments a
    JOIN public.profiles p ON p.id = a.user_id
    WHERE a.incident_id = p_incident_id AND p.auth_user_id = auth.uid()
  );
$$;
REVOKE ALL ON FUNCTION private.tacresq_is_incident_assignee(bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_is_incident_assignee(bigint) TO authenticated;

ALTER TABLE public.incident_assignments ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS incident_assignments_read_participants ON public.incident_assignments;
CREATE POLICY incident_assignments_read_participants ON public.incident_assignments
FOR SELECT TO authenticated
USING (
  private.tacresq_is_incident_assignee(incident_id)
  OR private.tacresq_actor_role() = 'dispatcher'
  OR EXISTS (
    SELECT 1 FROM public.incidents i
    JOIN public.profiles requester ON requester.id = i.requester_id
    WHERE i.id = incident_assignments.incident_id AND requester.auth_user_id = auth.uid()
  )
);
GRANT SELECT ON public.incident_assignments TO authenticated;

DROP POLICY IF EXISTS incidents_read_participant_or_dispatcher ON public.incidents;
CREATE POLICY incidents_read_participant_or_dispatcher ON public.incidents FOR SELECT TO authenticated
USING (
  requester_id IN (SELECT id FROM public.profiles WHERE auth_user_id = auth.uid())
  OR assigned_to IN (SELECT id FROM public.profiles WHERE auth_user_id = auth.uid())
  OR private.tacresq_is_incident_assignee(id)
  OR private.tacresq_actor_role() = 'dispatcher'
);
DROP POLICY IF EXISTS incidents_update_dispatcher_or_assigned_unit ON public.incidents;
CREATE POLICY incidents_update_dispatcher_or_assigned_unit ON public.incidents FOR UPDATE TO authenticated
USING (
  private.tacresq_actor_role() = 'dispatcher'
  OR assigned_to IN (SELECT id FROM public.profiles WHERE auth_user_id = auth.uid())
  OR private.tacresq_is_incident_assignee(id)
)
WITH CHECK (
  private.tacresq_actor_role() = 'dispatcher'
  OR assigned_to IN (SELECT id FROM public.profiles WHERE auth_user_id = auth.uid())
  OR private.tacresq_is_incident_assignee(id)
);

CREATE OR REPLACE FUNCTION public.tacresq_guard_incident_update()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $function$
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


CREATE OR REPLACE FUNCTION public.tacresq_guard_profile_update()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $function$
BEGIN
  IF auth.uid() IS NOT NULL AND pg_trigger_depth() = 1 THEN
    IF NEW.auth_user_id IS DISTINCT FROM OLD.auth_user_id
       OR NEW.role IS DISTINCT FROM OLD.role
       OR NEW.name IS DISTINCT FROM OLD.name
       OR NEW.email IS DISTINCT FROM OLD.email
       OR NEW.phone IS DISTINCT FROM OLD.phone THEN
      RAISE EXCEPTION 'Profile identity and role fields cannot be changed by the mobile client' USING ERRCODE = '42501';
    END IF;
    IF NEW.duty_status NOT IN ('available','off_duty') THEN
      RAISE EXCEPTION 'Invalid user-set duty status' USING ERRCODE = '22023';
    END IF;
    IF EXISTS (
      SELECT 1 FROM public.incidents i
      WHERE i.assigned_to = OLD.id AND i.status IN ('assigned','en_route','on_scene','transporting')
    ) OR EXISTS (
      SELECT 1 FROM public.incident_assignments a
      JOIN public.incidents i ON i.id = a.incident_id
      WHERE a.user_id = OLD.id AND i.status IN ('assigned','en_route','on_scene','transporting')
    ) THEN
      RAISE EXCEPTION 'Finish your active incident first' USING ERRCODE = '22023';
    END IF;
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END;
$function$;
REVOKE ALL ON FUNCTION public.tacresq_guard_profile_update() FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.api_assign_incident_team(p_id bigint, p_user_ids bigint[])
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, auth AS $function$
DECLARE
  actor public.profiles;
  incident_row public.incidents;
  primary_user_id bigint;
  selected_count integer;
  assigned_ids jsonb;
  current_team_count integer := 0;
BEGIN
  SELECT * INTO actor FROM public.profiles WHERE auth_user_id = auth.uid();
  IF actor.id IS NULL OR actor.role <> 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can assign incidents' USING ERRCODE = '42501';
  END IF;
  IF p_user_ids IS NULL OR cardinality(p_user_ids) < 1 OR cardinality(p_user_ids) > 5
     OR array_position(p_user_ids, NULL) IS NOT NULL
     OR cardinality(ARRAY(SELECT DISTINCT unnest(p_user_ids))) <> cardinality(p_user_ids) THEN
    RAISE EXCEPTION 'Select between 1 and 5 different field units' USING ERRCODE = '22023';
  END IF;

  SELECT * INTO incident_row FROM public.incidents WHERE id = p_id FOR UPDATE;
  IF incident_row.id IS NULL THEN
    RAISE EXCEPTION 'Incident not found' USING ERRCODE = 'P0002';
  END IF;
  IF incident_row.status NOT IN ('pending','assigned','en_route')
     OR (incident_row.status = 'pending' AND incident_row.assigned_to IS NOT NULL)
     OR (incident_row.status <> 'pending' AND incident_row.assigned_to IS NULL) THEN
    RAISE EXCEPTION 'A response team can only be assigned or expanded before the incident reaches the scene' USING ERRCODE = '22023';
  END IF;
  SELECT count(*) INTO current_team_count
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

CREATE OR REPLACE FUNCTION public.api_assign_incident(p_id bigint, p_user_id bigint)
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
  SELECT public.api_assign_incident_team(p_id, ARRAY[p_user_id]);
$$;

CREATE OR REPLACE FUNCTION public.api_my_incidents()
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path = pg_catalog, public AS $$
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
$$;

CREATE OR REPLACE FUNCTION public.api_all_incidents()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = pg_catalog, public AS $$
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
        'created_at', i.created_at,
        'requester_edit_count', i.requester_edit_count,
        'requester_last_edited_at', i.requester_last_edited_at
      ) ORDER BY i.created_at DESC
    ), '[]'::jsonb)
    FROM public.incidents i JOIN public.profiles r ON r.id = i.requester_id
  );
END;
$$;

CREATE OR REPLACE FUNCTION public.api_units()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, auth AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE auth_user_id = auth.uid() AND role = 'dispatcher') THEN
    RAISE EXCEPTION 'Only dispatchers can view field units' USING ERRCODE = '42501';
  END IF;
  RETURN (
    SELECT coalesce(jsonb_agg(jsonb_build_object(
      'id', p.id, 'name', p.name, 'email', p.email, 'phone', p.phone, 'role', p.role,
      'duty_status', coalesce((
        SELECT CASE i.status
          WHEN 'assigned' THEN 'assigned'
          WHEN 'en_route' THEN 'en_route'
          WHEN 'on_scene' THEN 'on_scene'
          WHEN 'transporting' THEN 'transporting'
        END
        FROM public.incident_assignments a JOIN public.incidents i ON i.id = a.incident_id
        WHERE a.user_id = p.id AND i.status IN ('assigned','en_route','on_scene','transporting')
        ORDER BY i.created_at DESC LIMIT 1
      ), p.duty_status)
    ) ORDER BY p.name), '[]'::jsonb)
    FROM public.profiles p WHERE p.role IN ('responder','driver')
  );
END;
$$;

CREATE OR REPLACE FUNCTION public.api_set_duty_status(p_duty_status text)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, public AS $$
DECLARE p public.profiles;
BEGIN
  IF p_duty_status NOT IN ('available','off_duty') THEN
    RAISE EXCEPTION 'Duty status may only be set to available or off duty' USING ERRCODE = '22023';
  END IF;
  SELECT * INTO p FROM public.profiles WHERE auth_user_id = auth.uid() FOR UPDATE;
  IF p.role NOT IN ('responder','driver') THEN
    RAISE EXCEPTION 'Only responders and drivers have a duty status' USING ERRCODE = '42501';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.incident_assignments a JOIN public.incidents i ON i.id = a.incident_id
    WHERE a.user_id = p.id AND i.status IN ('assigned','en_route','on_scene','transporting')
  ) OR EXISTS (
    SELECT 1 FROM public.incidents WHERE assigned_to = p.id AND status IN ('assigned','en_route','on_scene','transporting')
  ) THEN
    RAISE EXCEPTION 'Finish your active incident first' USING ERRCODE = '22023';
  END IF;
  UPDATE public.profiles SET duty_status = p_duty_status, updated_at = now() WHERE id = p.id RETURNING * INTO p;
  RETURN jsonb_build_object('id',p.id,'name',p.name,'email',p.email,'phone',p.phone,'role',p.role,'duty_status',p.duty_status);
END;
$$;



DROP POLICY IF EXISTS location_logs_read_dispatcher_or_owner ON public.location_logs;
CREATE POLICY location_logs_read_dispatcher_or_owner ON public.location_logs FOR SELECT TO authenticated
USING (
  (
    user_id IN (SELECT id FROM public.profiles WHERE auth_user_id = auth.uid() AND role IN ('responder','driver'))
    AND EXISTS (
      SELECT 1 FROM public.incidents i
      WHERE i.id = location_logs.incident_id AND i.status = 'en_route'
        AND (i.assigned_to = location_logs.user_id OR private.tacresq_is_incident_assignee(i.id))
    )
  )
  OR (
    private.tacresq_actor_role() = 'dispatcher'
    AND EXISTS (
      SELECT 1 FROM public.incidents i
      WHERE i.id = location_logs.incident_id AND i.status = 'en_route'
        AND (i.assigned_to = location_logs.user_id OR EXISTS (
          SELECT 1 FROM public.incident_assignments a
          WHERE a.incident_id = i.id AND a.user_id = location_logs.user_id
        ))
    )
  )
);
DROP POLICY IF EXISTS location_logs_insert_owner ON public.location_logs;
CREATE POLICY location_logs_insert_owner ON public.location_logs FOR INSERT TO authenticated
WITH CHECK (
  user_id IN (SELECT id FROM public.profiles WHERE auth_user_id = auth.uid() AND role IN ('responder','driver'))
  AND incident_id IS NOT NULL
  AND EXISTS (
    SELECT 1 FROM public.incidents i
    WHERE i.id = location_logs.incident_id AND i.status = 'en_route'
      AND (i.assigned_to = location_logs.user_id OR private.tacresq_is_incident_assignee(i.id))
  )
);

CREATE OR REPLACE FUNCTION public.api_record_location_v2(
  p_incident_id bigint, p_latitude double precision, p_longitude double precision,
  p_accuracy_m real, p_recorded_at timestamptz
) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $function$
DECLARE actor_id bigint; incident_status text; result_id bigint;
  recorded_at_value timestamptz := coalesce(p_recorded_at, now());
BEGIN
  IF p_latitude IS NULL OR p_longitude IS NULL OR p_latitude < -90 OR p_latitude > 90
     OR p_longitude < -180 OR p_longitude > 180 THEN
    RAISE EXCEPTION 'Location coordinates are invalid' USING ERRCODE = '22023';
  END IF;
  IF p_accuracy_m IS NOT NULL AND (p_accuracy_m < 0 OR p_accuracy_m > 100000) THEN
    RAISE EXCEPTION 'Location accuracy is outside the supported range' USING ERRCODE = '22023';
  END IF;
  IF recorded_at_value > now() + interval '5 minutes' THEN
    RAISE EXCEPTION 'Location timestamp is in the future' USING ERRCODE = '22023';
  END IF;
  IF recorded_at_value < now() - interval '15 minutes' THEN
    RAISE EXCEPTION 'Location timestamp is too old' USING ERRCODE = '22023';
  END IF;

  SELECT p.id, i.status INTO actor_id, incident_status
  FROM public.profiles p
  JOIN public.incidents i ON i.id = p_incident_id
  WHERE p.auth_user_id = auth.uid() AND p.role IN ('responder','driver')
    AND (i.assigned_to = p.id OR EXISTS (
      SELECT 1 FROM public.incident_assignments a WHERE a.incident_id = i.id AND a.user_id = p.id
    ));
  IF actor_id IS NULL OR incident_status <> 'en_route' THEN
    RAISE EXCEPTION 'Location tracking is only available to an assigned team member while en route' USING ERRCODE = '42501';
  END IF;

  PERFORM pg_advisory_xact_lock(actor_id);
  IF EXISTS (SELECT 1 FROM public.location_logs l
    WHERE l.user_id = actor_id AND l.incident_id = p_incident_id
      AND l.created_at > now() - interval '8 seconds') THEN
    RAISE EXCEPTION 'Location updates are arriving too quickly' USING ERRCODE = '22023';
  END IF;
  INSERT INTO public.location_logs(user_id, incident_id, latitude, longitude, accuracy_m, recorded_at)
  VALUES (actor_id, p_incident_id, p_latitude, p_longitude, p_accuracy_m, recorded_at_value)
  RETURNING id INTO result_id;
  RETURN result_id;
END;
$function$;
REVOKE ALL ON FUNCTION public.api_record_location_v2(bigint,double precision,double precision,real,timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_record_location_v2(bigint,double precision,double precision,real,timestamptz) TO authenticated;

CREATE OR REPLACE FUNCTION public.api_active_responder_locations()
RETURNS TABLE (incident_id bigint, user_id bigint, latitude double precision, longitude double precision, accuracy_m real, recorded_at timestamptz)
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, public, pg_temp AS $function$
BEGIN
  IF private.tacresq_actor_role() IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can view responder locations' USING ERRCODE = '42501';
  END IF;
  RETURN QUERY
    SELECT DISTINCT ON (i.id, l.user_id)
      i.id, l.user_id, l.latitude, l.longitude, l.accuracy_m, l.recorded_at
    FROM public.incidents i
    JOIN public.incident_assignments a ON a.incident_id = i.id
    JOIN public.location_logs l ON l.incident_id = i.id AND l.user_id = a.user_id
    WHERE i.status = 'en_route'
      AND l.latitude BETWEEN -90 AND 90 AND l.longitude BETWEEN -180 AND 180
      AND (l.accuracy_m IS NULL OR l.accuracy_m >= 0)
      AND l.recorded_at <= now() + interval '5 minutes'
    ORDER BY i.id, l.user_id, l.recorded_at DESC, l.created_at DESC;
END;
$function$;
REVOKE ALL ON FUNCTION public.api_active_responder_locations() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_active_responder_locations() TO authenticated;


CREATE OR REPLACE FUNCTION public.api_update_incident_status(p_id bigint, p_status text)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, public AS $function$
DECLARE
  actor public.profiles;
  incident_row public.incidents;
  allowed boolean;
BEGIN
  SELECT * INTO actor FROM public.profiles WHERE auth_user_id = auth.uid();
  SELECT * INTO incident_row FROM public.incidents WHERE id = p_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Incident not found' USING ERRCODE = 'P0002'; END IF;
  IF actor.role NOT IN ('responder','driver') OR (
       incident_row.assigned_to IS DISTINCT FROM actor.id
       AND NOT EXISTS (SELECT 1 FROM public.incident_assignments a WHERE a.incident_id = p_id AND a.user_id = actor.id)
  ) THEN
    RAISE EXCEPTION 'Incident is not assigned to this field unit' USING ERRCODE = '42501';
  END IF;
  allowed := (incident_row.status = 'assigned' AND p_status = 'en_route')
    OR (incident_row.status = 'en_route' AND p_status = 'on_scene')
    OR (incident_row.status = 'on_scene' AND p_status IN ('transporting','resolved'))
    OR (incident_row.status = 'transporting' AND p_status = 'resolved');
  IF NOT allowed THEN RAISE EXCEPTION 'Invalid incident status transition' USING ERRCODE = '22023'; END IF;
  UPDATE public.incidents SET status = p_status WHERE id = incident_row.id RETURNING * INTO incident_row;
  RETURN jsonb_build_object(
    'id', incident_row.id, 'type', incident_row.type, 'description', incident_row.description,
    'latitude', incident_row.latitude, 'longitude', incident_row.longitude, 'accuracy_m', incident_row.accuracy_m,
    'location_notes', incident_row.location_notes, 'location_recorded_at', incident_row.location_recorded_at,
    'location_source', incident_row.location_source, 'status', incident_row.status,
    'requester_name', (SELECT name FROM public.profiles WHERE id = incident_row.requester_id),
    'requester_phone', (SELECT phone FROM public.profiles WHERE id = incident_row.requester_id),
    'assigned_to', incident_row.assigned_to,
    'assigned_user_ids', coalesce((SELECT jsonb_agg(a.user_id ORDER BY a.assigned_at, a.user_id)
      FROM public.incident_assignments a WHERE a.incident_id = incident_row.id), '[]'::jsonb),
    'created_at', incident_row.created_at,
    'requester_edit_count', incident_row.requester_edit_count,
    'requester_last_edited_at', incident_row.requester_last_edited_at
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_update_incident_status(bigint,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_update_incident_status(bigint,text) TO authenticated;
REVOKE ALL ON FUNCTION public.api_assign_incident_team(bigint,bigint[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_assign_incident_team(bigint,bigint[]) TO authenticated;
REVOKE ALL ON FUNCTION public.api_assign_incident(bigint,bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_assign_incident(bigint,bigint) TO authenticated;

COMMIT;
