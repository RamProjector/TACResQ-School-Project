-- Exercise requester edit authorization and location validation as the actual
-- authenticated role in the disposable PostgreSQL migration-replay database.
GRANT USAGE ON SCHEMA auth TO authenticated;
GRANT EXECUTE ON FUNCTION auth.uid() TO authenticated;

INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES
  (
    '00000000-0000-4000-8000-000000000991',
    'requester-location-edit-a@example.test',
    '{"name":"Requester Location Edit A","phone":"+63917000991","role":"requester"}'::jsonb
  ),
  (
    '00000000-0000-4000-8000-000000000992',
    'requester-location-edit-b@example.test',
    '{"name":"Requester Location Edit B","phone":"+63917000992","role":"requester"}'::jsonb
  );

-- Terminal requests remain visible in the fixture but are not editable.
INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude, accuracy_m,
  location_notes, location_recorded_at, location_source, status
)
SELECT id, 'medical', 'Cancelled report', 10.3200, 123.9200, 10,
       'Front gate', clock_timestamp(), 'gps', 'cancelled'
FROM public.profiles
WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude, accuracy_m,
  location_notes, location_recorded_at, location_source, status
)
SELECT id, 'medical', 'Original report', 10.3000, 123.9000, 12,
       'Main entrance', clock_timestamp(), 'gps', 'pending'
FROM public.profiles
WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';

-- A second request owned by another account proves object-level ownership.
INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude, accuracy_m,
  location_notes, location_recorded_at, location_source, status
)
SELECT id, 'fire', 'Other requester report', 10.3100, 123.9100, 10,
       'Side entrance', clock_timestamp(), 'gps', 'pending'
FROM public.profiles
WHERE auth_user_id = '00000000-0000-4000-8000-000000000992';

SELECT set_config(
  'tacresq.test.other_requester_incident_id',
  (
    SELECT i.id::text
    FROM public.incidents i
    JOIN public.profiles p ON p.id = i.requester_id
    WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000992'
    ORDER BY i.id DESC
    LIMIT 1
  ),
  false
);
SELECT set_config(
  'tacresq.test.cancelled_incident_id',
  (
    SELECT i.id::text
    FROM public.incidents i
    JOIN public.profiles p ON p.id = i.requester_id
    WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000991'
      AND i.status = 'cancelled'
    ORDER BY i.id DESC
    LIMIT 1
  ),
  false
);

SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000991', false);

DO $requester_location_edit_authenticated$
DECLARE
  own_incident_id bigint;
  other_incident_id bigint := current_setting('tacresq.test.other_requester_incident_id')::bigint;
  cancelled_incident_id bigint := current_setting('tacresq.test.cancelled_incident_id')::bigint;
  result jsonb;
  denied boolean;
