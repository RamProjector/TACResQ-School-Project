BEGIN;

-- Synthetic-only fixtures for the disposable clean-replay database.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000401',
  'api-operation-investigation-admin@example.test',
  '{"name":"API Operation Investigation Admin","phone":"+639171234567"}'::jsonb
);

UPDATE public.profiles
   SET role = 'admin', account_status = 'active'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000401';

INSERT INTO auth.sessions (id, user_id)
VALUES (
  '10000000-0000-4000-8000-000000000401',
  '00000000-0000-4000-8000-000000000401'
);

INSERT INTO public.api_operation_logs (
  correlation_id, actor_auth_user_id, auth_session_id, provider, operation, source, outcome,
  http_status, duration_ms, provider_request_id, error_code
) VALUES
  (
    '00000000-0000-4000-8000-000000000410',
    '00000000-0000-4000-8000-000000000401',
    '10000000-0000-4000-8000-000000000401',
    'supabase', 'auth.get_user', 'render_api', 'success',
    200, 18, 'sb-auth-request-fixture-410', NULL
  ),
  (
    '00000000-0000-4000-8000-000000000410',
    '00000000-0000-4000-8000-000000000401',
    '10000000-0000-4000-8000-000000000401',
    'supabase', 'rpc.api_units', 'render_api', 'failure',
    503, 241, 'sb-rpc-request-fixture-410', 'upstream_unavailable'
  );

INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000402',
  'api-operation-investigation-requester@example.test',
  '{"name":"Session Correlation Requester","phone":"+639171234568"}'::jsonb
);

INSERT INTO auth.sessions (id, user_id)
VALUES (
  '10000000-0000-4000-8000-000000000402',
  '00000000-0000-4000-8000-000000000402'
);

-- Exercise the real BEFORE INSERT session-stamping triggers using two
-- independent signed-JWT contexts. The feed reader below remains Admin-only.
DO $session_event_fixtures$
DECLARE
  v_admin_profile_id bigint;
  v_requester_profile_id bigint;
  v_incident_id bigint;
BEGIN
  SELECT id INTO v_admin_profile_id
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000401';
  SELECT id INTO v_requester_profile_id
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000402';

  PERFORM set_config('request.jwt.claim.sub',
    '00000000-0000-4000-8000-000000000402', true);
  PERFORM set_config('request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000402","session_id":"10000000-0000-4000-8000-000000000402","aal":"aal1","amr":[{"method":"password"}]}'::text,
    true);

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude, status
  ) VALUES (
    v_requester_profile_id, 'medical', 'Disposable session-correlation fixture',
    10.0, 10.0, 'pending'
  ) RETURNING id INTO v_incident_id;

  INSERT INTO public.incident_request_edit_logs (
    incident_id, requester_id, edit_number, before_values, after_values
  ) VALUES (
    v_incident_id, v_requester_profile_id, 1,
    '{"type":"medical"}'::jsonb, '{"type":"fire"}'::jsonb
  );

  PERFORM set_config('request.jwt.claim.sub',
    '00000000-0000-4000-8000-000000000401', true);
  PERFORM set_config('request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000401","session_id":"10000000-0000-4000-8000-000000000401","aal":"aal2","amr":[{"method":"totp"}]}'::text,
    true);

  INSERT INTO public.incident_claimability_logs (
    incident_id, actor_profile_id, source_rpc, previous_claimable,
    new_claimable, incident_status
  ) VALUES (
    v_incident_id, v_admin_profile_id, 'api_set_incident_claimable',
    false, true, 'pending'
  );

  INSERT INTO public.incident_assignment_events (
    incident_id, user_id, event_type, actor_user_id, source,
    assignment_status, incident_status
  ) VALUES (
    v_incident_id, v_requester_profile_id, 'assigned', v_admin_profile_id,
    'assignment_insert', NULL, 'pending'
  );

  INSERT INTO public.admin_audit_logs (
    actor_user_id, actor_identity, target_user_id, action,
    old_role, new_role, reason
  ) VALUES (
    v_admin_profile_id, 'Session Correlation Admin', v_requester_profile_id,
    'user_role_changed', 'requester', 'responder',
    'Disposable session correlation fixture'
  );

  INSERT INTO public.account_lifecycle_audit_logs (
    actor_profile_id, actor_identity, target_profile_id, action,
    previous_status, new_status, reason
  ) VALUES (
    v_admin_profile_id, 'Session Correlation Admin', v_requester_profile_id,
    'account_suspended', 'active', 'suspended',
    'Disposable session correlation fixture'
  );
END;
$session_event_fixtures$;

