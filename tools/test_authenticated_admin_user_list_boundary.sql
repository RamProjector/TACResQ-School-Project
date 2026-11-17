-- Verify the Admin list RPC boundary under the actual authenticated role.
-- Fixtures 128 (admin) and 130 (requester) are provisioned earlier in replay.
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000128', false);

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
    '00000000-0000-4000-8000-000000000130',
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
