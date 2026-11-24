-- Verify that one installation token follows the currently authenticated account.
-- This test runs only against the disposable PostgreSQL database used by migration replay.
BEGIN;

DO $token_rpc_privileges$
DECLARE
  register_oid regprocedure := to_regprocedure('public.api_register_fcm_token(text)');
  function_settings text[];
  unregister_oid regprocedure := to_regprocedure('public.api_unregister_fcm_token(text)');
  unregister_settings text[];
BEGIN
  IF register_oid IS NULL THEN
    RAISE EXCEPTION 'FCM registration RPC is missing';
  END IF;
  IF unregister_oid IS NULL THEN
    RAISE EXCEPTION 'FCM unregistration RPC is missing';
  END IF;
  IF NOT has_function_privilege('authenticated', unregister_oid, 'EXECUTE')
     OR has_function_privilege('anon', unregister_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'FCM unregistration RPC must be authenticated-only';
  END IF;
  IF has_table_privilege('authenticated', 'public.fcm_tokens', 'DELETE')
     OR has_table_privilege('anon', 'public.fcm_tokens', 'DELETE') THEN
    RAISE EXCEPTION 'FCM self-cleanup must not reopen direct client table DELETE';
  END IF;
  IF (SELECT prosecdef FROM pg_proc WHERE oid = unregister_oid) IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'FCM self-cleanup RPC must use its narrowly scoped SECURITY DEFINER boundary';
  END IF;
  SELECT proconfig INTO unregister_settings FROM pg_proc WHERE oid = unregister_oid;
  IF NOT (coalesce(unregister_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'FCM unregistration RPC must pin search_path to pg_catalog';
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

-- A previously associated account must not be able to unregister a token after
-- that installation has moved to another authenticated profile.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'f2000000-1111-4111-8111-000000000201', true);
SELECT public.api_unregister_fcm_token('fcm-installation-token-account-switch-000000000201');
RESET ROLE;

DO $cross_account_cleanup_denied$
DECLARE
  expected_profile_id bigint;
BEGIN
  SELECT id INTO expected_profile_id
    FROM public.profiles
   WHERE auth_user_id = 'f2000000-1111-4111-8111-000000000202';

  IF expected_profile_id IS NULL OR NOT EXISTS (
    SELECT 1
      FROM public.fcm_tokens
     WHERE token = 'fcm-installation-token-account-switch-000000000201'
       AND profile_id = expected_profile_id
  ) THEN
    RAISE EXCEPTION 'An account that no longer owns an installation token must not unregister the current owner token';
  END IF;
END;
$cross_account_cleanup_denied$;

-- Suspend the second account and exercise the same still-valid JWT. Registration,
-- direct reads, and direct writes must fail/return no rows, while self-cleanup
-- remains available so sign-out can remove this installation's token.
UPDATE public.profiles
   SET account_status = 'suspended',
       suspended_at = statement_timestamp(),
       suspended_by = (
         SELECT id FROM public.profiles
          WHERE auth_user_id = 'f2000000-1111-4111-8111-000000000201'
       ),
       suspension_reason = 'Synthetic FCM lifecycle test'
 WHERE auth_user_id = 'f2000000-1111-4111-8111-000000000202';

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'f2000000-1111-4111-8111-000000000202', true);

-- Even if a caller can set the custom transaction flag, direct table DELETE
-- remains unavailable. Only the scoped definer RPC owns the cleanup boundary.
SELECT set_config('tacresq.allow_suspended_fcm_self_cleanup', 'on', true);
DO $suspended_direct_delete_denied$
BEGIN
  BEGIN
    DELETE FROM public.fcm_tokens
     WHERE token = 'fcm-installation-token-account-switch-000000000201';
    RAISE EXCEPTION 'Suspended account unexpectedly deleted an FCM token directly';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
END;
$suspended_direct_delete_denied$;

DO $suspended_registration_denied$
BEGIN
  BEGIN
    PERFORM public.api_register_fcm_token('fcm-suspended-registration-token-000000000202');
    RAISE EXCEPTION 'Suspended account unexpectedly registered an FCM token';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
END;
$suspended_registration_denied$;

DO $suspended_direct_read_denied$
DECLARE
  visible_count bigint;
BEGIN
  SELECT count(*) INTO visible_count
    FROM public.fcm_tokens
   WHERE token = 'fcm-installation-token-account-switch-000000000201';
  IF visible_count <> 0 THEN
    RAISE EXCEPTION 'Suspended account unexpectedly read its FCM token row';
  END IF;
END;
$suspended_direct_read_denied$;

DO $suspended_direct_update_denied$
DECLARE
  changed_count bigint;
BEGIN
  BEGIN
    UPDATE public.fcm_tokens
       SET updated_at = statement_timestamp()
     WHERE token = 'fcm-installation-token-account-switch-000000000201';
    GET DIAGNOSTICS changed_count = ROW_COUNT;
    IF changed_count <> 0 THEN
      RAISE EXCEPTION 'Suspended account unexpectedly updated an FCM token row';
    END IF;
  EXCEPTION WHEN insufficient_privilege THEN
    -- No UPDATE grant is also a valid denial; do not require an RLS zero-row
    -- result when the table privilege boundary rejects the statement first.
    NULL;
  END;
END;
$suspended_direct_update_denied$;

DO $suspended_direct_write_denied$
BEGIN
  BEGIN
    INSERT INTO public.fcm_tokens(profile_id, token)
    VALUES (
      (SELECT private.tacresq_auth_profile_id_for_token_cleanup()),
      'fcm-suspended-direct-insert-token-000000000202'
    );
    RAISE EXCEPTION 'Suspended account unexpectedly inserted an FCM token directly';
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN
    NULL;
  END;
END;
$suspended_direct_write_denied$;

DO $suspended_self_cleanup$
BEGIN
  PERFORM public.api_unregister_fcm_token('fcm-installation-token-account-switch-000000000201');
END;
$suspended_self_cleanup$;
RESET ROLE;

DO $suspended_token_cleanup_assertion$
BEGIN
  IF current_setting('tacresq.allow_suspended_fcm_self_cleanup', true) = 'on' THEN
    RAISE EXCEPTION 'FCM cleanup transaction marker must be cleared after the scoped DELETE';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.fcm_tokens
     WHERE token = 'fcm-installation-token-account-switch-000000000201'
  ) THEN
    RAISE EXCEPTION 'Suspended account must be able to remove its own FCM token';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.fcm_tokens
     WHERE token IN (
       'fcm-suspended-registration-token-000000000202',
       'fcm-suspended-direct-insert-token-000000000202'
     )
  ) THEN
    RAISE EXCEPTION 'Suspended account must not create or rotate FCM tokens';
  END IF;
