BEGIN;

-- This private SECURITY DEFINER implementation is directly executable by
-- authenticated clients through the invoker wrapper. Re-read and lock the
-- dispatcher profile with the active-state predicate before any incident or
-- assignment mutation, so a still-valid JWT cannot advance dispatch progress
-- after suspension.
CREATE OR REPLACE FUNCTION private.tacresq_dispatch_update_incident_status(
  p_id bigint,
  p_status text
)
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
  actor_role text;
  incident_row public.incidents;
  next_duty_status text;
BEGIN
  SELECT p.id, p.role
    INTO actor_profile_id, actor_role
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  IF actor_profile_id IS NULL OR actor_role IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only active dispatchers can update incident progress'
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
     SET responder_status = p_status,
         status_updated_at = now()
   WHERE incident_id = p_id;

  next_duty_status := CASE
    WHEN p_status = 'en_route' THEN 'en_route'
    WHEN p_status = 'on_scene' THEN 'on_scene'
    WHEN p_status = 'transporting' THEN 'transporting'
    ELSE 'available'
  END;

  UPDATE public.profiles p
     SET duty_status = next_duty_status
   WHERE p.id IN (
     SELECT a.user_id
       FROM public.incident_assignments a
      WHERE a.incident_id = p_id
   )
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
    'requester_name', (
      SELECT p.name FROM public.profiles p WHERE p.id = incident_row.requester_id
    ),
    'requester_phone', (
      SELECT p.phone FROM public.profiles p WHERE p.id = incident_row.requester_id
    ),
    'assigned_to', incident_row.assigned_to,
    'assigned_user_ids', (
      SELECT coalesce(
        jsonb_agg(a.user_id ORDER BY a.assigned_at, a.user_id), '[]'::jsonb
      )
        FROM public.incident_assignments a
       WHERE a.incident_id = p_id
    ),
    'assigned_user_statuses', (
      SELECT coalesce(
        jsonb_agg(jsonb_build_object(
          'user_id', a.user_id,
          'status', a.responder_status,
          'status_updated_at', a.status_updated_at
        ) ORDER BY a.assigned_at, a.user_id), '[]'::jsonb
      )
        FROM public.incident_assignments a
       WHERE a.incident_id = p_id
    ),
    'created_at', incident_row.created_at,
    'requester_edit_count', incident_row.requester_edit_count,
    'requester_last_edited_at', incident_row.requester_last_edited_at
  );
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_dispatch_update_incident_status(bigint, text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_dispatch_update_incident_status(bigint, text)
  TO authenticated;

COMMIT;
