BEGIN;

DO $trace_parser_contract$
BEGIN
  PERFORM set_config('request.headers', '{"traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}', true);
  IF private.tacresq_current_trace_id() IS DISTINCT FROM '4bf92f3577b34da6a3ce929d0e0e4736' THEN
    RAISE EXCEPTION 'Valid W3C traceparent did not return its trace ID';
  END IF;
  PERFORM set_config('request.headers', '{"traceparent":"00-00000000000000000000000000000000-00f067aa0ba902b7-01"}', true);
  IF private.tacresq_current_trace_id() IS NOT NULL THEN
    RAISE EXCEPTION 'All-zero trace ID must be rejected';
  END IF;
  PERFORM set_config('request.headers', '{"traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"}', true);
  IF private.tacresq_current_trace_id() IS NOT NULL THEN
    RAISE EXCEPTION 'All-zero span ID must be rejected';
  END IF;
  PERFORM set_config('request.headers', '{"traceparent":"01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}', true);
  IF private.tacresq_current_trace_id() IS NOT NULL THEN
    RAISE EXCEPTION 'Unsupported traceparent version must be rejected';
  END IF;
  PERFORM set_config('request.headers', '{"x-request-id":"not-a-trace"}', true);
  IF private.tacresq_current_trace_id() IS NOT NULL THEN
    RAISE EXCEPTION 'Missing traceparent must return NULL';
  END IF;
  PERFORM set_config('request.headers', 'not-json', true);
  IF private.tacresq_current_trace_id() IS NOT NULL THEN
    RAISE EXCEPTION 'Malformed request headers must return NULL';
  END IF;
END;
$trace_parser_contract$;


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

-- Synthetic provider-owned Auth audit fixture. Hosted and pinned local
-- Supabase Auth schemas currently use different primary-key types; the reader
-- treats the provider row ID as an opaque source reference and casts it to text.
-- This test adapts the fixture to the actual disposable schema without changing
-- the provider-owned table definition.
DO $auth_audit_fixture$
DECLARE
  v_id_type text;
BEGIN
  SELECT data_type INTO v_id_type
    FROM information_schema.columns
   WHERE table_schema = 'auth'
     AND table_name = 'audit_log_entries'
     AND column_name = 'id';

  IF v_id_type = 'uuid' THEN
    INSERT INTO auth.audit_log_entries (id, payload, created_at)
    VALUES (
      '20000000-0000-4000-8000-000000000401'::uuid,
      json_build_object(
        'action', 'login',
        'user_id', '00000000-0000-4000-8000-000000000401'
      ),
      now()
    );
  ELSIF v_id_type = 'bigint' THEN
    INSERT INTO auth.audit_log_entries (id, payload, created_at)
    VALUES (
      200000000000000401::bigint,
      json_build_object(
        'action', 'login',
        'user_id', '00000000-0000-4000-8000-000000000401'
      ),
      now()
    );
  ELSE
    RAISE EXCEPTION 'Unsupported auth.audit_log_entries.id type: %', v_id_type;
  END IF;
END;
$auth_audit_fixture$;

SELECT set_config('request.headers', '{"traceparent":"00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"}', true);