BEGIN
  SELECT i.id INTO own_incident_id
  FROM public.incidents i
  JOIN public.profiles p ON p.id = i.requester_id
  WHERE p.auth_user_id = auth.uid()
    AND i.status = 'pending'
  ORDER BY i.id DESC
  LIMIT 1;

  IF own_incident_id IS NULL THEN
    RAISE EXCEPTION 'Requester fixture incident was not visible to its owner';
  END IF;

  -- Manual pins must not carry GPS accuracy metadata.
  denied := false;
  BEGIN
    PERFORM public.api_update_my_incident(
      own_incident_id, 'medical', 'Original report',
      10.3001, 123.9001, 5, 'Main entrance',
      clock_timestamp(), 'manual', false
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Manual pin with GPS accuracy was not rejected';
  END IF;

  -- Changed coordinates require a recent client capture/selection timestamp.
  denied := false;
  BEGIN
    PERFORM public.api_update_my_incident(
      own_incident_id, 'medical', 'Original report',
      10.3001, 123.9001, NULL, 'Main entrance',
      clock_timestamp() - interval '16 minutes', 'manual', false
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Stale location timestamp was not rejected';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_update_my_incident(
      own_incident_id, 'medical', 'Original report',
      10.3001, 123.9001, NULL, 'Main entrance',
      clock_timestamp() + interval '6 minutes', 'manual', false
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Future location timestamp was not rejected';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_update_my_incident(
      own_incident_id, 'medical', 'Original report',
      91, 123.9001, NULL, 'Main entrance',
      clock_timestamp(), 'manual', false
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Out-of-range latitude was not rejected';
  END IF;

  -- Another requester's incident must be indistinguishable from a missing object.
  denied := false;
  BEGIN
    PERFORM public.api_update_my_incident(
      other_incident_id, 'medical', 'Attempted takeover',
      10.3001, 123.9001, NULL, 'Main entrance',
      clock_timestamp(), 'manual', false
    );
  EXCEPTION WHEN no_data_found THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Requester was able to edit another account incident';
  END IF;

  -- First valid manual-pin correction must succeed without GPS accuracy.
  result := public.api_update_my_incident(
    own_incident_id, 'medical', 'Original report',
    10.3002, 123.9002, NULL, 'Main entrance',
    clock_timestamp(), 'manual', false
  );
  IF result->>'location_source' IS DISTINCT FROM 'manual'
     OR (result->>'accuracy_m') IS NOT NULL
     OR (result->>'requester_edit_count')::integer <> 1 THEN
    RAISE EXCEPTION 'Valid manual-pin edit did not persist expected metadata: %', result;
  END IF;

  -- A location-only correction can bypass the short cooldown only after review.
  result := public.api_update_my_incident(
    own_incident_id, 'medical', 'Original report',
    10.3003, 123.9003, NULL, 'Main entrance',
    clock_timestamp(), 'manual', true
  );
  IF result->>'latitude' IS DISTINCT FROM '10.3003'
     OR (result->>'requester_edit_count')::integer <> 2 THEN
    RAISE EXCEPTION 'Confirmed urgent location correction did not persist: %', result;
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_update_my_incident(
      cancelled_incident_id, 'medical', 'Cancelled report',
      10.3201, 123.9201, NULL, 'Front gate',
      clock_timestamp(), 'manual', false
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Cancelled incident was editable';
  END IF;
  -- A requester cannot cancel another account's pending incident.
  denied := false;
  BEGIN
    PERFORM public.api_cancel_my_incident(other_incident_id);
  EXCEPTION WHEN no_data_found THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Requester was able to cancel another account incident';
  END IF;

  -- A terminal request cannot be cancelled again.
  denied := false;
  BEGIN
    PERFORM public.api_cancel_my_incident(cancelled_incident_id);
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Already-cancelled incident accepted a second cancellation';
  END IF;

  -- The owner may cancel the same pending request after reviewing/editing it.
  result := public.api_cancel_my_incident(own_incident_id);
  IF result->>'status' IS DISTINCT FROM 'cancelled' THEN
    RAISE EXCEPTION 'Pending requester cancellation did not return cancelled status: %', result;
  END IF;
END;
$requester_location_edit_authenticated$;

RESET ROLE;
SELECT set_config('request.jwt.claim.sub', '', false);

DO $requester_location_edit_audit$
DECLARE
  v_incident_id bigint;
  edit_count integer;
  urgent_count integer;
BEGIN
  SELECT i.id INTO v_incident_id
  FROM public.incidents i
  JOIN public.profiles p ON p.id = i.requester_id
  WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000991'
    AND i.status = 'pending'
  ORDER BY i.id DESC
  LIMIT 1;

  SELECT count(*), count(*) FILTER (WHERE urgent_location_correction)
    INTO edit_count, urgent_count
  FROM public.incident_request_edit_logs
  WHERE incident_request_edit_logs.incident_id = v_incident_id;

  IF edit_count <> 2 OR urgent_count <> 1 THEN
    RAISE EXCEPTION 'Expected two edit audit records with one urgent correction; found total %, urgent %', edit_count, urgent_count;
  END IF;
  IF (SELECT count(*) FROM public.incident_status_logs
      WHERE incident_id = v_incident_id
        AND status = 'cancelled'
        AND changed_by = (
          SELECT id FROM public.profiles
          WHERE auth_user_id = '00000000-0000-4000-8000-000000000991'
        )) <> 1 THEN
    RAISE EXCEPTION 'Requester cancellation did not create exactly one attributable status audit record';
  END IF;

  -- Cancellation releases the one-active-request guard for a fresh SOS.
  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude, accuracy_m,
    location_notes, location_recorded_at, location_source
  )
  SELECT id, 'medical', 'New report after cancellation', 10.3300, 123.9300, 8,
         'Community entrance', clock_timestamp(), 'gps'
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';

  IF (SELECT count(*) FROM public.incidents
      WHERE requester_id = (
        SELECT id FROM public.profiles
        WHERE auth_user_id = '00000000-0000-4000-8000-000000000991'
      )
      AND status NOT IN ('resolved', 'cancelled')) <> 1 THEN
    RAISE EXCEPTION 'Cancellation did not free exactly one active-request slot';
  END IF;
END;
$requester_location_edit_audit$;

SELECT 'PASS: authenticated requester edit, cancellation ownership, terminal-state, audit, and one-active-request behavior' AS result;
