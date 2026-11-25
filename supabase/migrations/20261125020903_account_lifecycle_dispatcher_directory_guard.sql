BEGIN;

-- The field-unit directory exposes responder contact details and assignment
-- availability. Preserve its existing query as a private implementation and
-- guard the callable SECURITY DEFINER boundary with an active dispatcher lock.
ALTER FUNCTION public.api_units() SET SCHEMA private;
ALTER FUNCTION private.api_units() RENAME TO tacresq_api_units_internal;
REVOKE ALL ON FUNCTION private.tacresq_api_units_internal()
  FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.api_units()
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
  actor_role text;
BEGIN
  SELECT p.id, p.role INTO actor_id, actor_role
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  IF actor_id IS NULL OR actor_role IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only active dispatchers can view field units'
      USING ERRCODE = '42501';
  END IF;

  RETURN private.tacresq_api_units_internal();
END;
$function$;

REVOKE ALL ON FUNCTION public.api_units() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_units() TO authenticated;

-- The dispatcher incident queue is SECURITY INVOKER. Use the shared
-- active-aware actor-role helper rather than a raw profile-role lookup.
CREATE OR REPLACE FUNCTION public.api_all_incidents()
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, public, private
AS $function$
BEGIN
  IF private.tacresq_actor_role() IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only active dispatchers can view the queue'
      USING ERRCODE = '42501';
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
$function$;
REVOKE ALL ON FUNCTION public.api_all_incidents() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_all_incidents() TO authenticated;

COMMIT;
