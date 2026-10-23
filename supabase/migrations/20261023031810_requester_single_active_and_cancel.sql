BEGIN;

-- Serialize idempotent requests on the requester row before the insert trigger
-- acquires the same lock. This avoids lock-upgrade deadlocks for concurrent SOS taps.
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

REVOKE ALL ON FUNCTION public.api_create_incident_v4(text, text, double precision, double precision, real, text, timestamptz, text, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_create_incident_v4(text, text, double precision, double precision, real, text, timestamptz, text, uuid) TO authenticated;

-- Enforce one active emergency per requester for every incident-creation RPC,
-- including older compatibility RPCs. The profile-row lock serializes concurrent inserts.
CREATE OR REPLACE FUNCTION public.tacresq_guard_single_active_incident()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  active_id bigint;
BEGIN
  PERFORM 1
    FROM public.profiles
   WHERE id = NEW.requester_id
   FOR UPDATE;

  SELECT id
    INTO active_id
    FROM public.incidents
   WHERE requester_id = NEW.requester_id
     AND status NOT IN ('resolved', 'cancelled')
   ORDER BY created_at DESC
   LIMIT 1;

  IF active_id IS NOT NULL THEN
    RAISE EXCEPTION
      'You already have active request #%. Cancel it while waiting for dispatch or contact dispatch if a unit may be responding.',
      active_id
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION public.tacresq_guard_single_active_incident() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS tacresq_single_active_incident ON public.incidents;
CREATE TRIGGER tacresq_single_active_incident
  BEFORE INSERT ON public.incidents
  FOR EACH ROW
  EXECUTE FUNCTION public.tacresq_guard_single_active_incident();

-- Requesters can cancel only their own request while it is still pending.
-- Assignment and cancellation both lock the incident row, so whichever wins first
-- determines whether cancellation is accepted. Assigned/in-progress requests require dispatch.
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

REVOKE ALL ON FUNCTION public.api_cancel_my_incident(bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_cancel_my_incident(bigint) TO authenticated;

COMMIT;
