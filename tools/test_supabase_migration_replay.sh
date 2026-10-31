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
  '{"name":"SOS Regression","phone":"+63917000001","role":"requester"}'::jsonb
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

-- Location fallback must remain enforced when a requester edits an existing incident.
DO $incident_location_update_test$
DECLARE
  requester_profile_id bigint;
  incident_id bigint;
BEGIN
  SELECT id INTO requester_profile_id
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000123';

  -- Reuse the fixture incident created above; the one-active-request
  -- invariant correctly prevents creating a second active request here.
  SELECT id INTO incident_id
    FROM public.incidents
   WHERE requester_id = requester_profile_id
     AND description = 'Automated SOS audit regression';

  IF incident_id IS NULL THEN
    RAISE EXCEPTION 'Expected the existing requester fixture incident';
  END IF;

  -- Convert the fixture to a valid landmark-only location before testing
  -- attempts to remove its final usable location detail.
  UPDATE public.incidents
     SET latitude = NULL,
         longitude = NULL,
         accuracy_m = NULL,
         location_recorded_at = NULL,
         location_notes = 'Beside the public market entrance',
         location_source = 'none'
   WHERE id = incident_id;

  BEGIN
    UPDATE public.incidents
       SET location_notes = NULL
     WHERE id = incident_id;
    RAISE EXCEPTION 'Expected an incident update without pin or landmark to be rejected';
  EXCEPTION WHEN SQLSTATE '22023' THEN
    NULL;
  END;

  BEGIN
    UPDATE public.incidents
       SET longitude = 125.003
     WHERE id = incident_id;
    RAISE EXCEPTION 'Expected a partial coordinate update to be rejected';
  EXCEPTION WHEN SQLSTATE '22023' THEN
    NULL;
  END;

  UPDATE public.incidents
     SET location_notes = 'Beside the verified public market entrance'
   WHERE id = incident_id;
END;
$incident_location_update_test$;


-- End-to-end SQL regression: team members advance independently, the aggregate
-- stays en_route until all units arrive, and GPS is rejected for an arrived unit.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000124', 'dispatch-regression@example.test', '{"name":"Dispatch Regression","phone":"+63917000002"}'),
  ('00000000-0000-4000-8000-000000000127', 'per-responder-requester@example.test', '{"name":"Per Responder Requester","phone":"+63917000003"}'),
  ('00000000-0000-4000-8000-000000000125', 'responder-one@example.test', '{"name":"Responder One","phone":"+63917000004"}'),
  ('00000000-0000-4000-8000-000000000126', 'responder-two@example.test', '{"name":"Responder Two","phone":"+63917000005"}');

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
  ('00000000-0000-4000-8000-000000000131', 'notification-dispatcher@example.test', '{"name":"Notification Dispatcher","phone":"+63917000006"}'),
  ('00000000-0000-4000-8000-000000000132', 'notification-requester@example.test', '{"name":"Notification Requester","phone":"+63917000007"}'),
  ('00000000-0000-4000-8000-000000000133', 'notification-responder@example.test', '{"name":"Notification Responder","phone":"+63917000008"}'),
  ('00000000-0000-4000-8000-000000000134', 'notification-legacy-responder@example.test', '{"name":"Notification Legacy Responder","phone":"+63917000009"}'),
  ('00000000-0000-4000-8000-000000000135', 'notification-legacy-requester@example.test', '{"name":"Notification Legacy Requester","phone":"+63917000010"}');

DO $notification_test$
DECLARE
  dispatcher_id bigint;
  requester_id bigint;
  responder_id bigint;
  legacy_responder_id bigint;
  legacy_requester_id bigint;
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
  SELECT id INTO legacy_requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000135';

  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL WHERE id = dispatcher_id;
  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = requester_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = responder_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = legacy_responder_id;
  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = legacy_requester_id;

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
  -- Its assignment must also produce exactly one alert per recipient. Use a
  -- separate available unit because the first responder is still on an active incident.
  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    legacy_requester_id, 'medical', 'Synthetic legacy assignment notification test',
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

  denied := false;  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000132', true);
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

