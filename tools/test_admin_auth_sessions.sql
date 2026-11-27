BEGIN;

-- These fixtures run only in the disposable clean-replay PostgreSQL database.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000201', 'session-inventory-admin@example.test',
    '{"name":"Session Inventory Admin","phone":"+63917000021"}'::jsonb),
  ('00000000-0000-4000-8000-000000000202', 'session-inventory-target@example.test',
    '{"name":"Session Inventory Target","phone":"+63917000022"}'::jsonb),
  ('00000000-0000-4000-8000-000000000203', 'session-inventory-suspended@example.test',
    '{"name":"Session Inventory Suspended Admin","phone":"+63917000023"}'::jsonb);

UPDATE public.profiles
   SET role = 'admin', account_status = 'active'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000201';
UPDATE public.profiles
   SET role = 'admin', account_status = 'suspended'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000203';

INSERT INTO auth.sessions (
  id, user_id, created_at, updated_at, refreshed_at, aal, not_after,
  user_agent, ip, refresh_token_hmac_key, refresh_token_counter, scopes
) VALUES
  (
    '00000000-0000-4000-8000-000000000301',
    '00000000-0000-4000-8000-000000000202',
    '2026-11-27 02:00:00+00', '2026-11-27 02:05:00+00',
    '2026-11-27 02:05:00', 'aal2', NULL,
    'sensitive-user-agent-fixture', '192.0.2.21',
    'sensitive-refresh-hmac-fixture', 91, 'sensitive-scope-fixture'
  ),
  (
    '00000000-0000-4000-8000-000000000302',
    '00000000-0000-4000-8000-000000000202',
    '2026-11-26 02:00:00+00', '2026-11-26 02:05:00+00',
    '2026-11-26 02:05:00', 'aal1', '2026-12-01 00:00:00+00',
    'sensitive-user-agent-fixture', '192.0.2.22',
    'sensitive-refresh-hmac-fixture', 92, 'sensitive-scope-fixture'
  ),
  (
    '00000000-0000-4000-8000-000000000303',
    '00000000-0000-4000-8000-000000000202',
    NULL, NULL, NULL, NULL, NULL,
    'sensitive-user-agent-fixture', '192.0.2.23',
    'sensitive-refresh-hmac-fixture', 93, 'sensitive-scope-fixture'
  ),
  (
    '00000000-0000-4000-8000-000000000304',
    '00000000-0000-4000-8000-000000000202',
    NULL, NULL, NULL, NULL, NULL,
    'sensitive-user-agent-fixture', '192.0.2.24',
    'sensitive-refresh-hmac-fixture', 94, 'sensitive-scope-fixture'
  ),
  (
    '00000000-0000-4000-8000-000000000305',
    '00000000-0000-4000-8000-000000000202',
    NULL, NULL, NULL, NULL, NULL,
    'sensitive-user-agent-fixture', '192.0.2.25',
    'sensitive-refresh-hmac-fixture', 95, 'sensitive-scope-fixture'
  );

SELECT set_config(
  'test.session_inventory_admin_id',
  (SELECT id::text FROM public.profiles
    WHERE auth_user_id = '00000000-0000-4000-8000-000000000201'),
  true
);
SELECT set_config(
  'test.session_inventory_suspended_admin_id',
  (SELECT id::text FROM public.profiles
    WHERE auth_user_id = '00000000-0000-4000-8000-000000000203'),
  true
);
SELECT set_config(
  'test.session_inventory_target_id',
  (SELECT id::text FROM public.profiles
    WHERE auth_user_id = '00000000-0000-4000-8000-000000000202'),
  true
);

