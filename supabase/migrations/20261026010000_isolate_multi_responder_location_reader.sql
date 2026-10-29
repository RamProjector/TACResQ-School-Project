-- Preserve multi-responder GPS visibility without relying on a row policy
-- that historically only recognized incidents.assigned_to (the lead unit).
-- The public RPC remains SECURITY INVOKER; this narrowly scoped private helper
-- bypasses location_logs RLS only after checking the caller's dispatcher role.
BEGIN;

-- The public invoker wrapper resolves this private helper as the caller.
-- Schema USAGE does not grant table access or function EXECUTE by itself.
GRANT USAGE ON SCHEMA private TO authenticated;

CREATE OR REPLACE FUNCTION private.tacresq_active_responder_locations()
RETURNS TABLE (
  incident_id bigint,
  user_id bigint,
  latitude double precision,
  longitude double precision,
  accuracy_m real,
  recorded_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  IF private.tacresq_actor_role() IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can view responder locations'
      USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
    SELECT DISTINCT ON (i.id, l.user_id)
      i.id, l.user_id, l.latitude, l.longitude, l.accuracy_m, l.recorded_at
    FROM public.incidents i
    JOIN public.incident_assignments a ON a.incident_id = i.id
    JOIN public.location_logs l ON l.incident_id = i.id AND l.user_id = a.user_id
    WHERE i.status = 'en_route'
      AND a.responder_status = 'en_route'
      AND l.latitude BETWEEN -90 AND 90
      AND l.longitude BETWEEN -180 AND 180
      AND (l.accuracy_m IS NULL OR l.accuracy_m >= 0)
      AND l.recorded_at <= pg_catalog.now() + interval '5 minutes'
      AND COALESCE(l.received_at, l.recorded_at) >= pg_catalog.now() - interval '2 minutes'
    ORDER BY i.id, l.user_id, COALESCE(l.received_at, l.recorded_at) DESC;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_active_responder_locations() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_active_responder_locations() TO authenticated;

CREATE OR REPLACE FUNCTION public.api_active_responder_locations()
RETURNS TABLE (
  incident_id bigint,
  user_id bigint,
  latitude double precision,
  longitude double precision,
  accuracy_m real,
  recorded_at timestamptz
)
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT * FROM private.tacresq_active_responder_locations();
$function$;

REVOKE ALL ON FUNCTION public.api_active_responder_locations() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_active_responder_locations() TO authenticated;

COMMIT;
