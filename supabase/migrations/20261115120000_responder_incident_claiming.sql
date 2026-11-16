BEGIN;

-- Add is_claimable column to incidents table if not present.
ALTER TABLE public.incidents
  ADD COLUMN IF NOT EXISTS is_claimable boolean NOT NULL DEFAULT false;

-- Create index for claimable open incidents.
CREATE INDEX IF NOT EXISTS idx_incidents_claimable
  ON public.incidents (status, is_claimable)
  WHERE is_claimable = true;

-- RPC 1: api_set_incident_claimable
-- Allows dispatchers to enable or disable field claiming for an incident.
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
  v_actor_role text;
  v_incident public.incidents;
BEGIN
  v_auth_uid := auth.uid();
  IF v_auth_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
  END IF;

  SELECT role INTO v_actor_role
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid;

  IF v_actor_role IS NULL OR v_actor_role != 'dispatcher' THEN
    RAISE EXCEPTION 'Only dispatchers can toggle field claiming for incidents.' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO v_incident
  FROM public.incidents
  WHERE id = p_incident_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Incident not found.' USING ERRCODE = 'P0002';
  END IF;

  IF v_incident.status IN ('cancelled', 'completed', 'resolved') THEN
    RAISE EXCEPTION 'Closed incidents cannot be made claimable.' USING ERRCODE = '22000';
  END IF;

  UPDATE public.incidents
  SET is_claimable = p_claimable,
      updated_at = clock_timestamp()
  WHERE id = p_incident_id;

  RETURN jsonb_build_object(
    'id', p_incident_id,
    'status', v_incident.status,
    'is_claimable', p_claimable
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_set_incident_claimable(bigint, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_set_incident_claimable(bigint, boolean) TO authenticated;


-- RPC 2: api_claim_incident
-- Allows authenticated, on-duty responders or drivers to claim an open claimable incident.
CREATE OR REPLACE FUNCTION public.api_claim_incident(
  p_incident_id bigint
)
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
  v_existing_assignment public.incident_assignments;
  v_assignment_count integer;
  v_assigned_to bigint;
BEGIN
  v_auth_uid := auth.uid();
  IF v_auth_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
  END IF;

  SELECT id, role, duty_status INTO v_actor_id, v_actor_role, v_duty_status
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid;

  IF v_actor_id IS NULL OR v_actor_role NOT IN ('responder', 'driver') THEN
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

  -- Atomic row lock on target incident
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

  IF NOT COALESCE(v_claimable, false) OR v_status IN ('cancelled', 'completed', 'resolved') THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'NOT_CLAIMABLE',
      'message', 'Incident is not available for field claiming.'
    );
  END IF;

  -- Check if already assigned
  SELECT * INTO v_existing_assignment
  FROM public.incident_assignments
  WHERE incident_id = p_incident_id AND user_id = v_actor_id;

  IF FOUND THEN
    RETURN jsonb_build_object(
      'status', 'already_assigned',
      'incident_id', p_incident_id,
      'user_id', v_actor_id
    );
  END IF;

  -- Capacity check (max 5 responders per team)
  SELECT count(*) INTO v_assignment_count
  FROM public.incident_assignments
  WHERE incident_id = p_incident_id;

  IF v_assignment_count >= 5 THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'GROUP_FULL',
      'message', 'Response team is already full (maximum 5 members).'
    );
  END IF;

  -- Insert assignment
  INSERT INTO public.incident_assignments (incident_id, user_id, assigned_by, assigned_at)
  VALUES (p_incident_id, v_actor_id, v_actor_id, clock_timestamp());

  -- Set assigned_to if not already set, and advance status if pending
  IF v_assigned_to IS NULL THEN
    UPDATE public.incidents
    SET assigned_to = v_actor_id,
        status = CASE WHEN status = 'pending' THEN 'assigned' ELSE status END,
        updated_at = clock_timestamp()
    WHERE id = p_incident_id;
  ELSIF v_status = 'pending' THEN
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


-- RPC 3: api_claimable_incidents
-- Returns open claimable incidents for authenticated on-duty responders and drivers.
CREATE OR REPLACE FUNCTION public.api_claimable_incidents()
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_auth_uid uuid;
  v_actor_role text;
  v_duty_status text;
  v_result jsonb;
BEGIN
  v_auth_uid := auth.uid();
  IF v_auth_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
  END IF;

  SELECT role, duty_status INTO v_actor_role, v_duty_status
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid;

  IF v_actor_role IS NULL OR v_actor_role NOT IN ('responder', 'driver') OR v_duty_status IS DISTINCT FROM 'available' THEN
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
    AND (
      SELECT count(*) FROM public.incident_assignments a WHERE a.incident_id = i.id
    ) < 5;

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.api_claimable_incidents() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_claimable_incidents() TO authenticated;

COMMIT;