DO $privilege_contract$
BEGIN
  IF NOT has_function_privilege(
    'authenticated',
    'public.api_admin_list_auth_sessions_page(bigint,integer,timestamp with time zone,uuid)',
    'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'Authenticated role must be able to call the protected session RPC';
  END IF;
  IF has_function_privilege(
    'anon',
    'public.api_admin_list_auth_sessions_page(bigint,integer,timestamp with time zone,uuid)',
    'EXECUTE'
  ) OR has_function_privilege(
    'service_role',
    'public.api_admin_list_auth_sessions_page(bigint,integer,timestamp with time zone,uuid)',
    'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'Anon and service_role must not call the Admin session RPC';
  END IF;
  IF has_table_privilege('authenticated', 'auth.sessions', 'SELECT')
     OR has_table_privilege('anon', 'auth.sessions', 'SELECT')
     OR has_table_privilege('service_role', 'auth.sessions', 'SELECT') THEN
    RAISE EXCEPTION 'Auth session table must not be directly readable by API roles';
  END IF;
END;
$privilege_contract$;

SET LOCAL ROLE authenticated;
SELECT set_config(
  'request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000201',
  true
);
SELECT set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-4000-8000-000000000201","aal":"aal2","amr":[{"method":"totp"}]}'::text,
  true
);

DO $session_reader_contract$
DECLARE
  v_target_profile_id bigint := current_setting('test.session_inventory_target_id')::bigint;
  v_page_one jsonb;
  v_page_two jsonb;
  v_cursor_created_at timestamptz;
  v_cursor_session_id uuid;
  v_rejected boolean;
BEGIN
  v_page_one := public.api_admin_list_auth_sessions_page(v_target_profile_id, 2, NULL, NULL);
  IF jsonb_array_length(v_page_one -> 'items') <> 2
     OR v_page_one ->> 'has_more' <> 'true'
     OR v_page_one ->> 'source' <> 'supabase_auth_sessions'
     OR v_page_one ->> 'source_status' <> 'available' THEN
    RAISE EXCEPTION 'First session page returned invalid metadata: %', v_page_one;
  END IF;
  IF v_page_one -> 'items' -> 0 ->> 'session_id'
       <> '00000000-0000-4000-8000-000000000301'
     OR v_page_one -> 'items' -> 1 ->> 'session_id'
       <> '00000000-0000-4000-8000-000000000302' THEN
    RAISE EXCEPTION 'Session page ordering or target-profile filtering is incorrect: %', v_page_one;
  END IF;
  IF EXISTS (
    SELECT 1
    FROM jsonb_array_elements(v_page_one -> 'items') AS item
    CROSS JOIN LATERAL jsonb_object_keys(item) AS keys(key_name)
    WHERE keys.key_name IN (
      'ip', 'user_agent', 'refresh_token_hmac_key',
      'refresh_token_counter', 'scopes', 'factor_id',
      'oauth_client_id', 'access_token', 'refresh_token'
    )
  ) THEN
    RAISE EXCEPTION 'Session RPC exposed a forbidden Auth/session field';
  END IF;
  IF v_page_one -> 'items' -> 0 ->> 'record_status' <> 'record_present'
     OR v_page_one -> 'items' -> 0 ->> 'validity_assessment' <> 'not_evaluated'
     OR v_page_one -> 'items' -> 0 ->> 'aal' <> 'aal2' THEN
    RAISE EXCEPTION 'Session status semantics or allowlisted AAL projection is incorrect';
  END IF;

  v_cursor_created_at := (v_page_one -> 'next_cursor' ->> 'created_at')::timestamptz;
  v_cursor_session_id := (v_page_one -> 'next_cursor' ->> 'session_id')::uuid;
  v_page_two := public.api_admin_list_auth_sessions_page(
    v_target_profile_id, 2, v_cursor_created_at, v_cursor_session_id
  );
  IF jsonb_array_length(v_page_two -> 'items') <> 2
     OR v_page_two ->> 'has_more' <> 'true'
     OR v_page_two -> 'items' -> 0 ->> 'session_id'
       <> '00000000-0000-4000-8000-000000000305'
     OR v_page_two -> 'items' -> 1 ->> 'session_id'
       <> '00000000-0000-4000-8000-000000000304' THEN
    RAISE EXCEPTION 'Keyset pagination did not return the NULLS LAST page correctly: %', v_page_two;
  END IF;

  -- The cursor after the NULLS LAST page itself has a NULL timestamp. Verify
  -- that the session-ID tie-breaker still advances without duplicates/skips.
  v_cursor_created_at := (v_page_two -> 'next_cursor' ->> 'created_at')::timestamptz;
  v_cursor_session_id := (v_page_two -> 'next_cursor' ->> 'session_id')::uuid;
  IF v_cursor_created_at IS NOT NULL
     OR v_cursor_session_id <> '00000000-0000-4000-8000-000000000304' THEN
    RAISE EXCEPTION 'NULLS LAST page cursor is malformed: %', v_page_two -> 'next_cursor';
  END IF;
  v_page_two := public.api_admin_list_auth_sessions_page(
    v_target_profile_id, 2, v_cursor_created_at, v_cursor_session_id
  );
  IF jsonb_array_length(v_page_two -> 'items') <> 1
     OR v_page_two ->> 'has_more' <> 'false'
     OR v_page_two -> 'next_cursor' <> 'null'::jsonb
     OR v_page_two -> 'items' -> 0 ->> 'session_id'
       <> '00000000-0000-4000-8000-000000000303' THEN
    RAISE EXCEPTION 'Keyset pagination did not advance through the NULL timestamp tail: %', v_page_two;
  END IF;

  -- Non-Admin profiles are denied even when their JWT claims contain AAL2/TOTP.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000202', true);
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000202","aal":"aal2","amr":[{"method":"totp"}]}',
    true
  );
  v_rejected := false;
  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(v_target_profile_id, 2, NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'Non-Admin session inventory request must be denied';
  END IF;

  -- AAL1 and AAL2 without TOTP are both denied for an otherwise active Admin.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000201', true);
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000201","aal":"aal1","amr":[{"method":"password"}]}',
    true
  );
  v_rejected := false;
  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(v_target_profile_id, 2, NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'AAL1 session inventory request must be denied';
  END IF;

  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000201","aal":"aal2","amr":[{"method":"password"}]}',
    true
  );
  v_rejected := false;
  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(v_target_profile_id, 2, NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'AAL2 without TOTP session inventory request must be denied';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000203', true);
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000203","aal":"aal2","amr":[{"method":"totp"}]}',
    true
  );
  v_rejected := false;
  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(v_target_profile_id, 2, NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'Suspended Admin session inventory request must be denied';
  END IF;
END;
$session_reader_contract$;

ROLLBACK;
