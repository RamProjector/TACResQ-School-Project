BEGIN;

-- SOS idempotency and cancellation both lock the requester profile before
-- returning any existing/request state. Re-read current account state under
-- that same lock, so a suspended caller cannot replay an earlier SOS response.
create or replace function public.api_create_incident_v4(
  p_type text,
  p_description text,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_location_notes text,
  p_location_recorded_at timestamptz,
  p_location_source text,
  p_client_request_id uuid
) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $function$
declare
  profile_row public.profiles;
  saved_payload jsonb;
  saved_response jsonb;
  current_payload jsonb;
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  select * into profile_row
    from public.profiles
   where auth_user_id = auth.uid()
     and account_status = 'active'
   for update;
  if profile_row.id is null or profile_row.role <> 'requester' then
    raise exception 'Only requesters can submit incidents' using errcode = '42501';
  end if;
  if p_client_request_id is null then
    raise exception 'A request identifier is required' using errcode = '22023';
  end if;

  current_payload := jsonb_build_object(
    'type', p_type,
    'description', nullif(trim(p_description), ''),
    'latitude', p_latitude,
    'longitude', p_longitude,
    'accuracy_m', p_accuracy_m,
    'location_notes', nullif(trim(p_location_notes), ''),
    'location_recorded_at', p_location_recorded_at,
    'location_source', p_location_source
  );

  -- The unique key serializes concurrent retries. The conflicting INSERT waits for
  -- the first transaction; SELECT FOR UPDATE then sees its committed response.
  insert into public.incident_submission_keys(
    requester_id, client_request_id, request_payload
  ) values (
    profile_row.id, p_client_request_id, current_payload
  ) on conflict (requester_id, client_request_id) do nothing;

  select request_payload, response_payload
    into saved_payload, saved_response
    from public.incident_submission_keys
   where requester_id = profile_row.id
     and client_request_id = p_client_request_id
   for update;

  if saved_payload is distinct from current_payload then
    raise exception 'This request identifier is already tied to a different SOS. Start a new request.' using errcode = '22023';
  end if;
  if saved_response is not null then
    return saved_response;
  end if;

  saved_response := public.api_create_incident_v3(
    p_type, p_description, p_latitude, p_longitude, p_accuracy_m,
    p_location_notes, p_location_recorded_at, p_location_source
  );

  update public.incident_submission_keys
     set response_payload = saved_response
   where requester_id = profile_row.id
     and client_request_id = p_client_request_id;

  return saved_response;
end;
$function$;
REVOKE ALL ON FUNCTION public.api_create_incident_v4(text, text, double precision, double precision, real, text, timestamptz, text, uuid)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_create_incident_v4(text, text, double precision, double precision, real, text, timestamptz, text, uuid)
  TO authenticated;

CREATE OR REPLACE FUNCTION public.api_cancel_my_incident(p_id bigint)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  profile_row public.profiles;
  incident_row public.incidents;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO profile_row
    FROM public.profiles
   WHERE auth_user_id = auth.uid()
     AND account_status = 'active'
   FOR UPDATE;

  IF profile_row.id IS NULL OR profile_row.role <> 'requester' THEN
    RAISE EXCEPTION 'Only requesters can cancel their own requests' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO incident_row
    FROM public.incidents
   WHERE id = p_id
     AND requester_id = profile_row.id
   FOR UPDATE;

  IF incident_row.id IS NULL THEN
    RAISE EXCEPTION 'Request not found' USING ERRCODE = 'P0002';
  END IF;

  IF incident_row.status <> 'pending' THEN
    RAISE EXCEPTION
      'This request can only be cancelled before dispatch assignment. A unit may already be responding; contact dispatch.'
      USING ERRCODE = '22023';
  END IF;

  UPDATE public.incidents
     SET status = 'cancelled'
   WHERE id = incident_row.id
   RETURNING * INTO incident_row;

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
    'requester_name', profile_row.name,
    'requester_phone', profile_row.phone,
    'assigned_to', incident_row.assigned_to,
    'created_at', incident_row.created_at
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.api_cancel_my_incident(bigint)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_cancel_my_incident(bigint)
  TO authenticated;