psql -v ON_ERROR_STOP=1 <<'SQL'
-- Incident chat authorization, idempotency, rate/size bounds, and message alerts.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000136', 'chat-dispatcher@example.test', '{"name":"Chat Dispatcher","phone":"+63917000011"}'),
  ('00000000-0000-4000-8000-000000000137', 'chat-requester@example.test', '{"name":"Chat Requester","phone":"+63917000012"}'),
  ('00000000-0000-4000-8000-000000000138', 'chat-responder@example.test', '{"name":"Chat Responder","phone":"+63917000013"}'),
  ('00000000-0000-4000-8000-000000000139', 'chat-outsider@example.test', '{"name":"Chat Outsider","phone":"+63917000014"}');

DO $chat_test$
DECLARE
  dispatcher_id bigint;
  requester_id bigint;
  responder_id bigint;
  outsider_id bigint;
  test_incident_id bigint;
  send_result jsonb;
  retry_result jsonb;
  message_list jsonb;
  first_message_id bigint;
  reply_message_id bigint;
  denied boolean;
  notification_count integer;
  i integer;
BEGIN
  SELECT id INTO dispatcher_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000136';
  SELECT id INTO requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000137';
  SELECT id INTO responder_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000138';
  SELECT id INTO outsider_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000139';

  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL WHERE id = dispatcher_id;
  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = requester_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = responder_id;
  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = outsider_id;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    requester_id, 'medical', 'Synthetic chat access test incident',
    11.246, 125.005, now(), 'manual'
  ) RETURNING id INTO test_incident_id;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000136', true);
  PERFORM public.api_assign_incident_team(test_incident_id, ARRAY[responder_id]);

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000137', true);
  send_result := public.api_send_incident_message(
    test_incident_id, 'Need help at the front entrance.',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0136'
  );
  first_message_id := (send_result->>'id')::bigint;
  retry_result := public.api_send_incident_message(
    test_incident_id, 'Need help at the front entrance.',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0136'
  );
  IF (retry_result->>'id')::bigint <> first_message_id THEN
    RAISE EXCEPTION 'Retry with the same client message ID must return the original message';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id, 'Different content with reused key.',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0136'
    );
  EXCEPTION WHEN unique_violation THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Reusing a client message ID for different content must be rejected';
  END IF;

  message_list := public.api_my_incident_messages(test_incident_id, 50, NULL);
  IF jsonb_array_length(message_list) <> 1
     OR message_list->0->>'message' <> 'Need help at the front entrance.'
     OR (message_list->0->>'sender_id')::bigint <> requester_id THEN
    RAISE EXCEPTION 'Requester must see their own incident message: %', message_list;
  END IF;

  -- Only the assigned responder is notified about the requester message; the
  -- sender must not receive a notification for their own message.
  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = test_incident_id
     AND n.event_type = 'incident_message'
     AND n.recipient_user_id = responder_id;
  IF notification_count <> 1 THEN
    RAISE EXCEPTION 'Assigned responder must receive one generic message notification; found %', notification_count;
  END IF;
  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = test_incident_id
     AND n.event_type = 'incident_message'
     AND n.recipient_user_id = requester_id;
  IF notification_count <> 0 THEN
    RAISE EXCEPTION 'A sender must not be notified about their own message';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000138', true);
  message_list := public.api_my_incident_messages(test_incident_id, 50, NULL);
  IF jsonb_array_length(message_list) <> 1
     OR (message_list->0->>'id')::bigint <> first_message_id THEN
    RAISE EXCEPTION 'Assigned responder must be able to read the incident conversation';
  END IF;

  send_result := public.api_send_incident_message(
    test_incident_id, 'Responder is on the way.',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbb0138'
  );
  reply_message_id := (send_result->>'id')::bigint;
  message_list := public.api_my_incident_messages(test_incident_id, 1, reply_message_id);
  IF jsonb_array_length(message_list) <> 1
     OR (message_list->0->>'id')::bigint <> first_message_id THEN
    RAISE EXCEPTION 'Before-ID pagination must return the older message';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000139', true);
  denied := false;
  BEGIN
    PERFORM public.api_my_incident_messages(test_incident_id, 50, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Unrelated account must not read incident chat';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id, 'Unauthorized message.',
      'cccccccc-cccc-4ccc-8ccc-cccccccc0139'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Unrelated account must not send incident chat messages';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000136', true);
  message_list := public.api_my_incident_messages(test_incident_id, 50, NULL);
  IF jsonb_array_length(message_list) <> 2 THEN
    RAISE EXCEPTION 'Dispatcher should be able to read incident chat';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_my_incident_messages(test_incident_id, 101, NULL);
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Chat page size above 100 must be rejected';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id, '   ',
      'dddddddd-dddd-4ddd-8ddd-dddddddd0136'
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Blank chat messages must be rejected';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id, repeat('x', 2001),
      'eeeeeeee-eeee-4eee-8eee-eeeeeeee0136'
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Chat messages longer than 2000 characters must be rejected';
  END IF;

  IF has_table_privilege('anon', 'public.incident_messages', 'SELECT')
     OR has_table_privilege('authenticated', 'public.incident_messages', 'SELECT')
     OR has_table_privilege('authenticated', 'public.incident_messages', 'INSERT')
     OR has_table_privilege('authenticated', 'public.incident_messages', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.incident_messages', 'DELETE') THEN
    RAISE EXCEPTION 'Chat table must not be directly accessible to API roles';
  END IF;
  IF has_function_privilege('anon', 'public.api_my_incident_messages(bigint,integer,bigint)', 'EXECUTE')
     OR has_function_privilege('anon', 'public.api_send_incident_message(bigint,text,uuid)', 'EXECUTE')
     OR NOT has_function_privilege('authenticated', 'public.api_my_incident_messages(bigint,integer,bigint)', 'EXECUTE')
     OR NOT has_function_privilege('authenticated', 'public.api_send_incident_message(bigint,text,uuid)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Incident chat RPC grants are incorrect';
  END IF;

  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = test_incident_id
     AND n.event_type = 'incident_message'
     AND n.recipient_user_id = requester_id;
  IF notification_count <> 1 THEN
    RAISE EXCEPTION 'Requester must receive one message notification after responder reply';
  END IF;

  -- Per-sender flood guard: twenty sends in a minute are allowed; the next is rejected.
  FOR i IN 1..20 LOOP
    PERFORM public.api_send_incident_message(
      test_incident_id,
      'Rate limit probe ' || i,
      ('f0000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid
    );
  END LOOP;
  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id,
      'Rate limit probe 21',
      'f0000000-0000-4000-8000-000000000021'
    );
  EXCEPTION WHEN program_limit_exceeded THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'The 21st message within one minute must be rate limited';
  END IF;
END;
$chat_test$;
SQL

echo "PASS: migration replay and baseline incident/chat/notification/admin regressions passed; continuing with SOS idempotency stress tests."


psql -v ON_ERROR_STOP=1 <<'SQL'
-- Admin role/audit regression tests run only in this disposable clean database.
-- Public signup metadata must never grant the privileged role.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000128', 'admin-spoof@example.test', '{"name":"Admin Spoof Attempt","phone":"+63917000015","role":"admin"}'),
  ('00000000-0000-4000-8000-000000000129', 'admin-role-target@example.test', '{"name":"Admin Role Target","phone":"+63917000016","role":"requester"}'),
  ('00000000-0000-4000-8000-000000000130', 'admin-escalation-attempt@example.test', '{"name":"Admin Escalation Attempt","phone":"+63917000017","role":"requester"}');

DO $admin_provision_fixture$
DECLARE
  admin_profile_id bigint;
  signup_role text;
BEGIN
  SELECT id, role INTO admin_profile_id, signup_role
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000128';

  IF admin_profile_id IS NULL OR signup_role <> 'requester' THEN
    RAISE EXCEPTION 'Public signup metadata must be forced to requester, including forged role=admin';
  END IF;

  -- Simulate the trusted operator's out-of-band first-admin provisioning.
  UPDATE public.profiles
  SET role = 'admin', duty_status = NULL
  WHERE id = admin_profile_id;

  IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = admin_profile_id AND role = 'admin') THEN
    RAISE EXCEPTION 'Trusted operator fixture could not provision the test administrator';
  END IF;
END;
$admin_provision_fixture$;

DO $admin_authorization_test$
DECLARE
  admin_profile_id bigint;
  target_profile_id bigint;
  attacker_profile_id bigint;
  changed jsonb;
  role_after_attempt text;
  audit_count integer;
  denied boolean;
BEGIN
  SELECT id INTO admin_profile_id FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000128';
  SELECT id INTO target_profile_id FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000129';
  SELECT id INTO attacker_profile_id FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000130';

  IF has_function_privilege('anon', 'public.api_admin_list_users()', 'EXECUTE') THEN
    RAISE EXCEPTION 'Anonymous role must not execute the Admin user-list RPC';
  END IF;
  IF NOT has_function_privilege('authenticated', 'public.api_admin_list_users()', 'EXECUTE') THEN
    RAISE EXCEPTION 'Authenticated role must have the explicitly granted Admin RPC entry point';
  END IF;
  IF has_function_privilege('anon', 'public.api_admin_list_users_page(integer,integer,text,text)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Anonymous role must not execute the Admin paginated user-list RPC';
  END IF;
  IF NOT has_function_privilege('authenticated', 'public.api_admin_list_users_page(integer,integer,text,text)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Authenticated role must have the explicitly granted Admin pagination RPC entry point';
  END IF;
  IF has_table_privilege('authenticated', 'public.admin_audit_logs', 'INSERT')
     OR has_table_privilege('authenticated', 'public.admin_audit_logs', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.admin_audit_logs', 'DELETE') THEN
    RAISE EXCEPTION 'Authenticated clients must not directly mutate Admin audit rows';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000128', true);
  changed := public.api_admin_list_users();
  IF jsonb_typeof(changed) <> 'array' OR jsonb_array_length(changed) < 3 THEN
    RAISE EXCEPTION 'Admin user-list RPC should return profile data for an authorized administrator';
  END IF;
  -- Search must be case-insensitive and literal (wildcard characters are not SQL patterns).
  changed := public.api_admin_list_users_page(25, 0, 'ADMIN-ROLE-TARGET@example.test', 'requester');
  IF changed->>'total_count' <> '1'
     OR jsonb_array_length(changed->'users') <> 1
     OR changed->'users'->0->>'email' <> 'admin-role-target@example.test' THEN
    RAISE EXCEPTION 'Admin pagination search/filter returned an unexpected result: %', changed;
  END IF;

  changed := public.api_admin_list_users_page(25, 0, '%', 'requester');
  IF changed->>'total_count' <> '0' OR jsonb_array_length(changed->'users') <> 0 THEN
    RAISE EXCEPTION 'Percent in Admin search must be treated literally, not as a wildcard: %', changed;
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_list_users_page(101, 0, '', 'all');
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Admin pagination must reject page sizes above 100';
  END IF;


  changed := public.api_admin_set_user_role(
    target_profile_id,
    'dispatcher',
    'Approved role assignment for emergency dispatch coordination.'
  );
  IF changed->>'role' <> 'dispatcher' OR changed->>'old_role' <> 'requester' THEN
    RAISE EXCEPTION 'Authorized Admin role change returned an unexpected result: %', changed;
  END IF;

  SELECT count(*) INTO audit_count
  FROM public.admin_audit_logs
  WHERE actor_user_id = admin_profile_id
    AND target_user_id = target_profile_id
    AND action = 'user_role_changed'
    AND old_role = 'requester'
    AND new_role = 'dispatcher'
    AND reason = 'Approved role assignment for emergency dispatch coordination.';
  IF audit_count <> 1 THEN
    RAISE EXCEPTION 'Successful role change must write exactly one matching audit event, found %', audit_count;
  END IF;

  -- Even an Admin cannot promote, demote, or edit an Admin account through this RPC.
  denied := false;
  BEGIN
    PERFORM public.api_admin_set_user_role(
      admin_profile_id,
      'requester',
      'Attempt to demote an administrator account.'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Admin role-change RPC must reject changes to an Admin account';
  END IF;

  -- Ordinary authenticated profiles must not list users or change another role.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000130', true);
  denied := false;
  BEGIN
    PERFORM public.api_admin_list_users();
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not read the Admin user list';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_set_user_role(
      target_profile_id,
      'responder',
      'Unauthorized role escalation attempt by a requester.'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not change another profile role';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_list_audit_logs(100);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not read Admin audit events';
  END IF;

  -- Direct profile role mutation is rejected by the existing database guard.
  denied := false;
  BEGIN
    UPDATE public.profiles SET role = 'admin'
    WHERE id = attacker_profile_id;
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not self-promote through a direct profile update';
  END IF;
  SELECT role INTO role_after_attempt FROM public.profiles WHERE id = attacker_profile_id;
  IF role_after_attempt <> 'requester' THEN
    RAISE EXCEPTION 'Failed self-promotion attempt changed the profile role to %', role_after_attempt;
  END IF;

  -- A missing auth.uid() is treated as unauthenticated, not as an Admin.
  PERFORM set_config('request.jwt.claim.sub', '', true);
  denied := false;
  BEGIN
    PERFORM public.api_admin_list_audit_logs(100);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Unauthenticated request must not read Admin audit events';
  END IF;
END;
$admin_authorization_test$;

-- Every ordinary operational role must be denied by the database authorization
-- boundary, even though each role can execute the authenticated RPC entrypoint.
DO $admin_role_matrix_test$
DECLARE
  principal record;
  denied boolean;
  target_profile_id bigint;
BEGIN
  SELECT id INTO target_profile_id
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000129';

  FOR principal IN
    SELECT * FROM (VALUES
      ('00000000-0000-4000-8000-000000000124'::uuid, 'dispatcher'::text),
      ('00000000-0000-4000-8000-000000000125'::uuid, 'responder'::text),
      ('00000000-0000-4000-8000-000000000126'::uuid, 'driver'::text),
      ('00000000-0000-4000-8000-000000000130'::uuid, 'requester'::text)
    ) AS ordinary_principals(auth_id, role_name)
  LOOP
    PERFORM set_config('request.jwt.claim.sub', principal.auth_id::text, true);

    denied := false;
    BEGIN
      PERFORM public.api_admin_list_users();
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly read Admin user list', principal.role_name;
    END IF;

    denied := false;
    BEGIN
      PERFORM public.api_admin_list_users_page(25, 0, '', 'all');
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly used Admin pagination RPC', principal.role_name;
    END IF;

    denied := false;
    BEGIN
      PERFORM public.api_admin_set_user_role(
        target_profile_id,
        'responder',
        'Unauthorized role change regression test.'
      );
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly changed an account role', principal.role_name;
    END IF;

    denied := false;
    BEGIN
      PERFORM public.api_admin_list_audit_logs(100);
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly read Admin audit logs', principal.role_name;
    END IF;

    -- A user cannot self-promote through the profile-update route regardless
    -- of whether the account is a requester, responder, driver, or dispatcher.
    denied := false;
    BEGIN
      UPDATE public.profiles
      SET role = 'admin', duty_status = NULL
      WHERE auth_user_id = principal.auth_id;
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly self-promoted via profile update', principal.role_name;
    END IF;

    IF EXISTS (
      SELECT 1 FROM public.profiles
      WHERE auth_user_id = principal.auth_id AND role = 'admin'
    ) THEN
      RAISE EXCEPTION 'Denied self-promotion changed ordinary role % to admin', principal.role_name;
    END IF;
  END LOOP;
END;
$admin_role_matrix_test$;

-- Audit history must keep immutable actor/target identity values after profile deletion.
-- This protects historical attribution from ON DELETE SET NULL side effects.
DO $admin_audit_immutability_test$
DECLARE
  audit_event_id bigint;
  audit_target_id bigint;
  audit_actor_id bigint;
  audit_actor_identity text;
BEGIN
  SELECT id, target_user_id, actor_user_id, actor_identity
  INTO audit_event_id, audit_target_id, audit_actor_id, audit_actor_identity
  FROM public.admin_audit_logs
  WHERE target_user_id = (
    SELECT id FROM public.profiles
    WHERE auth_user_id = '00000000-0000-4000-8000-000000000129'
  )
  ORDER BY id DESC
  LIMIT 1;

  IF audit_event_id IS NULL OR audit_target_id IS NULL OR audit_actor_id IS NULL OR audit_actor_identity IS NULL THEN
    RAISE EXCEPTION 'Expected a complete application-admin audit event before testing identity retention';
  END IF;

  INSERT INTO public.admin_audit_logs (
    actor_user_id, actor_identity, target_user_id, action, old_role, new_role, reason
  ) VALUES (
    NULL, 'CI trusted operator', audit_target_id, 'user_role_changed', 'requester', 'admin',
    'Disposable migration test operator provisioning event'
  );
  IF NOT EXISTS (
    SELECT 1 FROM public.admin_audit_logs
    WHERE target_user_id = audit_target_id
      AND actor_user_id IS NULL
      AND actor_identity = 'CI trusted operator'
  ) THEN
    RAISE EXCEPTION 'Trusted-operator audit events must support an explicit identity without a profile actor ID';
  END IF;

  DELETE FROM public.profiles
  WHERE auth_user_id IN (
    '00000000-0000-4000-8000-000000000129',
    '00000000-0000-4000-8000-000000000128'
  );

  IF NOT EXISTS (
    SELECT 1 FROM public.admin_audit_logs
    WHERE id = audit_event_id
      AND target_user_id = audit_target_id
      AND actor_user_id = audit_actor_id
  ) THEN
    RAISE EXCEPTION 'Deleting profiles must not mutate or erase historical Admin audit identity snapshots';
  END IF;
END;
$admin_audit_immutability_test$;
SQL


# Concurrent SOS idempotency regression. Each psql process is an independent
# database session, exercising unique-key arbitration across sessions.
psql -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000140',
  'concurrent-sos-regression@example.test',
  '{"name":"Concurrent SOS Regression","phone":"+63917000136","role":"requester"}'::jsonb
);
SQL

concurrent_dir="$(mktemp -d)"
worker_pids=()
cleanup_concurrent_dir() { rm -rf "$concurrent_dir"; }
trap cleanup_concurrent_dir EXIT

for worker in $(seq 1 8); do
  (
    psql -X -qAt -v ON_ERROR_STOP=1 >"$concurrent_dir/response-${worker}.json" <<'SQL'
BEGIN;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000140', true);
SELECT public.api_create_incident_v4(
  'medical',
  'Concurrent idempotency regression',
  11.244,
  125.003,
  NULL,
  'Synthetic concurrent CI location',
  '2026-10-30T12:00:00Z'::timestamptz,
  'manual',
  '00000000-0000-4000-8000-000000000140'::uuid
)::text;
COMMIT;
SQL
  ) &
  worker_pids+=("$!")
done
for worker_pid in "${worker_pids[@]}"; do
  wait "$worker_pid"
done

python3 - "$concurrent_dir" <<'PY'
from pathlib import Path
import sys

directory = Path(sys.argv[1])
responses = [
    line.strip()
    for path in sorted(directory.glob("response-*.json"))
    for line in path.read_text().splitlines()
    if line.strip().startswith("{")
]
if len(responses) != 8:
    raise SystemExit(f"Expected 8 successful concurrent RPC responses, found {len(responses)}")
if len(set(responses)) != 1:
    raise SystemExit("Concurrent SOS retries returned different incident responses")
print("PASS: all 8 concurrent SOS submissions returned the same saved response")
PY

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $concurrent_sos_assert$
DECLARE
  requester_profile_id bigint;
  incident_count integer;
  saved_key_count integer;
  response_count integer;
BEGIN
  SELECT id INTO requester_profile_id
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000140';

  SELECT count(*) INTO incident_count
  FROM public.incidents
  WHERE requester_id = requester_profile_id
    AND description = 'Concurrent idempotency regression';

  SELECT count(*), count(response_payload)
    INTO saved_key_count, response_count
  FROM public.incident_submission_keys
  WHERE requester_id = requester_profile_id
    AND client_request_id = '00000000-0000-4000-8000-000000000140';

  IF incident_count <> 1 THEN
    RAISE EXCEPTION 'Eight concurrent retries must create exactly one incident; found %', incident_count;
  END IF;
  IF saved_key_count <> 1 OR response_count <> 1 THEN
    RAISE EXCEPTION 'Expected one completed idempotency record; keys %, responses %',
      saved_key_count, response_count;
  END IF;
END;
$concurrent_sos_assert$;
SQL


# Reusing a key with changed details must be rejected, and an unauthenticated
# caller must not be able to reserve or retrieve another request's idempotency key.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $sos_idempotency_negative_test$
DECLARE
  rejected boolean := false;
BEGIN
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000140', true);
  BEGIN
    PERFORM public.api_create_incident_v4(
      'medical',
      'Changed payload must not reuse key',
      11.244,
      125.003,
      NULL,
      'Synthetic concurrent CI location',
      '2026-10-30T12:00:00Z'::timestamptz,
      'manual',
      '00000000-0000-4000-8000-000000000140'::uuid
    );
  EXCEPTION WHEN SQLSTATE '22023' THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'Reusing an SOS key with changed details must be rejected';
  END IF;

  rejected := false;
  PERFORM set_config('request.jwt.claim.sub', '', true);
  BEGIN
    PERFORM public.api_create_incident_v4(
      'medical', 'Unauthenticated SOS attempt', NULL, NULL, NULL, NULL,
      NULL, 'none', '00000000-0000-4000-8000-000000000137'::uuid
    );
  EXCEPTION WHEN insufficient_privilege THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'Unauthenticated callers must not create SOS submissions';
  END IF;
END;
$sos_idempotency_negative_test$;
SQL


# Failure-atomicity regression: force the underlying incident insert to fail
# after the idempotency key has been reserved. The failed RPC must roll back
# that reservation so the same request ID can be retried successfully.
psql -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000141',
  'sos-atomicity-regression-141@example.test',
  '{"name":"SOS Atomicity Regression","phone":"+63917000141","role":"requester"}'::jsonb
);

CREATE OR REPLACE FUNCTION public.tacresq_test_fail_sos_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $test$
BEGIN
  IF NEW.description = 'Atomicity failure injection' THEN
    RAISE EXCEPTION 'Intentional CI failure after idempotency reservation'
      USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$test$;

CREATE TRIGGER tacresq_test_fail_sos_insert
BEFORE INSERT ON public.incidents
FOR EACH ROW EXECUTE FUNCTION public.tacresq_test_fail_sos_insert();
SQL

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $sos_atomicity_failure$
DECLARE
  requester_profile_id bigint;
  failure_observed boolean := false;
BEGIN
  SELECT id INTO requester_profile_id FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000141';

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000141', true);
  BEGIN
    PERFORM public.api_create_incident_v4(
      'medical', 'Atomicity failure injection', 11.244, 125.003, NULL,
      'Synthetic CI failure location', '2026-10-30T12:00:00Z'::timestamptz,
      'manual', '00000000-0000-4000-8000-000000000141'::uuid
    );
  EXCEPTION WHEN SQLSTATE 'P0001' THEN
    failure_observed := true;
  END;

  IF NOT failure_observed THEN
    RAISE EXCEPTION 'Expected the injected incident-creation failure';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.incident_submission_keys
    WHERE requester_id = requester_profile_id
      AND client_request_id = '00000000-0000-4000-8000-000000000141'::uuid
  ) THEN
    RAISE EXCEPTION 'Failed SOS transaction left behind an idempotency reservation';
  END IF;
END;
$sos_atomicity_failure$;

DROP TRIGGER tacresq_test_fail_sos_insert ON public.incidents;
DROP FUNCTION public.tacresq_test_fail_sos_insert();

DO $sos_atomicity_retry$
DECLARE
  response jsonb;
  requester_profile_id bigint;
BEGIN
  SELECT id INTO requester_profile_id
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000141';

  IF requester_profile_id IS NULL THEN
    RAISE EXCEPTION 'Atomicity retry fixture profile was not created';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000141', true);
  SELECT public.api_create_incident_v4(
    'medical', 'Atomicity failure injection', 11.244, 125.003, NULL,
    'Synthetic CI failure location', '2026-10-30T12:00:00Z'::timestamptz,
    'manual', '00000000-0000-4000-8000-000000000141'::uuid
  ) INTO response;

  IF response IS NULL THEN
    RAISE EXCEPTION 'Retry after rolled-back SOS reservation must return the saved incident response';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.incident_submission_keys
    WHERE requester_id = requester_profile_id
      AND client_request_id = '00000000-0000-4000-8000-000000000141'::uuid
      AND response_payload = response
  ) THEN
    RAISE EXCEPTION 'Successful retry did not persist its idempotency response';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.incidents
    WHERE requester_id = requester_profile_id
      AND description = 'Atomicity failure injection'
  ) THEN
    RAISE EXCEPTION 'Successful retry did not create the expected incident';
  END IF;
