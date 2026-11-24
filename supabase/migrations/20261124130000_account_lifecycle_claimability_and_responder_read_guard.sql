BEGIN;

-- Claimability is a dispatcher mutation. The previous RPC re-read and locked
-- the dispatcher profile, but did not include account_status in that locked
-- authorization decision. Keep its incident -> profile lock order and audit
-- transaction unchanged while making active state explicit.
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
  v_actor_role text;
  v_incident public.incidents;
  v_previous_claimable boolean;
BEGIN
  v_auth_uid := auth.uid();
  IF v_auth_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
  END IF;

  IF p_incident_id IS NULL OR p_incident_id < 1 OR p_claimable IS NULL THEN
    RAISE EXCEPTION 'Incident and claimability values are required.' USING ERRCODE = '22023';
  END IF;

  SELECT role INTO v_actor_role
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid;

  IF v_actor_role IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can toggle field claiming for incidents.' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO v_incident
  FROM public.incidents
  WHERE id = p_incident_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Incident not found.' USING ERRCODE = 'P0002';
  END IF;

  -- Preserve the established incident -> profile lock order. Suspension and
  -- claimability changes now serialize on the same profile row.
  SELECT * INTO v_actor
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid
  FOR UPDATE;

  IF v_actor.id IS NULL
     OR v_actor.role IS DISTINCT FROM 'dispatcher'
     OR v_actor.account_status IS DISTINCT FROM 'active' THEN
    RAISE EXCEPTION 'Only active dispatchers can toggle field claiming for incidents.' USING ERRCODE = '42501';
  END IF;

  IF p_claimable IS TRUE AND v_incident.status NOT IN ('pending', 'assigned') THEN
    RAISE EXCEPTION 'Only pending or assigned incidents can be made claimable.' USING ERRCODE = '22000';
  END IF;

  v_previous_claimable := v_incident.is_claimable;
  IF v_previous_claimable IS NOT DISTINCT FROM p_claimable THEN
    RETURN jsonb_build_object(
      'id', v_incident.id,
      'status', v_incident.status,
      'is_claimable', v_incident.is_claimable
    );
  END IF;

  UPDATE public.incidents
  SET is_claimable = p_claimable,
      updated_at = clock_timestamp()
  WHERE id = v_incident.id
  RETURNING * INTO v_incident;

  IF v_previous_claimable IS DISTINCT FROM v_incident.is_claimable THEN
    INSERT INTO public.incident_claimability_logs (
      incident_id, actor_profile_id, source_rpc, previous_claimable,
      new_claimable, incident_status
    ) VALUES (
      v_incident.id, v_actor.id, 'api_set_incident_claimable',
      v_previous_claimable, v_incident.is_claimable, v_incident.status
    );
  END IF;

  RETURN jsonb_build_object(
    'id', v_incident.id,
    'status', v_incident.status,
    'is_claimable', v_incident.is_claimable
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_set_incident_claimable(bigint, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_set_incident_claimable(bigint, boolean) TO authenticated;

-- The private coordinate reader was directly executable by authenticated
-- clients and checked role, but not lifecycle state. Move the old query behind
-- an internal-only name and expose a narrow active-dispatcher wrapper.
ALTER FUNCTION private.tacresq_active_responder_locations()
  RENAME TO tacresq_active_responder_locations_internal;
REVOKE ALL ON FUNCTION private.tacresq_active_responder_locations_internal()
  FROM PUBLIC, anon, authenticated;

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
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
  actor_role text;
BEGIN
  SELECT p.id, p.role
    INTO actor_id, actor_role
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  IF actor_id IS NULL OR actor_role IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only active dispatchers can view responder locations'
      USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
    SELECT * FROM private.tacresq_active_responder_locations_internal();
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_active_responder_locations() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_active_responder_locations() TO authenticated;

COMMIT;
