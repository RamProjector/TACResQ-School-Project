-- Runtime regression coverage for dispatcher-owned assignment release and audit history.
-- This runs after the full migration replay against an isolated CI PostgreSQL database.

INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000150', 'release-requester@example.test', '{"name":"Release Requester","phone":"+63917000150","role":"requester"}'),
  ('00000000-0000-4000-8000-000000000151', 'release-responder-one@example.test', '{"name":"Release Responder One","phone":"+63917000151","role":"responder"}'),
  ('00000000-0000-4000-8000-000000000152', 'release-responder-two@example.test', '{"name":"Release Responder Two","phone":"+63917000152","role":"responder"}'),
  ('00000000-0000-4000-8000-000000000153', 'release-driver-three@example.test', '{"name":"Release Driver Three","phone":"+63917000153","role":"driver"}'),
  ('00000000-0000-4000-8000-000000000154', 'release-arrival-requester@example.test', '{"name":"Arrival Requester","phone":"+63917000154","role":"requester"}'),
  ('00000000-0000-4000-8000-000000000155', 'release-arrival-responder@example.test', '{"name":"Arrival Responder","phone":"+63917000155","role":"responder"}'),
  ('00000000-0000-4000-8000-000000000156', 'release-arrival-driver@example.test', '{"name":"Arrival Driver","phone":"+63917000156","role":"driver"}'),
  ('00000000-0000-4000-8000-000000000157', 'release-legacy-requester@example.test', '{"name":"Legacy Reservation Requester","phone":"+63917000157","role":"requester"}'),
  ('00000000-0000-4000-8000-000000000158', 'release-admin@example.test', '{"name":"Release Admin","phone":"+63917000158","role":"admin"}');

DO $dispatcher_release_runtime_test$
<<release_test>>
DECLARE
  dispatcher_auth_id uuid := '00000000-0000-4000-8000-000000000124';
  requester_id bigint;
  responder_one_id bigint;
  responder_two_id bigint;
  driver_three_id bigint;
  arrival_requester_id bigint;
  arrival_responder_id bigint;
  arrival_driver_id bigint;
  legacy_requester_id bigint;
  admin_id bigint;
  incident_id bigint;
  legacy_incident_id bigint;
  arrival_incident_id bigint;
  primary_id bigint;
  secondary_id bigint;
  final_member_id bigint;
  response jsonb;
  rejected boolean;
  actor_id bigint;
  assigned_to_after bigint;
  duty_after text;
  event_count integer;
  function_definer boolean;
  function_settings text[];
