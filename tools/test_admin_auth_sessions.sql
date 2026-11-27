BEGIN;

-- These fixtures run only in the disposable clean-replay PostgreSQL database.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000201', 'session-inventory-admin@example.test',
    '{"name":"Session Inventory Admin","phone":"+63917000021"}'::jsonb),
  ('00000000-0000-4000-8000-000000000202', 'session-inventory-target@example.test',
    '{"name":"Session Inventory Target","phone":"+63917000022"}'::jsonb),
  ('00000000-0000-4000-8000-000000000203', 'session-inventory-suspended@example.test',
    '{"name":"Session Inventory Suspended Admin","phone":"+63917000023"}'::jsonb),
  ('00000000-0000-4000-8000-000000000204', 'session-inventory-non-admin@example.test',
    '{"name":"Session Inventory Non Admin","phone":"+63917000024"}'::jsonb),
  ('00000000-0000-4000-8000-000000000205', 'session-inventory-empty@example.test',
    '{"name":"Session Inventory Empty Target","phone":"+63917000025"}'::jsonb);

UPDATE public.profiles
   SET role = 'admin', account_status = 'active'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000201';
UPDATE public.profiles AS suspended_admin
   SET role = 'admin',
       account_status = 'suspended',
       suspended_at = statement_timestamp(),
       suspended_by = (
         SELECT actor.id
           FROM public.profiles AS actor
          WHERE actor.auth_user_id = '00000000-0000-4000-8000-000000000201'
       ),
       suspension_reason = 'Disposable session inventory authorization test'
 WHERE suspended_admin.auth_user_id = '00000000-0000-4000-8000-000000000203';

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
  ),
  (
    '00000000-0000-4000-8000-000000000306',
    '00000000-0000-4000-8000-000000000201',
    '2026-11-27 03:00:00+00', '2026-11-27 03:05:00+00',
    '2026-11-27 03:05:00', 'aal2', NULL,
    'admin-session-fixture', '192.0.2.26',
    'sensitive-refresh-hmac-fixture', 96, 'sensitive-scope-fixture'
  ),
  (
    '00000000-0000-4000-8000-000000000307',
    '00000000-0000-4000-8000-000000000204',
    '2026-11-27 03:10:00+00', '2026-11-27 03:15:00+00',
    '2026-11-27 03:15:00', 'aal2', NULL,
    'non-admin-session-fixture', '192.0.2.27',
    'sensitive-refresh-hmac-fixture', 97, 'sensitive-scope-fixture'
  ),
  (
    '00000000-0000-4000-8000-000000000308',
    '00000000-0000-4000-8000-000000000203',
    '2026-11-27 03:20:00+00', '2026-11-27 03:25:00+00',
    '2026-11-27 03:25:00', 'aal2', NULL,
    'suspended-admin-session-fixture', '192.0.2.28',
    'sensitive-refresh-hmac-fixture', 98, 'sensitive-scope-fixture'
  ),
  (
    '00000000-0000-4000-8000-000000000309',
    '00000000-0000-4000-8000-000000000201',
    '2026-11-27 03:30:00+00', '2026-11-27 03:35:00+00',
    '2026-11-27 03:35:00', 'aal2', NULL,
    'revocation-test-session-fixture', '192.0.2.29',
    'sensitive-refresh-hmac-fixture', 99, 'sensitive-scope-fixture'
  );