END;
$suspended_token_cleanup_assertion$;


-- The trusted delivery worker uses a different RPC and a service-role JWT.
-- Its cleanup is restricted to the profile/token pair returned by the claim.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'f2000000-1111-4111-8111-000000000202', true);
SELECT public.api_register_fcm_token('fcm-server-cleanup-token-account-switch-000000000202');
SELECT public.api_register_fcm_token('fcm-server-cleanup-survivor-token-account-switch-000000000202');
RESET ROLE;

DO $resolve_service_cleanup_profile$
DECLARE
  recipient_profile_id bigint;
BEGIN
  SELECT id INTO recipient_profile_id
    FROM public.profiles
   WHERE auth_user_id = 'f2000000-1111-4111-8111-000000000202';
  IF recipient_profile_id IS NULL THEN
    RAISE EXCEPTION 'Expected service-cleanup recipient profile';
  END IF;
  PERFORM set_config('tacresq.test_fcm_cleanup_profile_id', recipient_profile_id::text, true);
END;
$resolve_service_cleanup_profile$;

SET LOCAL ROLE service_role;
SELECT public.api_unregister_fcm_token_for_delivery(
  current_setting('tacresq.test_fcm_cleanup_profile_id')::bigint,
  'fcm-server-cleanup-token-account-switch-000000000202'
);
RESET ROLE;

DO $service_cleanup_exact_pair_assertion$
BEGIN
  IF EXISTS (
    SELECT 1 FROM public.fcm_tokens
     WHERE token = 'fcm-server-cleanup-token-account-switch-000000000202'
  ) THEN
    RAISE EXCEPTION 'Service-role delivery cleanup did not remove the exact claimed token';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.fcm_tokens
     WHERE token = 'fcm-server-cleanup-survivor-token-account-switch-000000000202'
       AND profile_id = (
         SELECT id FROM public.profiles
          WHERE auth_user_id = 'f2000000-1111-4111-8111-000000000202'
       )
  ) THEN
    RAISE EXCEPTION 'Service-role cleanup for one token must not remove another token for the same profile';
  END IF;
END;
$service_cleanup_exact_pair_assertion$;

ROLLBACK;
SELECT 'PASS: FCM installation token ownership follows the authenticated account without exposing client table access.' AS result;
