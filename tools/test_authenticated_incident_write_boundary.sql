-- Exercise RPC-only incident writes as the actual authenticated database role.
-- This runs only in the disposable PostgreSQL migration-replay database.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000143',
  'incident-write-boundary@example.test',
  '{"name":"Incident Write Boundary","phone":"+63917000043","role":"requester"}'::jsonb
);

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude,
  location_recorded_at, location_source
)
SELECT p.id, 'medical', 'Authenticated write-boundary fixture',
       11.244, 125.003, now(), 'manual'
FROM public.profiles p
WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000143';

SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000143', false);

DO $incident_write_boundary$
DECLARE
  incident_id bigint;
  result jsonb;
  denied boolean := false;
  persisted_status text;
BEGIN
  SELECT i.id INTO incident_id
  FROM public.incidents i
  JOIN public.profiles p ON p.id = i.requester_id
  WHERE p.auth_user_id = auth.uid()
    AND i.description = 'Authenticated write-boundary fixture';

  IF incident_id IS NULL THEN
    RAISE EXCEPTION 'Authenticated requester could not read its own fixture incident';
  END IF;

  BEGIN
    UPDATE public.incidents
       SET status = 'cancelled'
     WHERE id = incident_id;
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;

  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated direct incident UPDATE was not denied';
  END IF;

  -- The supported SECURITY DEFINER RPC must remain callable and succeed.
  result := public.api_cancel_my_incident(incident_id);
  IF result->>'status' IS DISTINCT FROM 'cancelled' THEN
    RAISE EXCEPTION 'Requester cancellation RPC failed after direct DML revocation: %', result;
  END IF;

  SELECT status INTO persisted_status
  FROM public.incidents
  WHERE id = incident_id;

  IF persisted_status IS DISTINCT FROM 'cancelled' THEN
    RAISE EXCEPTION 'Cancellation RPC did not persist terminal status: %', persisted_status;
  END IF;
END;
$incident_write_boundary$;



-- Responder status updates must still work through the supported invoker wrapper
-- after direct authenticated incident-table DML has been revoked.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES
  ('00000000-0000-4000-8000-000000000144', 'incident-write-requester-144@example.test', '{"name":"Incident Write Requester","phone":"+63917000144"}'),
  ('00000000-0000-4000-8000-000000000145', 'incident-write-responder-145@example.test', '{"name":"Incident Write Responder","phone":"+63917000145"}');

UPDATE public.profiles
SET role = 'responder', duty_status = 'available'
WHERE auth_user_id = '00000000-0000-4000-8000-000000000145';

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude,
  location_recorded_at, location_source, assigned_to, status, is_claimable
)
SELECT requester.id, 'medical', 'Authenticated responder status RPC boundary fixture',
       11.244, 125.003, now(), 'manual', responder.id, 'assigned', true
FROM public.profiles requester
CROSS JOIN public.profiles responder
WHERE requester.auth_user_id = '00000000-0000-4000-8000-000000000144'
  AND responder.auth_user_id = '00000000-0000-4000-8000-000000000145';

INSERT INTO public.incident_assignments (incident_id, user_id, assigned_by)
SELECT i.id, responder.id, NULL
FROM public.incidents i
JOIN public.profiles requester ON requester.id = i.requester_id
JOIN public.profiles responder ON responder.auth_user_id = '00000000-0000-4000-8000-000000000145'
WHERE requester.auth_user_id = '00000000-0000-4000-8000-000000000144'
  AND i.description = 'Authenticated responder status RPC boundary fixture';

SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000145', false);

DO $responder_write_boundary$
DECLARE
  incident_id bigint;
  denied boolean := false;
  result jsonb;
  final_status text;
  final_claimable boolean;
BEGIN
  SELECT i.id INTO incident_id
  FROM public.incidents i
  JOIN public.incident_assignments a ON a.incident_id = i.id
  JOIN public.profiles p ON p.id = a.user_id
  WHERE p.auth_user_id = auth.uid()
    AND i.description = 'Authenticated responder status RPC boundary fixture';

  IF incident_id IS NULL THEN
    RAISE EXCEPTION 'Authenticated responder could not read its assigned incident';
  END IF;

  BEGIN
    UPDATE public.incidents SET status = 'cancelled' WHERE id = incident_id;
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated responder direct incident UPDATE was not denied';
  END IF;

  -- The legacy public endpoint must remain compatible and delegate to the
  -- private SECURITY DEFINER implementation with its own assignment checks.
  result := public.api_update_incident_status(incident_id, 'en_route');
  IF result->>'status' IS DISTINCT FROM 'en_route' THEN
    RAISE EXCEPTION 'Authenticated responder status RPC failed after direct DML revocation: %', result;
  END IF;

  SELECT status, is_claimable INTO final_status, final_claimable
  FROM public.incidents WHERE id = incident_id;
  IF final_status IS DISTINCT FROM 'en_route' OR final_claimable IS DISTINCT FROM false THEN
    RAISE EXCEPTION 'Responder status transition did not clear claimability: status %, claimable %',
      final_status, final_claimable;
  END IF;
END;
$responder_write_boundary$;

RESET ROLE;
SELECT set_config('request.jwt.claim.sub', '', false);



DO $claimability_audit_boundary$
DECLARE
  incident_id bigint;
  actor_id bigint;
  matching_events integer;
BEGIN
  SELECT id INTO actor_id
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000145';

  SELECT id INTO incident_id
  FROM public.incidents
  WHERE description = 'Authenticated responder status RPC boundary fixture';

  SELECT count(*) INTO matching_events
  FROM public.incident_claimability_logs
  WHERE incident_id = claimability_audit_boundary.incident_id
    AND actor_profile_id = claimability_audit_boundary.actor_id
    AND source_rpc = 'incident_status_transition'
    AND previous_claimable IS TRUE
    AND new_claimable IS FALSE
    AND incident_status = 'en_route';

  IF matching_events <> 1 THEN
    RAISE EXCEPTION 'Expected exactly one attributable automatic claimability audit event, found %',
      matching_events;
  END IF;
END;
$claimability_audit_boundary$;