END;
$sos_atomicity_retry$;
SQL

# Verify the isolated GPS RPC privilege boundary after all migrations replay.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $location_rpc_boundary_test$
DECLARE
  public_writer regprocedure := to_regprocedure(
    'public.api_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)'
  );
  private_writer regprocedure := to_regprocedure(
    'private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)'
  );
  public_reader regprocedure := to_regprocedure('public.api_active_responder_locations()');
  private_reader regprocedure := to_regprocedure('private.tacresq_active_responder_locations()');
  function_settings text[];
BEGIN
  IF public_writer IS NULL OR private_writer IS NULL OR public_reader IS NULL OR private_reader IS NULL THEN
    RAISE EXCEPTION 'Expected public GPS wrappers and private implementations to exist';
  END IF;
  IF (SELECT prosecdef FROM pg_proc WHERE oid = public_writer) IS DISTINCT FROM false
     OR (SELECT prosecdef FROM pg_proc WHERE oid = private_writer) IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'GPS write boundary must be public SECURITY INVOKER and private SECURITY DEFINER';
  END IF;
  IF (SELECT prosecdef FROM pg_proc WHERE oid = public_reader) IS DISTINCT FROM false
     OR (SELECT prosecdef FROM pg_proc WHERE oid = private_reader) IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'GPS read boundary must be public SECURITY INVOKER and private SECURITY DEFINER';
  END IF;
  SELECT proconfig INTO function_settings FROM pg_proc WHERE oid = private_writer;
  IF NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Private GPS writer must pin search_path to pg_catalog';
  END IF;
  SELECT proconfig INTO function_settings FROM pg_proc WHERE oid = private_reader;
  IF NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Private GPS reader must pin search_path to pg_catalog';
  END IF;
  IF NOT has_schema_privilege('authenticated', 'private', 'USAGE')
     OR NOT has_function_privilege('authenticated', public_writer, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', private_writer, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', public_reader, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', private_reader, 'EXECUTE') THEN
    RAISE EXCEPTION 'Authenticated role is missing the required private GPS RPC privileges';
  END IF;
  IF has_function_privilege('anon', public_writer, 'EXECUTE')
     OR has_function_privilege('anon', private_writer, 'EXECUTE')
     OR has_function_privilege('anon', public_reader, 'EXECUTE')
     OR has_function_privilege('anon', private_reader, 'EXECUTE') THEN
    RAISE EXCEPTION 'Anonymous role must not execute GPS RPCs';
  END IF;
  IF has_table_privilege('authenticated', 'public.location_logs', 'INSERT')
     OR has_table_privilege('anon', 'public.location_logs', 'INSERT') THEN
    RAISE EXCEPTION 'Client roles must not bypass the validated GPS writer with direct inserts';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'location_logs'
      AND column_name = 'received_at' AND column_default LIKE '%clock_timestamp%'
  ) THEN
    RAISE EXCEPTION 'location_logs.received_at must have a server-generated default';
  END IF;
  IF to_regclass('public.location_logs_received_rate_idx') IS NULL THEN
    RAISE EXCEPTION 'Concurrent receipt-time rate index is missing';
  END IF;
END;
$location_rpc_boundary_test$;
SQL
