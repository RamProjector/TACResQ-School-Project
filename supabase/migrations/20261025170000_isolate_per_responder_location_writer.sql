-- Keep the location writer's privileged table access out of the PostgREST-exposed
-- public schema while retaining the per-responder authorization added in
-- 20261023103737_per_responder_assignment_status.sql.
--
-- SECURITY INVOKER on the public RPC alone is insufficient because the caller's
-- direct INSERT privileges/RLS are intentionally restrictive. The private helper
-- performs the narrowly scoped write after checking identity, assignment state,
-- incident state, coordinate/timestamp bounds, and update rate.
BEGIN;

-- location_logs has no created_at column. Store server receipt time separately
-- from the client/device capture timestamp so rate limiting and latest-point
-- ordering never depend on a client-controlled clock.
-- Add the column without a volatile default so existing large location histories
-- are not rewritten just to populate a receipt timestamp. Pre-migration rows use
-- recorded_at as a fallback; all new rows receive a server timestamp by default.
ALTER TABLE public.location_logs
  ADD COLUMN IF NOT EXISTS received_at timestamptz;

ALTER TABLE public.location_logs
  ALTER COLUMN received_at SET DEFAULT pg_catalog.clock_timestamp();

-- Force authenticated clients through the validated RPC; otherwise the legacy
-- table grant plus INSERT policy would let responders bypass RPC rate/timestamp checks.
REVOKE INSERT ON TABLE public.location_logs FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION private.tacresq_record_location_v2(
  p_incident_id bigint,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_recorded_at timestamptz
) RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
  incident_status text;
  assignment_status text;
  result_id bigint;
  recorded_at_value timestamptz := coalesce(p_recorded_at, pg_catalog.now());
BEGIN
  IF p_latitude IS NULL OR p_longitude IS NULL
     OR p_latitude < -90 OR p_latitude > 90
     OR p_longitude < -180 OR p_longitude > 180 THEN
    RAISE EXCEPTION 'Location coordinates are invalid' USING ERRCODE = '22023';
  END IF;

  IF p_accuracy_m IS NOT NULL AND (p_accuracy_m < 0 OR p_accuracy_m > 100000) THEN
    RAISE EXCEPTION 'Location accuracy is outside the supported range' USING ERRCODE = '22023';
  END IF;

  IF recorded_at_value > pg_catalog.now() + interval '5 minutes' THEN
    RAISE EXCEPTION 'Location timestamp is in the future' USING ERRCODE = '22023';
  END IF;
  IF recorded_at_value < pg_catalog.now() - interval '15 minutes' THEN
    RAISE EXCEPTION 'Location timestamp is too old' USING ERRCODE = '22023';
  END IF;

  SELECT p.id, i.status, a.responder_status
    INTO actor_id, incident_status, assignment_status
    FROM public.profiles p
    JOIN public.incidents i ON i.id = p_incident_id
    JOIN public.incident_assignments a
      ON a.incident_id = i.id AND a.user_id = p.id
   WHERE p.auth_user_id = auth.uid()
     AND p.role IN ('responder', 'driver')
   FOR UPDATE OF i, a;

  IF actor_id IS NULL
     OR incident_status IS DISTINCT FROM 'en_route'
     OR assignment_status IS DISTINCT FROM 'en_route' THEN
    RAISE EXCEPTION 'Location sharing is only available to your assigned unit while you are en route'
      USING ERRCODE = '42501';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(actor_id);

  IF EXISTS (
    SELECT 1
      FROM public.location_logs l
     WHERE l.user_id = actor_id
       AND l.incident_id = p_incident_id
       AND COALESCE(l.received_at, l.recorded_at) > pg_catalog.clock_timestamp() - interval '8 seconds'
  ) THEN
    RAISE EXCEPTION 'Location updates are arriving too quickly' USING ERRCODE = '22023';
  END IF;

  INSERT INTO public.location_logs(
    user_id, incident_id, latitude, longitude, accuracy_m, recorded_at
  )
  VALUES (
    actor_id, p_incident_id, p_latitude, p_longitude, p_accuracy_m, recorded_at_value
  )
  RETURNING id INTO result_id;

  RETURN result_id;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_record_location_v2(
  bigint, double precision, double precision, real, timestamptz
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_record_location_v2(
  bigint, double precision, double precision, real, timestamptz
) TO authenticated;

-- Preserve the existing mobile RPC signature and return type.
CREATE OR REPLACE FUNCTION public.api_record_location_v2(
  p_incident_id bigint,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_recorded_at timestamptz
) RETURNS bigint
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT private.tacresq_record_location_v2(
    p_incident_id, p_latitude, p_longitude, p_accuracy_m, p_recorded_at
  );
$function$;

REVOKE ALL ON FUNCTION public.api_record_location_v2(
  bigint, double precision, double precision, real, timestamptz
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_record_location_v2(
  bigint, double precision, double precision, real, timestamptz
) TO authenticated;

-- Replace the existing read RPC as well: the historical body also referenced
-- nonexistent location_logs.created_at. Use server receipt time to select the
-- latest accepted fix, while returning the original capture timestamp so clients
-- can continue to label old fixes as stale rather than implying they are live.
CREATE OR REPLACE FUNCTION public.api_active_responder_locations()
RETURNS TABLE (
  incident_id bigint,
  user_id bigint,
  latitude double precision,
  longitude double precision,
  accuracy_m real,
  recorded_at timestamptz
)
LANGUAGE plpgsql
SECURITY INVOKER
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
    ORDER BY i.id, l.user_id, COALESCE(l.received_at, l.recorded_at) DESC;
END;
$function$;

REVOKE ALL ON FUNCTION public.api_active_responder_locations() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_active_responder_locations() TO authenticated;

COMMIT;
