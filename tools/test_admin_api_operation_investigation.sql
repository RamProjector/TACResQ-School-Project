BEGIN;

-- Synthetic-only fixtures for the disposable clean-replay database.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000401',
  'api-operation-investigation-admin@example.test',
  '{"name":"API Operation Investigation Admin"}'::jsonb
);

UPDATE public.profiles
   SET role = 'admin', account_status = 'active'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000401';

INSERT INTO public.api_operation_logs (
  correlation_id, actor_auth_user_id, provider, operation, source, outcome,
  http_status, duration_ms, provider_request_id, error_code
) VALUES
  (
    '00000000-0000-4000-8000-000000000410',
    '00000000-0000-4000-8000-000000000401',
    'supabase', 'auth.get_user', 'render_api', 'success',
    200, 18, 'sb-auth-request-fixture-410', NULL
  ),
  (
    '00000000-0000-4000-8000-000000000410',
    '00000000-0000-4000-8000-000000000401',
    'supabase', 'rpc.api_units', 'render_api', 'failure',
    503, 241, 'sb-rpc-request-fixture-410', 'upstream_unavailable'
  );

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
  '{"sub":"00000000-0000-4000-8000-000000000401","aal":"aal2","amr":[{"method":"totp"}]}'::text,
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

  -- The same RPC must still reject an Admin token without AAL2/TOTP.
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000401","aal":"aal1","amr":[{"method":"password"}]}'::text,
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
