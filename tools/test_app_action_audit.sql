-- Verify metadata-only app-action audit writes and Admin investigation visibility.
-- All fixtures are synthetic and run only in the clean PostgreSQL CI replay.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000170', 'audit-requester-170@example.test',
   '{"name":"Audit Requester","phone":"+63917000170","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000171', 'audit-responder-171@example.test',
   '{"name":"Audit Responder","phone":"+63917000171","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000172', 'audit-admin-172@example.test',
   '{"name":"Audit Admin","phone":"+63917000172","role":"requester"}'::jsonb);

DO $app_action_audit_fixture$
DECLARE
  requester_id bigint;
  responder_id bigint;
  admin_id bigint;
  v_incident_id bigint;
  notification_id bigint;
  event_count integer;
  feed jsonb;
BEGIN
  SELECT id INTO requester_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000170';
  SELECT id INTO responder_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000171';
  SELECT id INTO admin_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000172';

  IF requester_id IS NULL OR responder_id IS NULL OR admin_id IS NULL THEN
    RAISE EXCEPTION 'App-action audit fixture profiles were not created';
  END IF;

  UPDATE public.profiles
     SET role = 'responder', duty_status = 'off_duty'
   WHERE id = responder_id;
  -- Provisioning itself is retained as a system-attributed duty transition;
  -- below we separately assert the authenticated responder's own transition.
  UPDATE public.profiles SET role = 'admin' WHERE id = admin_id;

  INSERT INTO auth.sessions (id, user_id)
  VALUES
    ('10000000-0000-4000-8000-000000000170', '00000000-0000-4000-8000-000000000170'),
    ('10000000-0000-4000-8000-000000000171', '00000000-0000-4000-8000-000000000171'),
    ('10000000-0000-4000-8000-000000000172', '00000000-0000-4000-8000-000000000172');

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    requester_id, 'medical', 'Synthetic audit flow fixture',
    11.244, 125.003, now(), 'manual'
  ) RETURNING id INTO v_incident_id;

  -- A field-unit duty change is a committed user action; record the actor,
  -- role, target profile, and before/after values, never location or secrets.
  PERFORM set_config('request.jwt.claim.sub',
    '00000000-0000-4000-8000-000000000171', false);
  PERFORM set_config('request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000171","session_id":"10000000-0000-4000-8000-000000000171","aal":"aal1","amr":[{"method":"password"}]}',
    false);
  UPDATE public.profiles SET duty_status = 'available' WHERE id = responder_id;

  -- Exercise the real authenticated chat RPC. The audit trigger must not copy
  -- the message body or any attachment payload/path into its metadata.
  PERFORM set_config('request.jwt.claim.sub',
    '00000000-0000-4000-8000-000000000170', false);
  PERFORM set_config('request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000170","session_id":"10000000-0000-4000-8000-000000000170","aal":"aal1","amr":[{"method":"password"}]}',
    false);
  PERFORM public.api_send_incident_message(
    v_incident_id, 'Synthetic private chat body must not enter audit metadata',
    '00000000-0000-4000-8000-000000000170'::uuid
  );

  INSERT INTO public.user_notifications (
    recipient_user_id, incident_id, event_type, title, body
  ) VALUES (
    requester_id, v_incident_id, 'incident_message',
    'New incident message', 'Open TACResQ to view the message.'
  ) RETURNING id INTO notification_id;

  -- Exercise the real notification-read RPC; the trigger logs only the
  -- successful null -> timestamp transition and the notification type.
  IF NOT public.api_mark_notification_read(notification_id) THEN
    RAISE EXCEPTION 'Notification read RPC did not confirm the owned notification';
  END IF;

  -- A different authenticated user cannot mark the notification read. This
  -- denied/no-op result must not create a successful-action audit row.
  PERFORM set_config('request.jwt.claim.sub',
    '00000000-0000-4000-8000-000000000171', false);
  PERFORM set_config('request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000171","session_id":"10000000-0000-4000-8000-000000000171","aal":"aal1","amr":[{"method":"password"}]}',
    false);
  IF public.api_mark_notification_read(notification_id) THEN
    RAISE EXCEPTION 'Unrelated responder must not read this notification';
  END IF;
  PERFORM set_config('request.jwt.claim.sub',
    '00000000-0000-4000-8000-000000000170', false);

  IF NOT EXISTS (
    SELECT 1 FROM public.app_action_audit_logs AS audit
     WHERE audit.action = 'incident_message_sent'
       AND audit.actor_profile_id = requester_id
       AND audit.actor_role = 'requester'
       AND audit.incident_id = v_incident_id
       AND audit.outcome = 'success'
       AND audit.metadata->>'message_kind' = 'text'
       AND audit.auth_session_id = '10000000-0000-4000-8000-000000000170'::uuid
  ) OR NOT EXISTS (
    SELECT 1 FROM public.app_action_audit_logs AS audit
     WHERE audit.action = 'notification_marked_read'
       AND audit.actor_profile_id = requester_id
       AND audit.actor_role = 'requester'
       AND audit.target_id = notification_id::text
       AND audit.target_user_id = requester_id
       AND audit.metadata->>'notification_type' = 'incident_message'
       AND audit.auth_session_id = '10000000-0000-4000-8000-000000000170'::uuid
  ) OR NOT EXISTS (
    SELECT 1 FROM public.app_action_audit_logs AS audit
     WHERE audit.action = 'duty_status_changed'
       AND audit.actor_profile_id = responder_id
       AND audit.actor_role = 'responder'
       AND audit.target_user_id = responder_id
       AND audit.metadata->>'previous_status' = 'off_duty'
       AND audit.metadata->>'new_status' = 'available'
       AND audit.auth_session_id = '10000000-0000-4000-8000-000000000171'::uuid
  ) THEN
    RAISE EXCEPTION 'App-action audit rows do not preserve the expected actor, target, outcome, or safe metadata';
  END IF;

  IF (
    SELECT count(*) FROM public.app_action_audit_logs AS audit
     WHERE audit.action = 'notification_marked_read'
       AND audit.target_id = notification_id::text
  ) <> 1 THEN
    RAISE EXCEPTION 'An unauthorized notification-read attempt must not appear as a successful action';
  END IF;

  SELECT count(*) INTO event_count
    FROM public.app_action_audit_logs AS audit
   WHERE (audit.action = 'duty_status_changed' AND audit.target_user_id = responder_id)
      OR (audit.action = 'incident_message_sent' AND audit.incident_id = v_incident_id)
      OR (audit.action = 'notification_marked_read' AND audit.incident_id = v_incident_id);

  IF event_count < 3 THEN
    RAISE EXCEPTION 'Expected committed app-action events for duty, chat, and notification flows; found %', event_count;
  END IF;

  RAISE NOTICE 'App-action audit fixture produced % relevant rows; expected event types are asserted in the Admin feed below.', event_count;

  IF EXISTS (
    SELECT 1
      FROM public.app_action_audit_logs
     WHERE metadata::text ILIKE '%Synthetic private chat body%'
        OR metadata::text ILIKE '%11.244%'
        OR metadata::text ILIKE '%125.003%'
  ) THEN
    RAISE EXCEPTION 'App-action audit metadata contains private message content or coordinates';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM public.app_action_audit_logs
     WHERE correlation_id IS NULL OR outcome <> 'success'
        OR action NOT IN (
          'duty_status_changed', 'incident_message_sent', 'notification_marked_read'
        )
  ) THEN
    RAISE EXCEPTION 'App-action audit row has invalid correlation, outcome, or action';
  END IF;

  BEGIN
    UPDATE public.app_action_audit_logs
       SET metadata = '{}'::jsonb
     WHERE action = 'incident_message_sent' AND incident_id = v_incident_id;
    RAISE EXCEPTION 'App-action audit history unexpectedly allowed mutation';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;

  IF has_table_privilege('authenticated', 'public.app_action_audit_logs', 'SELECT')
     OR has_table_privilege('authenticated', 'public.app_action_audit_logs', 'INSERT')
     OR has_table_privilege('anon', 'public.app_action_audit_logs', 'SELECT')
     OR has_table_privilege('anon', 'public.app_action_audit_logs', 'INSERT') THEN
    RAISE EXCEPTION 'Client roles must not read or forge app-action audit rows';
  END IF;

  -- The investigation feed remains protected by current Admin role + AAL2 + TOTP.
  PERFORM set_config('request.jwt.claim.sub',
    '00000000-0000-4000-8000-000000000172', false);
  PERFORM set_config('request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000172","session_id":"10000000-0000-4000-8000-000000000172","aal":"aal2","amr":[{"method":"totp"}]}',
    false);
  feed := public.api_admin_list_investigation_events(
    100, 0, '', 'all', 'app_action_audit_logs',
    NULL, NULL, NULL, NULL, NULL
  );

  IF (feed->>'total_count')::integer < 3
     OR NOT EXISTS (
       SELECT 1 FROM jsonb_array_elements(feed->'events') AS j(value)
        WHERE value->>'event_type' = 'incident_message_sent'
          AND value->>'summary' = 'Incident chat message sent'
          AND value->'details'->>'actor_role' = 'requester'
          AND value->'details'->>'target_type' = 'incident_message'
          AND value->'details'->>'target_id' IS NOT NULL
          AND value->'details'->>'outcome' = 'success'
          AND value->'details'->>'correlation_id' IS NOT NULL
          AND value->'details'->>'auth_session_id' = '10000000-0000-4000-8000-000000000170'
     )
     OR NOT EXISTS (
       SELECT 1 FROM jsonb_array_elements(feed->'events') AS j(value)
        WHERE value->>'event_type' = 'notification_marked_read'
          AND value->'details'->>'target_type' = 'notification'
          AND value->'details'->>'target_id' = notification_id::text
          AND value->'details'->>'actor_role' = 'requester'
          AND value->'details'->>'auth_session_id' = '10000000-0000-4000-8000-000000000170'
     )
     OR NOT EXISTS (
       SELECT 1 FROM jsonb_array_elements(feed->'events') AS j(value)
        WHERE value->>'event_type' = 'duty_status_changed'
          AND value->'details'->>'actor_role' = 'responder'
          AND value->'details'->'metadata'->>'new_status' = 'available'
          AND value->'details'->>'auth_session_id' = '10000000-0000-4000-8000-000000000171'
     ) THEN
    RAISE EXCEPTION 'Admin investigation feed did not return the expected app-action events: %', feed;
  END IF;

  feed := public.api_admin_list_investigation_events(
    100, 0, '10000000-0000-4000-8000-000000000170',
    'all', 'app_action_audit_logs', NULL, NULL, NULL, NULL, NULL
  );
  IF (feed->>'total_count')::integer <> 2 THEN
    RAISE EXCEPTION 'Session-ID search should return only the requester session actions; found %', feed->>'total_count';
  END IF;

  IF feed::text ILIKE '%Synthetic private chat body%'
     OR feed::text ILIKE '%11.244%'
     OR feed::text ILIKE '%125.003%' THEN
    RAISE EXCEPTION 'Admin investigation feed leaked message content or coordinates';
  END IF;
END;
$app_action_audit_fixture$;

RESET ROLE;
SELECT 'PASS: committed app actions are audited without private content and are visible only through the protected Admin feed.' AS result;
