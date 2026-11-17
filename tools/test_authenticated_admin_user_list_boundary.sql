-- Verify the Admin list RPC boundary under the actual authenticated role.
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

SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000145', false);

DO $admin_user_list_authenticated$
DECLARE
  result jsonb;
  denied boolean := false;
BEGIN
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

  denied := false;
  PERFORM set_config(
    'request.jwt.claim.sub',
    '00000000-0000-4000-8000-000000000146',
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
