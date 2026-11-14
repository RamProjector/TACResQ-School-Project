-- Verify that one installation token follows the currently authenticated account.
-- This test runs only against the disposable PostgreSQL database used by migration replay.
BEGIN;

DO $token_rpc_privileges$
DECLARE
  register_oid regprocedure := to_regprocedure('public.api_register_fcm_token(text)');
  function_settings text[];
BEGIN
  IF register_oid IS NULL THEN
    RAISE EXCEPTION 'FCM registration RPC is missing';
  END IF;
  IF NOT has_function_privilege('authenticated', register_oid, 'EXECUTE')
     OR has_function_privilege('anon', register_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'FCM registration RPC must be authenticated-only';
  END IF;
  IF (SELECT prosecdef FROM pg_proc WHERE oid = register_oid) IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'FCM registration ownership transfer must use its constrained SECURITY DEFINER function';
  END IF;
  SELECT proconfig INTO function_settings FROM pg_proc WHERE oid = register_oid;
  IF NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'FCM registration RPC must pin search_path to pg_catalog';
  END IF;
END;
$token_rpc_privileges$;

INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('f2000000-1111-4111-8111-000000000201', 'fcm-owner-one@example.test',
   '{"name":"FCM Owner One","phone":"+63917000201"}'),
  ('f2000000-1111-4111-8111-000000000202', 'fcm-owner-two@example.test',
   '{"name":"FCM Owner Two","phone":"+63917000202"}');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'f2000000-1111-4111-8111-000000000201', true);
SELECT public.api_register_fcm_token('fcm-installation-token-account-switch-000000000201');

SELECT set_config('request.jwt.claim.sub', 'f2000000-1111-4111-8111-000000000202', true);
SELECT public.api_register_fcm_token('fcm-installation-token-account-switch-000000000201');

-- The RPC must reject a missing authenticated identity.
SELECT set_config('request.jwt.claim.sub', '', true);
DO $token_rpc_requires_auth$
BEGIN
  BEGIN
    PERFORM public.api_register_fcm_token('fcm-installation-token-account-switch-000000000201');
    RAISE EXCEPTION 'Unauthenticated FCM token registration unexpectedly succeeded';
  EXCEPTION WHEN SQLSTATE '42501' THEN
    NULL;
  END;
END;
$token_rpc_requires_auth$;
RESET ROLE;

DO $token_owner_assertion$
DECLARE
  expected_profile_id bigint;
  token_owner_count integer;
  actual_profile_id bigint;
BEGIN
  SELECT id INTO expected_profile_id
    FROM public.profiles
   WHERE auth_user_id = 'f2000000-1111-4111-8111-000000000202';

  SELECT count(*), min(profile_id)
    INTO token_owner_count, actual_profile_id
    FROM public.fcm_tokens
   WHERE token = 'fcm-installation-token-account-switch-000000000201';

  IF expected_profile_id IS NULL
     OR token_owner_count <> 1
     OR actual_profile_id <> expected_profile_id THEN
    RAISE EXCEPTION 'FCM installation token must move to the latest authenticated profile; rows %, owner %, expected %',
      token_owner_count, actual_profile_id, expected_profile_id;
  END IF;
END;
$token_owner_assertion$;

ROLLBACK;
SELECT 'PASS: FCM installation token ownership follows the authenticated account without exposing client table access.' AS result;