INSERT INTO public.admin_audit_logs (
  actor_user_id, actor_identity, target_user_id, action,
  old_role, new_role, reason
) VALUES (
  (SELECT id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000201'),
  'Session Inventory Admin',
  (SELECT id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000202'),
  'user_role_changed',
  'requester',
  'responder',
  'Disposable audit fixture for session revocation policy'
);

DO $audit_fixture_owner_check$
DECLARE
  v_count bigint;
BEGIN
  SELECT count(*) INTO v_count
    FROM public.admin_audit_logs
   WHERE action = 'user_role_changed'
     AND reason = 'Disposable audit fixture for session revocation policy'
     AND actor_user_id = current_setting('test.session_inventory_admin_id')::bigint;
  IF v_count <> 1 THEN
    RAISE EXCEPTION 'Expected one owner-visible disposable Admin audit fixture row, found %', v_count;
  END IF;
END;
$audit_fixture_owner_check$;

SELECT set_config(
  'test.session_inventory_admin_id',
  (SELECT id::text FROM public.profiles
    WHERE auth_user_id = '00000000-0000-4000-8000-000000000201'),
  true
);
SELECT set_config(
  'test.session_inventory_suspended_admin_id',
  (SELECT id::text FROM public.profiles
    WHERE auth_user_id = '00000000-0000-4000-8000-000000000205'),
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
    'private.tacresq_current_auth_session_present_read()',
    'EXECUTE'
  ) OR has_function_privilege(
    'authenticated',
    'private.tacresq_current_auth_session_valid()',
    'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'Only the read-only session predicate may be directly callable by authenticated';
  END IF;
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
  '{"sub":"00000000-0000-4000-8000-000000000201","session_id":"00000000-0000-4000-8000-000000000306","aal":"aal2","amr":[{"method":"totp"}]}'::text,
  true
);

DO $session_reader_contract$
DECLARE
  v_target_profile_id bigint := current_setting('test.session_inventory_target_id')::bigint;
  v_empty_target_profile_id bigint := current_setting('test.session_inventory_suspended_admin_id')::bigint;
  v_page_one jsonb;
  v_page_two jsonb;
  v_cursor_created_at timestamptz;
  v_cursor_session_id uuid;
  v_rejected boolean;
  v_visible_audit_rows bigint;
BEGIN
  IF NOT private.tacresq_current_auth_session_present_read() THEN
    RAISE EXCEPTION 'Current Admin session should pass the read-only session predicate';
  END IF;
  IF auth.uid() IS DISTINCT FROM '00000000-0000-4000-8000-000000000201'::uuid THEN
    RAISE EXCEPTION 'Admin audit RLS fixture has unexpected auth.uid(): %', auth.uid();
  END IF;
  IF private.tacresq_actor_role() IS DISTINCT FROM 'admin' THEN
    RAISE EXCEPTION 'Admin audit RLS fixture did not resolve active Admin role: %', private.tacresq_actor_role();
  END IF;
  IF NOT private.tacresq_current_account_active() THEN
    RAISE EXCEPTION 'Admin audit RLS fixture failed the current-account policy';
  END IF;
  IF auth.jwt() ->> 'aal' IS DISTINCT FROM 'aal2' THEN
    RAISE EXCEPTION 'Admin audit RLS fixture has unexpected AAL: %', auth.jwt() ->> 'aal';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM jsonb_array_elements(coalesce(auth.jwt() -> 'amr', '[]'::jsonb)) AS method(value)
    WHERE method.value ->> 'method' = 'totp'
  ) THEN
    RAISE EXCEPTION 'Admin audit RLS fixture is missing TOTP AMR: %', auth.jwt() -> 'amr';
  END IF;
  SELECT count(*) INTO v_visible_audit_rows
    FROM public.admin_audit_logs
   WHERE reason = 'Disposable audit fixture for session revocation policy'
     AND actor_user_id = (
       SELECT id FROM public.profiles
        WHERE auth_user_id = '00000000-0000-4000-8000-000000000201'
     );
  IF v_visible_audit_rows <> 1 THEN
    RAISE EXCEPTION 'A current Admin session should retain direct audit-history access (visible_rows %, uid %, role %, aal %, amr %, session_present %, active %, current_user %, select_grant %)',
      v_visible_audit_rows, auth.uid(), private.tacresq_actor_role(), auth.jwt() ->> 'aal',
      auth.jwt() -> 'amr', private.tacresq_current_auth_session_present_read(),
      private.tacresq_current_account_active(), current_user,
      has_table_privilege(current_user, 'public.admin_audit_logs', 'SELECT');
  END IF;

  -- A syntactically valid session UUID owned by another user must not pass.
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000201","session_id":"00000000-0000-4000-8000-000000000301","aal":"aal2","amr":[{"method":"totp"}]}',
    true
  );
  IF private.tacresq_current_auth_session_present_read() THEN
    RAISE EXCEPTION 'A different user''s session UUID must not authenticate this Admin';
  END IF;
  v_rejected := false;
  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(v_target_profile_id, 2, NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'An Admin RPC must reject a session UUID owned by another user';
  END IF;

  -- Missing, malformed, and all-zero session claims fail closed without a cast error.
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000201","aal":"aal2","amr":[{"method":"totp"}]}',
    true
  );
  IF private.tacresq_current_auth_session_present_read() THEN
    RAISE EXCEPTION 'A missing session_id claim must fail closed';
  END IF;
  v_rejected := false;
  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(v_target_profile_id, 2, NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'A missing session_id claim must fail closed';
  END IF;
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000201","session_id":"not-a-uuid","aal":"aal2","amr":[{"method":"totp"}]}',
    true
  );
  IF private.tacresq_current_auth_session_present_read() THEN
    RAISE EXCEPTION 'A malformed session_id claim must fail closed';
  END IF;
  v_rejected := false;
  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(v_target_profile_id, 2, NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'A malformed session_id claim must fail closed';
  END IF;
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000201","session_id":"00000000-0000-0000-0000-000000000000","aal":"aal2","amr":[{"method":"totp"}]}',
    true
  );
  IF private.tacresq_current_auth_session_present_read() THEN
    RAISE EXCEPTION 'An all-zero session_id claim must fail closed';
  END IF;
  v_rejected := false;
  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(v_target_profile_id, 2, NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'An all-zero session_id claim must fail closed';
  END IF;
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000201","session_id":"00000000-0000-4000-8000-000000000306","aal":"aal2","amr":[{"method":"totp"}]}',
    true
  );

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
    FROM jsonb_array_elements(v_page_one -> 'items') AS elements(item)
    CROSS JOIN LATERAL jsonb_object_keys(elements.item) AS keys(key_name)
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
  IF v_page_one -> 'items' -> 0 ->> 'last_refreshed_at' NOT LIKE '%+00:00' THEN
    RAISE EXCEPTION 'UTC Auth refreshed_at timestamp must be normalized to an offset-aware JSON timestamp';
  END IF;

  -- A genuinely empty target is distinguishable from a failed source.
  v_page_two := public.api_admin_list_auth_sessions_page(v_empty_target_profile_id, 2, NULL, NULL);
  IF jsonb_array_length(v_page_two -> 'items') <> 0
     OR v_page_two ->> 'has_more' <> 'false'
     OR v_page_two ->> 'source_status' <> 'available' THEN
    RAISE EXCEPTION 'Empty session source must return an available, empty page: %', v_page_two;
  END IF;

  v_rejected := false;
  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(v_target_profile_id, 51, NULL, NULL);
  EXCEPTION WHEN invalid_parameter_value THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'Session inventory must reject page sizes above 50';
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
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000204', true);
  PERFORM set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-4000-8000-000000000204","session_id":"00000000-0000-4000-8000-000000000307","aal":"aal2","amr":[{"method":"totp"}]}',
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
    '{"sub":"00000000-0000-4000-8000-000000000203","session_id":"00000000-0000-4000-8000-000000000308","aal":"aal2","amr":[{"method":"totp"}]}',
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

