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

RESET ROLE;
SELECT set_config('request.jwt.claim.sub', '', false);