BEGIN
  SELECT id INTO requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000150';
  SELECT id INTO responder_one_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000151';
  SELECT id INTO responder_two_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000152';
  SELECT id INTO driver_three_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000153';
  SELECT id INTO arrival_requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000154';
  SELECT id INTO arrival_responder_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000155';
  SELECT id INTO arrival_driver_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000156';
  SELECT id INTO legacy_requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000157';
  SELECT id INTO admin_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000158';

  IF requester_id IS NULL OR responder_one_id IS NULL OR responder_two_id IS NULL
     OR driver_three_id IS NULL OR arrival_requester_id IS NULL
     OR arrival_responder_id IS NULL OR arrival_driver_id IS NULL
     OR legacy_requester_id IS NULL OR admin_id IS NULL THEN
    RAISE EXCEPTION 'Release test signup fixtures did not create every profile';
  END IF;

  -- CI is a trusted database operator here. Clear the request identity while
  -- preparing role fixtures, then exercise every RPC with an explicit auth.uid().
  PERFORM set_config('request.jwt.claim.sub', '', true);
  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL
    WHERE auth_user_id = dispatcher_auth_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available'
    WHERE id IN (responder_one_id, responder_two_id, arrival_responder_id);
  UPDATE public.profiles SET role = 'driver', duty_status = 'available'
    WHERE id IN (driver_three_id, arrival_driver_id);
  UPDATE public.profiles SET role = 'requester', duty_status = NULL
    WHERE id IN (requester_id, arrival_requester_id, legacy_requester_id);
  UPDATE public.profiles SET role = 'admin', duty_status = NULL WHERE id = admin_id;
  INSERT INTO auth.sessions (id, user_id)
  VALUES (
    '10000000-0000-4000-8000-000000000158',
    '00000000-0000-4000-8000-000000000158'
  );

  SELECT id INTO actor_id FROM public.profiles WHERE auth_user_id = dispatcher_auth_id;
  IF actor_id IS NULL THEN
    RAISE EXCEPTION 'Dispatcher fixture profile is missing';
  END IF;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    requester_id, 'medical', 'Dispatcher release runtime regression',
    11.244, 125.003, now(), 'manual'
  ) RETURNING id INTO incident_id;

  -- Preserve the fixture identity outside caller-visible incident RLS. The
  -- authenticated role must not depend on reading incidents to test history.
  PERFORM set_config('tacresq.release_test_incident_id', incident_id::text, false);

  PERFORM set_config('request.jwt.claim.sub', dispatcher_auth_id::text, true);
  response := public.api_assign_incident_team(
    incident_id,
    ARRAY[responder_one_id, responder_two_id, driver_three_id]
  );
  SELECT assigned_to INTO primary_id FROM public.incidents WHERE id = incident_id;
  IF primary_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.incident_assignments AS assignment_row
    WHERE assignment_row.incident_id = release_test.incident_id AND user_id = primary_id
  ) THEN
    RAISE EXCEPTION 'Initial assignment did not establish its primary member';
  END IF;

  -- Model a legacy active reservation in another incident. The release logic
  -- deliberately checks both team membership and legacy incidents.assigned_to.
  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source, status, assigned_to
  ) VALUES (
    legacy_requester_id, 'medical', 'Legacy active reservation fixture',
    11.245, 125.004, now(), 'manual', 'assigned', primary_id
  ) RETURNING id INTO legacy_incident_id;

  IF NOT EXISTS (
    SELECT 1 FROM public.incidents AS legacy_row
    WHERE legacy_row.id = legacy_incident_id
      AND legacy_row.assigned_to = primary_id
      AND legacy_row.status = 'assigned'
  ) THEN
    RAISE EXCEPTION 'Legacy reservation fixture must persist an active assigned_to reservation';
  END IF;

  -- Duty status and assignment state are separate. The legacy reservation
  -- lives in incidents.assigned_to; use a valid in-progress duty status here.
  -- The release must preserve that status while the other active incident
  -- continues to reserve this member.
  PERFORM set_config('request.jwt.claim.sub', '', true);
  UPDATE public.profiles SET duty_status = 'en_route' WHERE id = primary_id;
  PERFORM set_config('request.jwt.claim.sub', dispatcher_auth_id::text, true);

  -- A field unit cannot invoke the release RPC, even though the public wrapper
  -- is executable by authenticated clients.
  PERFORM set_config('request.jwt.claim.sub',
    CASE WHEN primary_id = responder_one_id THEN '00000000-0000-4000-8000-000000000151'
         WHEN primary_id = responder_two_id THEN '00000000-0000-4000-8000-000000000152'
         ELSE '00000000-0000-4000-8000-000000000153' END, true);
  rejected := false;
  BEGIN
    PERFORM public.api_release_incident_assignment(incident_id, primary_id);
  EXCEPTION WHEN insufficient_privilege THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'A field unit unexpectedly released its own assignment';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.incident_assignments AS assignment_row
    WHERE assignment_row.incident_id = release_test.incident_id AND user_id = primary_id
  ) THEN
    RAISE EXCEPTION 'Denied self-release unexpectedly removed team membership';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', dispatcher_auth_id::text, true);
  response := public.api_release_incident_assignment(incident_id, primary_id);
  SELECT assigned_to INTO assigned_to_after FROM public.incidents WHERE id = incident_id;
  IF assigned_to_after IS NULL OR assigned_to_after = primary_id
     OR NOT EXISTS (
       SELECT 1 FROM public.incident_assignments AS assignment_row
       WHERE assignment_row.incident_id = release_test.incident_id AND user_id = assigned_to_after
     ) THEN
    RAISE EXCEPTION 'Releasing the primary member did not hand off assigned_to to a remaining team member';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.incident_assignments AS assignment_row
    WHERE assignment_row.incident_id = release_test.incident_id AND user_id = primary_id
  ) THEN
    RAISE EXCEPTION 'Released primary member remains in the active team';
  END IF;

  SELECT duty_status INTO duty_after FROM public.profiles WHERE id = primary_id;
  IF duty_after IS DISTINCT FROM 'en_route' THEN
    RAISE EXCEPTION 'A member reserved by another active legacy incident must retain its valid in-progress duty status; got %', duty_after;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.incidents AS legacy_row
    WHERE legacy_row.id = legacy_incident_id
      AND legacy_row.assigned_to = primary_id
      AND legacy_row.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) THEN
    RAISE EXCEPTION 'The unrelated legacy incident must continue reserving the released team member';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.incident_assignment_events AS event_row
    WHERE event_row.incident_id = release_test.incident_id AND user_id = primary_id
      AND event_type = 'released' AND actor_user_id = actor_id
      AND source = 'assignment_delete' AND incident_status = 'assigned'
  ) THEN
    RAISE EXCEPTION 'Release audit event must retain the dispatcher actor and incident state';
  END IF;

  -- Release a non-primary member with no other active reservation. That member
  -- should return to the available pool.
  SELECT user_id INTO secondary_id
  FROM public.incident_assignments AS assignment_row
  WHERE assignment_row.incident_id = release_test.incident_id AND user_id <> assigned_to_after
  ORDER BY user_id
  LIMIT 1;
  IF secondary_id IS NULL THEN
    RAISE EXCEPTION 'Expected a second member after primary handoff';
  END IF;
  PERFORM public.api_release_incident_assignment(incident_id, secondary_id);
  SELECT duty_status INTO duty_after FROM public.profiles WHERE id = secondary_id;
  IF duty_after IS DISTINCT FROM 'available' THEN
    RAISE EXCEPTION 'Released member without another active assignment should become available; got %', duty_after;
  END IF;

  SELECT user_id INTO final_member_id
  FROM public.incident_assignments AS assignment_row WHERE assignment_row.incident_id = release_test.incident_id;
  rejected := false;
  BEGIN
    PERFORM public.api_release_incident_assignment(incident_id, final_member_id);
  EXCEPTION WHEN SQLSTATE '22023' THEN
    rejected := true;
  END;
  IF NOT rejected OR NOT EXISTS (
    SELECT 1 FROM public.incident_assignments AS assignment_row
    WHERE assignment_row.incident_id = release_test.incident_id AND user_id = final_member_id
  ) THEN
    RAISE EXCEPTION 'The final assigned member must not be releasable';
  END IF;

  -- Release is no longer allowed once the shared incident reaches the scene.
  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    arrival_requester_id, 'medical', 'Dispatcher release after-arrival regression',
    11.246, 125.005, now(), 'manual'
  ) RETURNING id INTO arrival_incident_id;
  PERFORM public.api_assign_incident_team(
    arrival_incident_id,
    ARRAY[arrival_responder_id, arrival_driver_id]
  );
  PERFORM public.api_update_incident_status(arrival_incident_id, 'en_route');
  PERFORM public.api_update_incident_status(arrival_incident_id, 'on_scene');

  rejected := false;
  BEGIN
    PERFORM public.api_release_incident_assignment(arrival_incident_id, arrival_responder_id);
  EXCEPTION WHEN SQLSTATE '22023' THEN
    rejected := true;
  END;
  IF NOT rejected OR NOT EXISTS (
    SELECT 1 FROM public.incident_assignments AS assignment_row
    WHERE assignment_row.incident_id = arrival_incident_id AND user_id = arrival_responder_id
  ) THEN
    RAISE EXCEPTION 'A team member must not be releasable after the incident reaches the scene';
  END IF;

  -- Close the first incident and verify the trigger records closure for the
  -- remaining member with the authenticated dispatcher as actor.
  PERFORM public.api_update_incident_status(incident_id, 'en_route');
  PERFORM public.api_update_incident_status(incident_id, 'on_scene');
  PERFORM public.api_update_incident_status(incident_id, 'transporting');
  PERFORM public.api_update_incident_status(incident_id, 'resolved');

  IF NOT EXISTS (
    SELECT 1 FROM public.incident_assignment_events AS event_row
    WHERE event_row.incident_id = release_test.incident_id AND user_id = final_member_id
      AND event_type = 'incident_closed' AND actor_user_id = actor_id
      AND source = 'incident_status' AND incident_status = 'resolved'
  ) THEN
    RAISE EXCEPTION 'Incident closure must append an attributed event for each remaining member';
  END IF;

  -- The client can read audit rows only; all writes remain trigger-owned.
  IF NOT has_table_privilege('authenticated', 'public.incident_assignment_events', 'SELECT')
     OR has_table_privilege('authenticated', 'public.incident_assignment_events', 'INSERT')
     OR has_table_privilege('authenticated', 'public.incident_assignment_events', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.incident_assignment_events', 'DELETE')
     OR has_table_privilege('anon', 'public.incident_assignment_events', 'SELECT') THEN
    RAISE EXCEPTION 'Assignment history table grants must remain authenticated read-only';
  END IF;

  IF NOT has_function_privilege('authenticated', 'public.api_release_incident_assignment(bigint,bigint)', 'EXECUTE')
     OR has_function_privilege('anon', 'public.api_release_incident_assignment(bigint,bigint)', 'EXECUTE')
     OR has_function_privilege('authenticated', 'private.tacresq_release_incident_assignment(bigint,bigint)', 'EXECUTE')
       IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Assignment release RPC grants do not match the authenticated dispatcher boundary';
  END IF;

  SELECT prosecdef, proconfig INTO function_definer, function_settings
  FROM pg_proc
  WHERE oid = to_regprocedure('public.api_release_incident_assignment(bigint,bigint)');
  IF function_definer IS DISTINCT FROM false
     OR NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Public assignment release wrapper must remain SECURITY INVOKER with pinned search_path';
  END IF;

  SELECT count(*) INTO event_count
  FROM public.incident_assignment_events AS event_row
  WHERE event_row.incident_id = release_test.incident_id;
  IF event_count < 5 THEN
    RAISE EXCEPTION 'Expected assignment, release, and closure events; found %', event_count;
  END IF;
END;
$dispatcher_release_runtime_test$;


-- Exercise the REST-facing invoker wrapper as the actual authenticated role.
-- The responder must be denied by role, while a dispatcher reaches the
-- incident lifecycle guard (22023), proving the wrapper and private grant work.
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000151', false);
DO $release_rpc_responder_denied$
DECLARE
  denied boolean := false;
BEGIN
  -- The private implementation rejects the caller's role before looking
  -- up either supplied identifier, so no cross-incident read permission is needed.
  BEGIN
    PERFORM public.api_release_incident_assignment(1, 0);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated responder unexpectedly passed the dispatcher-only release boundary';
  END IF;
END;
$release_rpc_responder_denied$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000124', false);
DO $release_rpc_dispatcher_reaches_guard$
DECLARE
  target_incident_id bigint;
  target_member_id bigint;
  lifecycle_guard_reached boolean := false;
BEGIN
  SELECT id INTO target_incident_id FROM public.incidents
  WHERE description = 'Dispatcher release runtime regression';
  SELECT user_id INTO target_member_id FROM public.incident_assignments AS assignment_row
  WHERE assignment_row.incident_id = target_incident_id;
  BEGIN
    PERFORM public.api_release_incident_assignment(target_incident_id, target_member_id);
  EXCEPTION WHEN SQLSTATE '22023' THEN
    lifecycle_guard_reached := true;
  END;
  IF NOT lifecycle_guard_reached THEN
    RAISE EXCEPTION 'Authenticated dispatcher did not reach the incident lifecycle guard';
  END IF;
END;
$release_rpc_dispatcher_reaches_guard$;

-- RLS must expose audit history to dispatchers/admins but not to requesters.
DO $release_history_dispatcher_read$
DECLARE visible_count integer;
BEGIN
  SELECT count(*) INTO visible_count
  FROM public.incident_assignment_events AS e
  WHERE e.incident_id = current_setting('tacresq.release_test_incident_id')::bigint;
  IF visible_count = 0 THEN
    RAISE EXCEPTION 'Dispatcher should be able to read assignment history under RLS';
  END IF;
END;
$release_history_dispatcher_read$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000150', false);
DO $release_history_requester_hidden$
DECLARE visible_count integer;
BEGIN
  SELECT count(*) INTO visible_count
  FROM public.incident_assignment_events AS e
  WHERE e.incident_id = current_setting('tacresq.release_test_incident_id')::bigint;
  IF visible_count <> 0 THEN
    RAISE EXCEPTION 'Requester must not read dispatcher-only assignment history';
  END IF;
END;
$release_history_requester_hidden$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000158', false);
SELECT set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-4000-8000-000000000158","session_id":"10000000-0000-4000-8000-000000000158","aal":"aal2","amr":[{"method":"password","timestamp":1},{"method":"totp","timestamp":2}]}',
  false
);
DO $release_history_admin_read$
DECLARE visible_count integer;
BEGIN
  SELECT count(*) INTO visible_count
  FROM public.incident_assignment_events AS e
  WHERE e.incident_id = current_setting('tacresq.release_test_incident_id')::bigint;
  IF visible_count = 0 THEN
    RAISE EXCEPTION 'Admin should be able to read assignment history under RLS';
  END IF;
END;
$release_history_admin_read$;
RESET ROLE;

\echo 'PASS: dispatcher release runtime, authenticated RPC access, assignment-history RLS, primary handoff, last-member and after-arrival protections, duty reservation, and attributed audit history.'
