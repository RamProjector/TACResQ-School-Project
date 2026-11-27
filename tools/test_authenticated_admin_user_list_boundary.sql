-- Verify the Admin list and paginated audit RPC boundaries under the authenticated role.
-- Use fixtures owned by this test: the broader replay suite deliberately deletes
-- its earlier Admin/audit fixtures to verify audit-history retention.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES
  (
    '00000000-0000-4000-8000-000000000145',
    'admin-user-list-boundary@example.test',
    '{"name":"Admin User List Boundary","phone":"+63917000145","role":"requester"}'::jsonb
  ),
  (
    '00000000-0000-4000-8000-000000000146',
    'requester-user-list-boundary@example.test',
    '{"name":"Requester User List Boundary","phone":"+63917000146","role":"requester"}'::jsonb
  );

-- Provision the trusted role as the migration-test database owner, before
-- switching to the authenticated API role.
UPDATE public.profiles
SET role = 'admin', duty_status = NULL
WHERE auth_user_id = '00000000-0000-4000-8000-000000000145';

INSERT INTO auth.sessions (id, user_id)
VALUES (
  '10000000-0000-4000-8000-000000000145',
  '00000000-0000-4000-8000-000000000145'
);

INSERT INTO public.admin_audit_logs (
  actor_user_id, actor_identity, target_user_id, action,
  old_role, new_role, reason
)
SELECT actor.id, 'admin-user-list-boundary@example.test', target.id,
       'user_role_changed', 'requester', 'responder',
       'Audit pagination fixture for authenticated RPC coverage.'
FROM public.profiles actor
CROSS JOIN public.profiles target
WHERE actor.auth_user_id = '00000000-0000-4000-8000-000000000145'
  AND target.auth_user_id = '00000000-0000-4000-8000-000000000146';

-- Capture fixture IDs before switching to the RLS-constrained API role.
SELECT set_config(
  'tacresq.test_admin_profile_id',
  (SELECT id::text FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000145'),
  false
);
SELECT set_config(
  'tacresq.test_target_profile_id',
  (SELECT id::text FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000146'),
  false
);

SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000145', false);
SELECT set_config(
  'request.jwt.claims',
  '{"session_id":"10000000-0000-4000-8000-000000000145","aal":"aal2","amr":[{"method":"password","timestamp":1},{"method":"totp","timestamp":2}]}',
  false
);

DO $admin_user_list_authenticated$
DECLARE
  result jsonb;
  denied boolean := false;
  actor_profile_id bigint;
  target_profile_id bigint;
BEGIN
  actor_profile_id := current_setting('tacresq.test_admin_profile_id')::bigint;
  target_profile_id := current_setting('tacresq.test_target_profile_id')::bigint;

  BEGIN
    PERFORM public.api_admin_list_users();
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated admin must not execute the legacy unbounded user-list RPC';
  END IF;

  result := public.api_admin_list_users_page(25, 0, '', 'all');
  IF jsonb_typeof(result) IS DISTINCT FROM 'object'
     OR jsonb_typeof(result->'users') IS DISTINCT FROM 'array'
     OR jsonb_array_length(result->'users') > 25 THEN
    RAISE EXCEPTION 'Authorized admin pagination did not return a bounded user page: %', result;
  END IF;

  result := public.api_admin_list_audit_logs_page(
    25, 0, 'audit pagination fixture', 'user_role_changed',
    now() - interval '1 day', now() + interval '1 day',
    actor_profile_id, target_profile_id
  );
  IF jsonb_typeof(result) IS DISTINCT FROM 'object'
     OR jsonb_typeof(result->'events') IS DISTINCT FROM 'array'
     OR jsonb_array_length(result->'events') <> 1
     OR (result->>'total_count')::integer <> 1
     OR result->'events'->0->>'reason' NOT LIKE '%Audit pagination fixture%' THEN
    RAISE EXCEPTION 'Authorized audit search/filter did not return the expected event: %', result;
  END IF;

  result := public.api_admin_list_audit_logs_page(
    25, 1, 'audit pagination fixture', 'all', NULL, NULL
  );
  IF jsonb_array_length(result->'events') <> 0
     OR (result->>'total_count')::integer <> 1 THEN
    RAISE EXCEPTION 'Audit offset pagination did not preserve the matching total: %', result;
  END IF;

  denied := false;
  PERFORM set_config(
    'request.jwt.claim.sub',
    '00000000-0000-4000-8000-000000000146',
    false
  );
  PERFORM set_config(
    'request.jwt.claims',
    '{"aal":"aal1","amr":[{"method":"password","timestamp":3}]}',
    false
  );
  BEGIN
    PERFORM public.api_admin_list_users_page(25, 0, '', 'all');
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not execute the Admin paginated user-list RPC';
  END IF;
END;
$admin_user_list_authenticated$;

RESET ROLE;
SELECT set_config('request.jwt.claim.sub', '', false);
