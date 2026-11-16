BEGIN;

-- Add is_claimable column to incidents table if not present.
ALTER TABLE public.incidents
  ADD COLUMN IF NOT EXISTS is_claimable boolean NOT NULL DEFAULT false;

-- Create index for claimable open incidents.
CREATE INDEX IF NOT EXISTS idx_incidents_claimable
  ON public.incidents (status, is_claimable)
  WHERE is_claimable = true;

-- Extend the existing incident guard so a responder/driver may complete only
-- the status transition created by the atomic field-claim RPC. The assignment row
-- must already exist and the caller must still be available; assigned_to remains
-- dispatcher-managed for legacy single-unit assignment compatibility.
CREATE OR REPLACE FUNCTION public.tacresq_guard_incident_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
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
    OR NEW.is_claimable IS DISTINCT FROM OLD.is_claimable
  ) THEN
    RAISE EXCEPTION 'Assigned field units cannot modify the original emergency report'
      USING ERRCODE = '42501';
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status
     AND NEW.status = 'cancelled'
     AND OLD.status = 'pending'
     AND actor_role = 'requester'
     AND actor_id = OLD.requester_id
     AND OLD.assigned_to IS NULL
     AND NEW.assigned_to IS NOT DISTINCT FROM OLD.assigned_to
     AND NEW.id IS NOT DISTINCT FROM OLD.id
     AND NEW.requester_id IS NOT DISTINCT FROM OLD.requester_id
     AND NEW.type IS NOT DISTINCT FROM OLD.type
     AND NEW.description IS NOT DISTINCT FROM OLD.description
     AND NEW.latitude IS NOT DISTINCT FROM OLD.latitude
     AND NEW.longitude IS NOT DISTINCT FROM OLD.longitude
     AND NEW.accuracy_m IS NOT DISTINCT FROM OLD.accuracy_m
     AND NEW.location_notes IS NOT DISTINCT FROM OLD.location_notes
     AND NEW.location_recorded_at IS NOT DISTINCT FROM OLD.location_recorded_at
     AND NEW.location_source IS NOT DISTINCT FROM OLD.location_source
     AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
     AND NEW.is_claimable IS NOT DISTINCT FROM OLD.is_claimable
     AND NOT EXISTS (
       SELECT 1 FROM public.incident_assignments a WHERE a.incident_id = OLD.id
     ) THEN
    RETURN NEW;
  END IF;

  -- A field claimant may move pending -> assigned only after the claim RPC has
  -- inserted that caller's assignment under the incident row lock.
  IF NEW.status IS DISTINCT FROM OLD.status
     AND NEW.status = 'assigned'
     AND OLD.status = 'pending'
     AND actor_role IN ('responder', 'driver')
     AND OLD.is_claimable IS TRUE
     AND NEW.assigned_to IS NOT DISTINCT FROM OLD.assigned_to
     AND NEW.is_claimable IS NOT DISTINCT FROM OLD.is_claimable
     AND NEW.id IS NOT DISTINCT FROM OLD.id
     AND NEW.requester_id IS NOT DISTINCT FROM OLD.requester_id
     AND NEW.type IS NOT DISTINCT FROM OLD.type
     AND NEW.description IS NOT DISTINCT FROM OLD.description
     AND NEW.latitude IS NOT DISTINCT FROM OLD.latitude
     AND NEW.longitude IS NOT DISTINCT FROM OLD.longitude
     AND NEW.accuracy_m IS NOT DISTINCT FROM OLD.accuracy_m
     AND NEW.location_notes IS NOT DISTINCT FROM OLD.location_notes
     AND NEW.location_recorded_at IS NOT DISTINCT FROM OLD.location_recorded_at
     AND NEW.location_source IS NOT DISTINCT FROM OLD.location_source
     AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
     AND NEW.requester_edit_count IS NOT DISTINCT FROM OLD.requester_edit_count
     AND NEW.requester_last_edited_at IS NOT DISTINCT FROM OLD.requester_last_edited_at
     AND EXISTS (
       SELECT 1 FROM public.incident_assignments a
       WHERE a.incident_id = OLD.id AND a.user_id = actor_id
     )
     AND EXISTS (
       SELECT 1 FROM public.profiles p
       WHERE p.id = actor_id AND p.duty_status = 'available'
     ) THEN
    RETURN NEW;
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
  SELECT status, is_claimable
  INTO v_status, v_claimable
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