-- Delete the synthetic Admin Auth session as the database owner, then prove
-- the same still-shaped AAL2/TOTP claims cannot read or invoke Admin surfaces.
RESET ROLE;
DELETE FROM auth.sessions
 WHERE id = '00000000-0000-4000-8000-000000000309';
SET LOCAL ROLE authenticated;
SELECT set_config(
  'request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000201',
  true
);
SELECT set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-4000-8000-000000000201","session_id":"00000000-0000-4000-8000-000000000309","aal":"aal2","amr":[{"method":"totp"}]}',
  true
);

DO $revoked_session_contract$
DECLARE
  v_rejected boolean := false;
  v_visible_audit_rows bigint;
BEGIN
  IF private.tacresq_current_auth_session_present_read() THEN
    RAISE EXCEPTION 'A deleted Auth session row must fail the read-only session predicate';
  END IF;

  SELECT count(*) INTO v_visible_audit_rows
    FROM public.admin_audit_logs
   WHERE reason = 'Disposable audit fixture for session revocation policy'
     AND actor_user_id = (
       SELECT id FROM public.profiles
        WHERE auth_user_id = '00000000-0000-4000-8000-000000000201'
     );
  IF v_visible_audit_rows <> 0 THEN
    RAISE EXCEPTION 'A revoked Admin session must lose direct audit-history access';
  END IF;

  BEGIN
    PERFORM public.api_admin_list_auth_sessions_page(
      current_setting('test.session_inventory_target_id')::bigint, 2, NULL, NULL
    );
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'A revoked Admin session must be denied by protected Admin RPCs';
  END IF;
END;
$revoked_session_contract$;

ROLLBACK;
