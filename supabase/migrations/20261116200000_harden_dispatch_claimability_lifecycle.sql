BEGIN;

-- Serialize claimability changes with cancellation, completion, and field claims.
-- Lock order is incident -> dispatcher profile, matching the field-claim RPC.
CREATE OR REPLACE FUNCTION public.api_set_incident_claimable(
  p_incident_id bigint,
  p_claimable boolean
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_auth_uid uuid;
  v_actor public.profiles;
  v_incident public.incidents;
BEGIN
  v_auth_uid := auth.uid();
  IF v_auth_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
  END IF;

  IF p_incident_id IS NULL OR p_incident_id < 1 OR p_claimable IS NULL THEN
    RAISE EXCEPTION 'Incident and claimability values are required.' USING ERRCODE = '22023';
  END IF;

  -- Acquire the incident lock before checking state so a concurrent terminal
  -- transition cannot be followed by a stale claimability update.
  SELECT * INTO v_incident
  FROM public.incidents
  WHERE id = p_incident_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Incident not found.' USING ERRCODE = 'P0002';
  END IF;

  -- Lock and re-read the actor after the incident, using the same lock order as
  -- field claiming. This prevents a concurrent role change from authorizing a
  -- stale dispatcher profile.
  SELECT * INTO v_actor
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid
  FOR UPDATE;

  IF v_actor.id IS NULL OR v_actor.role IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can toggle field claiming for incidents.' USING ERRCODE = '42501';
  END IF;

  IF v_incident.status IN ('cancelled', 'completed', 'resolved') THEN
    RAISE EXCEPTION 'Closed incidents cannot be made claimable.' USING ERRCODE = '22000';
  END IF;

  UPDATE public.incidents
  SET is_claimable = p_claimable,
      updated_at = clock_timestamp()
  WHERE id = v_incident.id
  RETURNING * INTO v_incident;

  RETURN jsonb_build_object(
    'id', v_incident.id,
    'status', v_incident.status,
    'is_claimable', v_incident.is_claimable
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_set_incident_claimable(bigint, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_set_incident_claimable(bigint, boolean) TO authenticated;

COMMIT;