DO $privilege_contract$
BEGIN
  IF NOT has_function_privilege(
    'authenticated',
    'public.api_admin_list_investigation_events(integer,integer,text,text,text,timestamp with time zone,timestamp with time zone,bigint,bigint,bigint)',
    'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'Authenticated must call the protected investigation feed';
  END IF;
  IF has_function_privilege(
    'anon',
    'public.api_admin_list_investigation_events(integer,integer,text,text,text,timestamp with time zone,timestamp with time zone,bigint,bigint,bigint)',
    'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'Anon must not execute the Admin investigation feed';
  END IF;
  IF has_table_privilege('authenticated', 'public.api_operation_logs', 'SELECT')
     OR has_table_privilege('anon', 'public.api_operation_logs', 'SELECT') THEN
    RAISE EXCEPTION 'API operation telemetry must not be directly readable by client roles';
  END IF;
END;
$privilege_contract$;

SET LOCAL ROLE authenticated;
SELECT set_config(
  'request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000401',
  true
);
SELECT set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-4000-8000-000000000401","session_id":"10000000-0000-4000-8000-000000000401","aal":"aal2","amr":[{"method":"totp"}]}'::text,
  true
);

DO $feed_contract$
DECLARE
  v_page jsonb;
  v_by_correlation jsonb;
  v_rejected boolean := false;
BEGIN
  v_page := public.api_admin_list_investigation_events(
    10, 0, '', 'operations', 'api_operation_logs',
    NULL, NULL, NULL, NULL, NULL
  );
  IF jsonb_array_length(v_page -> 'events') <> 2
     OR v_page ->> 'total_count' <> '2' THEN
    RAISE EXCEPTION 'Expected two correlated API operation events: %', v_page;
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM jsonb_array_elements(v_page -> 'events') AS rows(event)
     WHERE rows.event ->> 'event_type' = 'auth.get_user'
       AND rows.event -> 'details' ->> 'provider_request_id' = 'sb-auth-request-fixture-410'
       AND rows.event -> 'details' ->> 'outcome' = 'success'
       AND rows.event -> 'details' ->> 'correlation_id'
         = '00000000-0000-4000-8000-000000000410'
       AND rows.event -> 'details' ->> 'auth_session_id'
         = '10000000-0000-4000-8000-000000000401'
  ) THEN
    RAISE EXCEPTION 'Auth operation event is missing its provider/correlation metadata: %', v_page;
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM jsonb_array_elements(v_page -> 'events') AS rows(event)
     WHERE rows.event ->> 'event_type' = 'rpc.api_units'
       AND rows.event -> 'details' ->> 'provider_request_id' = 'sb-rpc-request-fixture-410'
       AND rows.event -> 'details' ->> 'error_code' = 'upstream_unavailable'
       AND rows.event -> 'details' ->> 'correlation_id'
         = '00000000-0000-4000-8000-000000000410'
       AND rows.event -> 'details' ->> 'auth_session_id'
         = '10000000-0000-4000-8000-000000000401'
  ) THEN
    RAISE EXCEPTION 'RPC operation event is missing its provider/correlation metadata: %', v_page;
  END IF;

  v_by_correlation := public.api_admin_list_investigation_events(
    10, 0, '00000000-0000-4000-8000-000000000410',
    'operations', 'api_operation_logs',
    NULL, NULL, NULL, NULL, NULL
  );
  IF jsonb_array_length(v_by_correlation -> 'events') <> 2 THEN
    RAISE EXCEPTION 'Searching the shared correlation ID must return both bridge events: %', v_by_correlation;
  END IF;

  v_by_correlation := public.api_admin_list_investigation_events(
    10, 0, '10000000-0000-4000-8000-000000000401',
    'operations', 'api_operation_logs',
    NULL, NULL, NULL, NULL, NULL
  );
  IF jsonb_array_length(v_by_correlation -> 'events') <> 2 THEN
    RAISE EXCEPTION 'Searching the Auth session ID must return both bridge events: %', v_by_correlation;
  END IF;

  -- The Admin session must find Admin role, lifecycle, claimability, and
  -- assignment events through the same Auth session ID.
  v_by_correlation := public.api_admin_list_investigation_events(
    25, 0, '10000000-0000-4000-8000-000000000401',
    'all', 'all', NULL, NULL, NULL, NULL, NULL
  );
  IF NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
     WHERE rows.event ->> 'source' = 'admin_audit_logs'
       AND rows.event -> 'details' ->> 'auth_session_id'
         = '10000000-0000-4000-8000-000000000401'
  ) OR NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
     WHERE rows.event ->> 'source' = 'account_lifecycle_audit_logs'
       AND rows.event -> 'details' ->> 'auth_session_id'
         = '10000000-0000-4000-8000-000000000401'
  ) OR NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
     WHERE rows.event ->> 'source' = 'incident_claimability_logs'
       AND rows.event -> 'details' ->> 'auth_session_id'
         = '10000000-0000-4000-8000-000000000401'
  ) OR NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
     WHERE rows.event ->> 'source' = 'incident_assignment_events'
       AND rows.event -> 'details' ->> 'auth_session_id'
         = '10000000-0000-4000-8000-000000000401'
  ) THEN
    RAISE EXCEPTION 'Admin session search did not find every stamped Admin event source: %', v_by_correlation;
  END IF;

  -- The requester session must also find its incident status and edit events,
  -- while the query itself remains authorized by the separate Admin session.
  v_by_correlation := public.api_admin_list_investigation_events(
    25, 0, '10000000-0000-4000-8000-000000000402',
    'all', 'all', NULL, NULL, NULL, NULL, NULL
  );
  IF NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
     WHERE rows.event ->> 'source' = 'incident_status_logs'
       AND rows.event -> 'details' ->> 'auth_session_id'
         = '10000000-0000-4000-8000-000000000402'
  ) OR NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
     WHERE rows.event ->> 'source' = 'incident_request_edit_logs'
       AND rows.event -> 'details' ->> 'auth_session_id'
         = '10000000-0000-4000-8000-000000000402'
  ) THEN
    RAISE EXCEPTION 'Requester session search did not find incident status and edit events: %', v_by_correlation;
  END IF;

  -- The same RPC must still reject an Admin token without AAL2/TOTP.
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000401","session_id":"10000000-0000-4000-8000-000000000401","aal":"aal1","amr":[{"method":"password"}]}'::text,
    true
  );
  BEGIN
    PERFORM public.api_admin_list_investigation_events(
      10, 0, '', 'operations', 'api_operation_logs',
      NULL, NULL, NULL, NULL, NULL
    );
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'Investigation feed must reject an Admin without AAL2/TOTP';
  END IF;
END;
$feed_contract$;

ROLLBACK;
