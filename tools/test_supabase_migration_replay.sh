#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:?PGPORT is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${PGDATABASE:?PGDATABASE is required}"

psql -v ON_ERROR_STOP=1 <<'SQL'
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE service_role NOLOGIN BYPASSRLS;
CREATE SCHEMA auth;
CREATE TABLE auth.users (
  id uuid PRIMARY KEY,
  email text,
  phone text,
  raw_user_meta_data jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION auth.uid()
RETURNS uuid
LANGUAGE sql
STABLE
AS $auth_uid$
  SELECT nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$auth_uid$;
SQL

for migration in supabase/migrations/*.sql; do
  echo "Applying ${migration}"
  psql -v ON_ERROR_STOP=1 -f "${migration}"
done

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'incidents'
      AND column_name = 'location_source'
      AND is_nullable = 'NO'
  ) THEN
    RAISE EXCEPTION 'location_source column missing or nullable after replay';
  END IF;

  IF to_regclass('public.fcm_tokens') IS NULL THEN
    RAISE EXCEPTION 'fcm_tokens table missing after replay';
  END IF;

  IF to_regprocedure('public.api_create_incident_v3(text,text,double precision,double precision,real,text,timestamp with time zone,text)') IS NULL THEN
    RAISE EXCEPTION 'api_create_incident_v3 missing after replay';
  END IF;

  IF to_regprocedure('public.api_register_fcm_token(text)') IS NULL
     OR to_regprocedure('public.api_unregister_fcm_token(text)') IS NULL THEN
    RAISE EXCEPTION 'FCM token RPCs missing after replay';
  END IF;

  IF to_regclass('public.incident_assignments') IS NULL THEN
    RAISE EXCEPTION 'incident_assignments table missing after replay';
  END IF;
  IF to_regprocedure('public.api_assign_incident_team(bigint,bigint[])') IS NULL THEN
    RAISE EXCEPTION 'team assignment RPC missing after replay';
  END IF;
END;
$$;


DO $dispatch_test$
DECLARE
  public_rpc_definer boolean;
  private_rpc_definer boolean;
  private_rpc_settings text[];
BEGIN
  SELECT prosecdef INTO public_rpc_definer
    FROM pg_proc WHERE oid = to_regprocedure('public.api_assign_incident_team(bigint,bigint[])');
  SELECT prosecdef, proconfig INTO private_rpc_definer, private_rpc_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_assign_incident_team(bigint,bigint[])');
  IF public_rpc_definer IS DISTINCT FROM false THEN
    RAISE EXCEPTION 'Public team-assignment RPC must be SECURITY INVOKER';
  END IF;
  IF private_rpc_definer IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Private team-assignment implementation must retain SECURITY DEFINER';
  END IF;
  IF NOT (coalesce(private_rpc_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Private team-assignment implementation must pin search_path to pg_catalog';
  END IF;
END;
$dispatch_test$;

-- Regression test for the failure seen in the Android recording:
-- creating an incident must also create its first status log without an FK error.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000123',
  'sos-regression@example.test',
  '{"name":"SOS Regression","role":"requester"}'::jsonb
);

DO $$
DECLARE
  requester_profile_id bigint;
  created_incident_id bigint;
  status_log_count integer;
BEGIN
  SELECT id INTO requester_profile_id
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000123';

  IF requester_profile_id IS NULL THEN
    RAISE EXCEPTION 'Signup trigger did not create the regression requester profile';
  END IF;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_notes, location_recorded_at, location_source
  ) VALUES (
    requester_profile_id, 'medical', 'Automated SOS audit regression',
    11.244, 125.003, 'Synthetic CI location', now(), 'manual'
  )
  RETURNING id INTO created_incident_id;

  SELECT count(*) INTO status_log_count
    FROM public.incident_status_logs
   WHERE incident_id = created_incident_id
     AND status = 'pending'
     AND changed_by = requester_profile_id;

  IF status_log_count <> 1 THEN
    RAISE EXCEPTION 'Expected exactly one initial status log for incident %, found %',
      created_incident_id, status_log_count;
  END IF;
END;
$$;


-- End-to-end SQL regression: team members advance independently, the aggregate
-- stays en_route until all units arrive, and GPS is rejected for an arrived unit.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000124', 'dispatch-regression@example.test', '{"name":"Dispatch Regression"}'),
  ('00000000-0000-4000-8000-000000000127', 'per-responder-requester@example.test', '{"name":"Per Responder Requester"}'),
  ('00000000-0000-4000-8000-000000000125', 'responder-one@example.test', '{"name":"Responder One"}'),
  ('00000000-0000-4000-8000-000000000126', 'responder-two@example.test', '{"name":"Responder Two"}');

DO $per_responder_test$
DECLARE
  requester_id bigint;
  dispatcher_id bigint;
  responder_one_id bigint;
  responder_two_id bigint;
  incident_id bigint;
  response jsonb;
  other_status text;
  aggregate_status text;
BEGIN
  SELECT id INTO requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000127';
  SELECT id INTO dispatcher_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000124';
  SELECT id INTO responder_one_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000125';
  SELECT id INTO responder_two_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000126';

  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL WHERE id = dispatcher_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = responder_one_id;
  UPDATE public.profiles SET role = 'driver', duty_status = 'available' WHERE id = responder_two_id;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude, location_recorded_at, location_source
  )
  VALUES (requester_id, 'medical', 'Synthetic per-responder status test', 11.244, 125.003, now(), 'manual')
  RETURNING id INTO incident_id;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000124', true);
  PERFORM public.api_assign_incident_team(incident_id, ARRAY[responder_one_id, responder_two_id]);

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000125', true);
  response := public.api_update_my_assignment_status(incident_id, 'en_route');
  IF response->>'status' <> 'en_route' THEN
    RAISE EXCEPTION 'Expected incident aggregate to become en_route after first unit departs';
  END IF;

  response := public.api_update_my_assignment_status(incident_id, 'on_scene');
  IF response->>'status' <> 'en_route' THEN
    RAISE EXCEPTION 'Incident must remain en_route while another assigned unit has not arrived';
  END IF;
  SELECT item->>'status' INTO other_status
  FROM jsonb_array_elements(response->'assigned_user_statuses') item
  WHERE (item->>'user_id')::bigint = responder_two_id;
  IF other_status <> 'assigned' THEN
    RAISE EXCEPTION 'One responder arrival must not overwrite another unit status; found %', other_status;
  END IF;

  BEGIN
    PERFORM public.api_record_location_v2(incident_id, 11.244, 125.003, 10, now());
    RAISE EXCEPTION 'Expected GPS update to be rejected after this unit arrived';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000126', true);
  PERFORM public.api_update_incident_status(incident_id, 'en_route');
  response := public.api_update_my_assignment_status(incident_id, 'on_scene');
  IF response->>'status' <> 'on_scene' THEN
    RAISE EXCEPTION 'Incident should move to on_scene once every assigned unit has arrived';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000125', true);
  response := public.api_update_my_assignment_status(incident_id, 'transporting');
  IF response->>'status' <> 'transporting' THEN
    RAISE EXCEPTION 'Incident should reflect transporting once all units arrived and one transports';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000126', true);
  PERFORM public.api_update_my_assignment_status(incident_id, 'transporting');
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000125', true);
  PERFORM public.api_update_my_assignment_status(incident_id, 'resolved');
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000126', true);
  response := public.api_update_my_assignment_status(incident_id, 'resolved');
  IF response->>'status' <> 'resolved' THEN
    RAISE EXCEPTION 'Incident should resolve only after every assigned unit resolves';
  END IF;

  SELECT status INTO aggregate_status FROM public.incidents WHERE id = incident_id;
  IF aggregate_status <> 'resolved' THEN
    RAISE EXCEPTION 'Expected final incident status resolved, found %', aggregate_status;
  END IF;
END;
$per_responder_test$;
SQL

psql -v ON_ERROR_STOP=1 <<'SQL'
-- Notification persistence, event creation, ownership, and RPC grants.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000131', 'notification-dispatcher@example.test', '{"name":"Notification Dispatcher"}'),
  ('00000000-0000-4000-8000-000000000132', 'notification-requester@example.test', '{"name":"Notification Requester"}'),
  ('00000000-0000-4000-8000-000000000133', 'notification-responder@example.test', '{"name":"Notification Responder"}'),
  ('00000000-0000-4000-8000-000000000134', 'notification-legacy-responder@example.test', '{"name":"Notification Legacy Responder"}');

DO $notification_test$
DECLARE
  dispatcher_id bigint;
  requester_id bigint;
  responder_id bigint;
  legacy_responder_id bigint;
  test_incident_id bigint;
  legacy_incident_id bigint;
  requester_notifications jsonb;
  responder_notifications jsonb;
  requester_notification_id bigint;
  responder_notification_id bigint;
  result_boolean boolean;
  notification_count integer;
  denied boolean;
  function_is_definer boolean;
  function_settings text[];
BEGIN
  SELECT prosecdef, proconfig INTO function_is_definer, function_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_notify_assignment_insert()');
  IF function_is_definer IS DISTINCT FROM true
     OR NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Assignment notification trigger must be SECURITY DEFINER with a pinned search_path';
  END IF;
  SELECT prosecdef, proconfig INTO function_is_definer, function_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_notify_status_log_insert()');
  IF function_is_definer IS DISTINCT FROM true
     OR NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Status notification trigger must be SECURITY DEFINER with a pinned search_path';
  END IF;
  SELECT prosecdef, proconfig INTO function_is_definer, function_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_notify_legacy_assignment_update()');
  IF function_is_definer IS DISTINCT FROM true
     OR NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Legacy assignment notification trigger must be SECURITY DEFINER with a pinned search_path';
  END IF;
  SELECT id INTO dispatcher_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000131';
  SELECT id INTO requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000132';
  SELECT id INTO responder_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000133';
  SELECT id INTO legacy_responder_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000134';

  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL WHERE id = dispatcher_id;
  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = requester_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = responder_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = legacy_responder_id;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    requester_id, 'medical', 'Synthetic notification test incident',
    11.244, 125.003, now(), 'manual'
  ) RETURNING id INTO test_incident_id;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000131', true);
  PERFORM public.api_assign_incident_team(test_incident_id, ARRAY[responder_id]);

  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = test_incident_id
     AND n.event_type = 'incident_assigned';
  IF notification_count <> 2 THEN
    RAISE EXCEPTION 'Assignment must create one notification for the requester and one for the assigned unit; found %', notification_count;
  END IF;

  IF has_table_privilege('anon', 'public.user_notifications', 'SELECT')
     OR has_table_privilege('authenticated', 'public.user_notifications', 'SELECT')
     OR has_table_privilege('authenticated', 'public.user_notifications', 'INSERT')
     OR has_table_privilege('authenticated', 'public.user_notifications', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.user_notifications', 'DELETE') THEN
    RAISE EXCEPTION 'Notification table must not be directly accessible to API roles';
  END IF;
  IF has_function_privilege('anon', 'public.api_my_notifications(integer)', 'EXECUTE')
     OR has_function_privilege('anon', 'public.api_mark_notification_read(bigint)', 'EXECUTE')
     OR NOT has_function_privilege('authenticated', 'public.api_my_notifications(integer)', 'EXECUTE')
     OR NOT has_function_privilege('authenticated', 'public.api_mark_notification_read(bigint)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Notification RPC execute grants are incorrect';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000132', true);
  requester_notifications := public.api_my_notifications(10);
  IF jsonb_array_length(requester_notifications) <> 1
     OR requester_notifications->0->>'event_type' <> 'incident_assigned'
     OR requester_notifications->0->>'title' <> 'Response unit assigned' THEN
    RAISE EXCEPTION 'Requester must receive only their own assignment notification: %', requester_notifications;
  END IF;
  requester_notification_id := (requester_notifications->0->>'id')::bigint;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000133', true);
  responder_notifications := public.api_my_notifications(10);
  IF jsonb_array_length(responder_notifications) <> 1
     OR responder_notifications->0->>'title' <> 'New incident assignment' THEN
    RAISE EXCEPTION 'Assigned unit must receive only its own assignment notification: %', responder_notifications;
  END IF;
  responder_notification_id := (responder_notifications->0->>'id')::bigint;

  -- A requester cannot mark a responder's notification read or learn whether it exists.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000132', true);
  result_boolean := public.api_mark_notification_read(responder_notification_id);
  IF result_boolean THEN
    RAISE EXCEPTION 'A user must not mark another recipient''s notification as read';
  END IF;
  result_boolean := public.api_mark_notification_read(requester_notification_id);
  IF NOT result_boolean THEN
    RAISE EXCEPTION 'A recipient should be able to mark their own notification read';
  END IF;
  result_boolean := public.api_mark_notification_read(requester_notification_id);
  IF NOT result_boolean THEN
    RAISE EXCEPTION 'Marking an already-read notification should be safely idempotent';
  END IF;

  -- A field-unit status transition should notify the requester, not expose other users' rows.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000133', true);
  PERFORM public.api_update_my_assignment_status(test_incident_id, 'en_route');

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000132', true);
  requester_notifications := public.api_my_notifications(10);
  SELECT count(*) INTO notification_count
    FROM jsonb_array_elements(requester_notifications) AS notifications(item)
   WHERE item->>'event_type' = 'incident_status_changed'
     AND item->>'incident_id' = test_incident_id::text;
  IF notification_count < 1 THEN
    RAISE EXCEPTION 'Requester must be notified when an assigned unit changes incident status: %', requester_notifications;
  END IF;

  -- The web dispatcher still uses the legacy single-unit api_assign_incident RPC.
  -- Its assignment must also produce exactly one alert per recipient.
  UPDATE public.profiles SET duty_status = 'available' WHERE id = responder_id;
  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    requester_id, 'medical', 'Synthetic legacy assignment notification test',
    11.245, 125.004, now(), 'manual'
  ) RETURNING id INTO legacy_incident_id;
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000131', true);
  PERFORM public.api_assign_incident(legacy_incident_id, legacy_responder_id);
  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = legacy_incident_id
     AND n.event_type = 'incident_assigned';
  IF notification_count <> 2 THEN
    RAISE EXCEPTION 'Legacy dashboard assignment must create exactly two recipient alerts, found %', notification_count;
  END IF;

  denied := false;
  PERFORM set_config('request.jwt.claim.sub', '', true);
  BEGIN
    PERFORM public.api_my_notifications(10);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Notification list must reject unauthenticated callers';
  END IF;

  denied := false;
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000132', true);
  BEGIN
    PERFORM public.api_my_notifications(101);
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Notification list must reject limits above 100';
  END IF;
END;
$notification_test$;
SQL

echo "PASS: all $(find supabase/migrations -maxdepth 1 -name '*.sql' | wc -l) Supabase migrations replayed, and incident, notification authorization, and per-responder status regressions passed."