-- Preserve the established incident-then-profile lock order. The initial
-- lookup avoids locking incidents for suspended users; the second profile
-- lock rechecks active state after the incident lock to close the race.
CREATE OR REPLACE FUNCTION public.api_claim_incident(p_incident_id bigint)
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
  v_assigned_to bigint;
  v_already_assigned boolean := false;
  v_assignment_count integer;
BEGIN
  v_auth_uid := auth.uid();
  IF v_auth_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
  END IF;

  -- Resolve identity first without taking a lock so an arbitrary signed-in user
  -- cannot lock incident rows. Role is checked again after the profile lock.
  SELECT id, role INTO v_actor_id, v_actor_role
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid
    AND account_status = 'active';

  IF v_actor_id IS NULL OR v_actor_role IS NULL OR v_actor_role NOT IN ('responder', 'driver') THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'UNAUTHORIZED',
      'message', 'Only responders or drivers can claim incidents.'
    );
  END IF;

  -- Lock order is incident then profile, the same order used by dispatcher
  -- assignment. FOR UPDATE serializes competing changes to this incident.
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

  -- A field claim is offered only for pending/assigned incidents. Never accept
  -- a stale client request once a team has reached the scene or begun transport.
  IF NOT COALESCE(v_claimable, false) OR v_status NOT IN ('pending', 'assigned') THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'NOT_CLAIMABLE',
      'message', 'Incident is not available for field claiming.'
    );
  END IF;

  -- Lock and re-read the profile after the incident lock. Concurrent claims for
  -- different incidents by this unit now serialize on the same profile row.
  SELECT role, duty_status INTO v_actor_role, v_duty_status
  FROM public.profiles
  WHERE id = v_actor_id AND auth_user_id = v_auth_uid
    AND account_status = 'active'
  FOR UPDATE;

  IF v_actor_role IS NULL OR v_actor_role NOT IN ('responder', 'driver') THEN
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

  SELECT EXISTS (
    SELECT 1
    FROM public.incident_assignments a
    WHERE a.incident_id = p_incident_id AND a.user_id = v_actor_id
  ) OR v_assigned_to = v_actor_id
  INTO v_already_assigned;

  IF v_already_assigned THEN
    RETURN jsonb_build_object(
      'status', 'already_assigned',
      'incident_id', p_incident_id,
      'user_id', v_actor_id
    );
  END IF;

  -- A unit may belong to only one active incident at a time, whether it was
  -- assigned by a dispatcher or claimed through the field-claim workflow.
  IF EXISTS (
    SELECT 1
    FROM public.incident_assignments a
    JOIN public.incidents active_i ON active_i.id = a.incident_id
    WHERE a.user_id = v_actor_id
      AND a.incident_id <> p_incident_id
      AND active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) OR EXISTS (
    SELECT 1
    FROM public.incidents active_i
    WHERE active_i.assigned_to = v_actor_id
      AND active_i.id <> p_incident_id
      AND active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'UNIT_BUSY',
      'message', 'You are already assigned to another active incident.'
    );
  END IF;

  SELECT count(*) INTO v_assignment_count
  FROM public.incident_assignments
  WHERE incident_id = p_incident_id;

  -- Count the legacy lead assignment too if a historical row has not yet been
  -- backfilled into incident_assignments.
  IF v_assigned_to IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.incident_assignments a
    WHERE a.incident_id = p_incident_id AND a.user_id = v_assigned_to
  ) THEN
    v_assignment_count := v_assignment_count + 1;
  END IF;

  IF v_assignment_count >= 5 THEN
    RETURN jsonb_build_object(
      'status', 'error',
      'code', 'GROUP_FULL',
      'message', 'Response team is already full (maximum 5 members).'
    );
  END IF;

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
REVOKE ALL ON FUNCTION public.api_claim_incident(bigint)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_claim_incident(bigint)
  TO authenticated;

COMMIT;