INSERT INTO public.api_operation_logs (
  correlation_id, actor_auth_user_id, auth_session_id, provider, operation, source, outcome,
  http_status, duration_ms, provider_request_id, error_code, trace_id
) VALUES
  (
    '00000000-0000-4000-8000-000000000410',
    '00000000-0000-4000-8000-000000000401',
    '10000000-0000-4000-8000-000000000401',
    'supabase', 'auth.get_user', 'render_api', 'success',
    200, 18, 'sb-auth-request-fixture-410', NULL,
    'ffffffffffffffffffffffffffffffff'
  ),
  (
    '00000000-0000-4000-8000-000000000410',
    '00000000-0000-4000-8000-000000000401',
    '10000000-0000-4000-8000-000000000401',
    'supabase', 'rpc.api_units', 'render_api', 'failure',
    503, 241, 'sb-rpc-request-fixture-410', 'upstream_unavailable',
    'ffffffffffffffffffffffffffffffff'
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
  PERFORM set_config('request.headers', '{"traceparent":"00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01"}', true);

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
  PERFORM set_config('request.headers', '{"traceparent":"00-11111111111111111111111111111111-2222222222222222-01"}', true);

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

  -- The device-security event must receive the same verified session and
  -- W3C trace attribution as other event rows written in this request.
  INSERT INTO public.device_security_events (
    profile_id, event_type, severity, details
  ) VALUES (
    v_admin_profile_id, 'suspicious_activity', 'high',
    '{"reason_code":"disposable_session_correlation_fixture"}'::jsonb
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
  IF has_table_privilege('authenticated', 'auth.audit_log_entries', 'SELECT')
     OR has_table_privilege('anon', 'auth.audit_log_entries', 'SELECT') THEN
    RAISE EXCEPTION 'Provider-owned Auth audit rows must remain inaccessible to direct client table reads';
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
  v_auth_page jsonb;
  v_admin_profile_id bigint;
  v_rejected boolean := false;
BEGIN
  SELECT id INTO v_admin_profile_id
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000401';
  -- The provider audit reader must expose the native event while
  -- retaining source identity and avoiding inferred session/trace attribution.
  v_auth_page := public.api_admin_list_investigation_events(
    10, 0, '', 'authentication', 'supabase_auth_audit_logs',
    NULL, NULL, NULL, NULL, NULL
  );
  IF v_auth_page ->> 'contract_version' <> 'session_trace_v1'
     OR v_auth_page ->> 'total_count' <> '1'
     OR jsonb_array_length(v_auth_page -> 'events') <> 1
     OR NOT EXISTS (
       SELECT 1
         FROM jsonb_array_elements(v_auth_page -> 'events') AS rows(event)
        WHERE rows.event ->> 'source' = 'supabase_auth_audit_logs'
          AND rows.event ->> 'category' = 'authentication'
          AND rows.event ->> 'event_type' = 'login'
          AND rows.event ->> 'actor_user_id' = v_admin_profile_id::text
          AND rows.event -> 'details' ->> 'provider_action' = 'login'
          AND NOT (rows.event -> 'details' ? 'auth_session_id')
          AND NOT (rows.event -> 'details' ? 'trace_id')
          AND NOT (rows.event -> 'details' ? 'ip_address')
          AND NOT (rows.event -> 'details' ? 'user_agent')
          AND NOT (rows.event -> 'details' ? 'payload')
     ) THEN
    RAISE EXCEPTION 'Native Supabase Auth audit adapter lost provenance, actor mapping, or privacy minimization: %', v_auth_page;
  END IF;

  v_page := public.api_admin_list_investigation_events(
    10, 0, '', 'operations', 'api_operation_logs',
    NULL, NULL, NULL, NULL, NULL
  );
  IF v_page ->> 'contract_version' <> 'session_trace_v1'
     OR jsonb_array_length(v_page -> 'events') <> 2
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
       AND rows.event -> 'details' ->> 'trace_id'
         = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
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
  ) OR NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
     WHERE rows.event ->> 'source' = 'device_security_events'
       AND rows.event -> 'details' ->> 'auth_session_id'
         = '10000000-0000-4000-8000-000000000401'
       AND rows.event -> 'details' ->> 'trace_id'
         = '11111111111111111111111111111111'
       AND rows.event -> 'details' ->> 'severity' = 'high'
  ) THEN
    RAISE EXCEPTION 'Admin session search did not find every stamped Admin and device-security event source: %', v_by_correlation;
  END IF;

  v_by_correlation := public.api_admin_list_investigation_events(
    25, 0, '11111111111111111111111111111111',
    'all', 'all', NULL, NULL, NULL, NULL, NULL
  );
  IF jsonb_array_length(v_by_correlation -> 'events') < 5
     OR NOT EXISTS (
       SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
        WHERE rows.event ->> 'source' = 'device_security_events'
          AND rows.event -> 'details' ->> 'auth_session_id'
            = '10000000-0000-4000-8000-000000000401'
     )
     OR EXISTS (
       SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
        WHERE rows.event -> 'details' ->> 'trace_id'
          IS DISTINCT FROM '11111111111111111111111111111111'
     ) THEN
    RAISE EXCEPTION 'Exact trace-ID search must include the matching device-security event and exclude unrelated trace rows: %', v_by_correlation;
  END IF;

  v_by_correlation := public.api_admin_list_investigation_events(
    25, 0, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    'operations', 'api_operation_logs', NULL, NULL, NULL, NULL, NULL
  );
  IF jsonb_array_length(v_by_correlation -> 'events') <> 2
     OR EXISTS (
       SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
        WHERE rows.event -> 'details' ->> 'trace_id'
          IS DISTINCT FROM 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
     ) THEN
    RAISE EXCEPTION 'Trace-ID search must find both bridge audit writes with the matching trace: %', v_by_correlation;
  END IF;

  v_by_correlation := public.api_admin_list_investigation_events(
    25, 0, 'cccccccccccccccccccccccccccccccc',
    'all', 'all', NULL, NULL, NULL, NULL, NULL
  );
  IF jsonb_array_length(v_by_correlation -> 'events') < 2
     OR EXISTS (
       SELECT 1 FROM jsonb_array_elements(v_by_correlation -> 'events') AS rows(event)
        WHERE rows.event -> 'details' ->> 'trace_id'
          IS DISTINCT FROM 'cccccccccccccccccccccccccccccccc'
     ) THEN
    RAISE EXCEPTION 'Requester trace-ID search must return only matching requester trace rows: %', v_by_correlation;
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
       AND rows.event -> 'details' ->> 'trace_id'
         = 'cccccccccccccccccccccccccccccccc'
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

\echo PASS: Admin investigation W3C trace parsing, stamping, exact search, and session attribution contract.
