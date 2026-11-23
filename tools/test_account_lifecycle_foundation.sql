-- Runtime contract for the staged account-lifecycle schema and current-state helper.
-- Executed only by the disposable PostgreSQL migration replay harness.
DO $schema_contract$
DECLARE
  helper_oid oid := to_regprocedure('private.tacresq_current_account_active()');
  helper_settings text[];
  helper_source text;
  helper_volatility "char";
  helper_is_definer boolean;
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'profiles'
      AND column_name = 'account_status' AND is_nullable = 'NO'
      AND column_default LIKE '%active%'
  ) THEN
    RAISE EXCEPTION 'Profiles must default new accounts to active';
  END IF;
  IF helper_oid IS NULL THEN
    RAISE EXCEPTION 'Current-account active-state helper is missing';
  END IF;

  SELECT p.prosecdef, p.proconfig, p.prosrc, p.provolatile
    INTO helper_is_definer, helper_settings, helper_source, helper_volatility
    FROM pg_catalog.pg_proc p
   WHERE p.oid = to_regprocedure('private.tacresq_current_account_active()');
  IF helper_is_definer IS DISTINCT FROM true
     OR helper_volatility IS DISTINCT FROM 'v'
     OR NOT (coalesce(helper_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR helper_source NOT ILIKE '%FOR SHARE%' THEN
    RAISE EXCEPTION 'Current-account helper must be VOLATILE SECURITY DEFINER, pin search_path, and lock the caller profile FOR SHARE';
  END IF;
  IF NOT has_function_privilege('authenticated', helper_oid, 'EXECUTE')
     OR has_function_privilege('anon', helper_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Current-account helper must be callable only by authenticated clients';
  END IF;

  IF NOT has_function_privilege('authenticated', 'private.tacresq_actor_role()', 'EXECUTE')
     OR has_function_privilege('anon', 'private.tacresq_actor_role()', 'EXECUTE')
     OR NOT has_function_privilege('authenticated', 'private.tacresq_auth_profile_id()', 'EXECUTE')
     OR has_function_privilege('anon', 'private.tacresq_auth_profile_id()', 'EXECUTE') THEN
    RAISE EXCEPTION 'Account-aware identity helpers must remain authenticated-only';
  END IF;
  IF has_function_privilege('authenticated', 'private.tacresq_require_admin_mfa()', 'EXECUTE')
     OR has_function_privilege('anon', 'private.tacresq_require_admin_mfa()', 'EXECUTE') THEN
    RAISE EXCEPTION 'The shared Admin MFA helper must remain internal to protected wrappers';
  END IF;
  IF EXISTS (
    SELECT 1
      FROM pg_catalog.pg_proc p
     WHERE p.oid IN (
       to_regprocedure('private.tacresq_actor_role()'),
       to_regprocedure('private.tacresq_auth_profile_id()')
     )
       AND (
         p.prosecdef IS DISTINCT FROM true
         OR p.provolatile IS DISTINCT FROM 'v'
         OR NOT (coalesce(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
         OR p.prosrc NOT ILIKE '%account_status = ''active''%'
         OR p.prosrc NOT ILIKE '%FOR SHARE%'
       )
  ) THEN
    RAISE EXCEPTION 'Actor-role and profile-ID helpers must enforce active state with a VOLATILE, pinned, share-locking definer body';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_class c
    WHERE c.oid = 'public.account_lifecycle_audit_logs'::regclass
      AND c.relrowsecurity
  ) THEN
    RAISE EXCEPTION 'Account lifecycle audit table must have RLS enabled';
  END IF;
  IF has_table_privilege('authenticated', 'public.account_lifecycle_audit_logs', 'SELECT')
     OR has_table_privilege('authenticated', 'public.account_lifecycle_audit_logs', 'INSERT')
     OR has_table_privilege('authenticated', 'public.account_lifecycle_audit_logs', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.account_lifecycle_audit_logs', 'DELETE')
     OR has_table_privilege('anon', 'public.account_lifecycle_audit_logs', 'SELECT')
     OR has_table_privilege('service_role', 'public.account_lifecycle_audit_logs', 'INSERT') THEN
    RAISE EXCEPTION 'Account lifecycle audit table must not expose client or service-role writes/reads';
  END IF;
  IF has_sequence_privilege('authenticated', 'public.account_lifecycle_audit_logs_id_seq', 'USAGE')
     OR has_sequence_privilege('anon', 'public.account_lifecycle_audit_logs_id_seq', 'USAGE') THEN
    RAISE EXCEPTION 'Account lifecycle audit sequence must not be exposed to client roles';
  END IF;

  IF has_column_privilege('authenticated', 'public.profiles', 'account_status', 'UPDATE')
     OR has_column_privilege('authenticated', 'public.profiles', 'suspended_at', 'UPDATE')
     OR has_column_privilege('authenticated', 'public.profiles', 'suspended_by', 'UPDATE')
     OR has_column_privilege('authenticated', 'public.profiles', 'suspension_reason', 'UPDATE') THEN
    RAISE EXCEPTION 'Authenticated clients must not directly update account lifecycle fields';
  END IF;

  -- No directly granted public table may bypass RLS entirely.
  IF EXISTS (
    SELECT 1
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p')
       AND NOT c.relrowsecurity
       AND (
         has_table_privilege('authenticated', c.oid, 'SELECT')
         OR has_table_privilege('authenticated', c.oid, 'INSERT')
         OR has_table_privilege('authenticated', c.oid, 'UPDATE')
         OR has_table_privilege('authenticated', c.oid, 'DELETE')
         OR has_any_column_privilege('authenticated', c.oid, 'SELECT')
         OR has_any_column_privilege('authenticated', c.oid, 'INSERT')
         OR has_any_column_privilege('authenticated', c.oid, 'UPDATE')
       )
  ) THEN
    RAISE EXCEPTION 'A public table with authenticated privileges has RLS disabled';
  END IF;

  -- Every public table with a direct authenticated grant must have the
  -- restrictive account-state predicate, not merely its original role policy.
  IF EXISTS (
    SELECT 1
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p')
       AND c.relrowsecurity
       AND (
         has_table_privilege('authenticated', c.oid, 'SELECT')
         OR has_table_privilege('authenticated', c.oid, 'INSERT')
         OR has_table_privilege('authenticated', c.oid, 'UPDATE')
         OR has_table_privilege('authenticated', c.oid, 'DELETE')
         OR has_any_column_privilege('authenticated', c.oid, 'SELECT')
         OR has_any_column_privilege('authenticated', c.oid, 'INSERT')
         OR has_any_column_privilege('authenticated', c.oid, 'UPDATE')
       )
       AND NOT EXISTS (
         SELECT 1 FROM pg_catalog.pg_policies p
          WHERE p.schemaname = 'public'
            AND p.tablename = c.relname
            AND p.policyname = 'tacresq_active_account_required'
            AND p.permissive = 'RESTRICTIVE'
            AND p.cmd = 'ALL'
            AND 'authenticated' = ANY(p.roles)
       )
  ) THEN
    RAISE EXCEPTION 'A public table with authenticated privileges is missing the restrictive active-account RLS policy';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p')
       AND NOT EXISTS (
         SELECT 1
           FROM pg_catalog.pg_trigger t
          WHERE t.tgrelid = c.oid
            AND t.tgname = 'tacresq_active_account_write_guard'
            AND t.tgenabled = 'O'
            AND t.tgfoid = to_regprocedure('private.tacresq_reject_suspended_account_write()')
            AND NOT t.tgisinternal
       )
  ) THEN
    RAISE EXCEPTION 'A public table is missing the enabled suspended-account write guard trigger';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_proc p
    WHERE p.oid = to_regprocedure('private.tacresq_reject_suspended_account_write()')
      AND p.prosecdef
      AND p.provolatile = 'v'
      AND coalesce(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']
      AND p.prosrc ILIKE '%private.tacresq_current_account_active()%'
  ) OR has_function_privilege('authenticated', 'private.tacresq_reject_suspended_account_write()', 'EXECUTE')
    OR has_function_privilege('anon', 'private.tacresq_reject_suspended_account_write()', 'EXECUTE') THEN
    RAISE EXCEPTION 'Suspended-account write trigger function must be pinned, privileged, and non-callable by clients';
  END IF;
END;
$schema_contract$;

-- The same, still-valid JWT subject is reused before and after the server-side
-- profile state change. No token refresh, expiry, or revocation is involved.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000991',
  'account-lifecycle-991@example.test',
  '{"name":"Lifecycle Contract","phone":"+63917000991","role":"requester"}'::jsonb
);

-- Disposable SECURITY DEFINER writer simulates a privileged application RPC.
-- It is removed before the replay's general callable-definer inventory runs.
CREATE OR REPLACE FUNCTION public.test_account_lifecycle_definer_write()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $test$
BEGIN
  UPDATE public.profiles
     SET updated_at = clock_timestamp()
   WHERE auth_user_id = auth.uid();
END;
$test$;
REVOKE ALL ON FUNCTION public.test_account_lifecycle_definer_write()
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.test_account_lifecycle_definer_write()
  TO authenticated;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000991', false);
SET ROLE authenticated;
DO $active_account_contract$
DECLARE
  visible_profile_count bigint;
BEGIN
  IF private.tacresq_current_account_active() IS DISTINCT FROM true
     OR private.tacresq_auth_profile_id() IS NULL
     OR private.tacresq_actor_role() IS DISTINCT FROM 'requester' THEN
    RAISE EXCEPTION 'Newly created profile must resolve as active for its authenticated caller';
  END IF;
  SELECT count(*) INTO visible_profile_count
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';
  IF visible_profile_count <> 1 THEN
    RAISE EXCEPTION 'Active-account restrictive RLS must preserve the caller profile read';
  END IF;
  PERFORM public.test_account_lifecycle_definer_write();
END;
$active_account_contract$;
RESET ROLE;

-- Trusted fixture setup simulates the future atomic suspension transaction.
-- Clear only the test connection's claims for the privileged fixture write,
-- then restore the exact same JWT subject without refreshing or revoking it.
SELECT set_config('request.jwt.claim.sub', '', false);
UPDATE public.profiles
   SET account_status = 'suspended',
       suspended_at = clock_timestamp(),
       suspended_by = id,
       suspension_reason = 'Synthetic suspension contract fixture'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000991', false);

SET ROLE authenticated;
DO $still_valid_jwt_denial$
DECLARE
  visible_profile_count bigint;
BEGIN
  IF private.tacresq_current_account_active() IS DISTINCT FROM false
     OR private.tacresq_auth_profile_id() IS NOT NULL
     OR private.tacresq_actor_role() IS NOT NULL THEN
    RAISE EXCEPTION 'A suspended profile must fail all active identity helpers with the same valid JWT';
  END IF;
  BEGIN
    PERFORM public.test_account_lifecycle_definer_write();
    RAISE EXCEPTION 'SECURITY DEFINER table write unexpectedly succeeded for a suspended caller';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  BEGIN
    PERFORM public.api_my_notifications(10);
    RAISE EXCEPTION 'Suspended caller unexpectedly read notification history';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  BEGIN
    PERFORM public.api_my_notification(1);
    RAISE EXCEPTION 'Suspended caller unexpectedly read a notification detail';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  BEGIN
    PERFORM public.api_my_incident_messages(1, 10, NULL);
    RAISE EXCEPTION 'Suspended caller unexpectedly read incident chat history';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  IF public.api_claimable_incidents() IS DISTINCT FROM '[]'::jsonb THEN
    RAISE EXCEPTION 'Suspended field-unit caller must not receive claimable incident data';
  END IF;
  SELECT count(*) INTO visible_profile_count
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';
  IF visible_profile_count <> 0 THEN
    RAISE EXCEPTION 'Restrictive RLS must hide the suspended caller profile while the same JWT remains valid';
  END IF;
  BEGIN
    INSERT INTO public.account_lifecycle_audit_logs (
      actor_profile_id, actor_identity, target_profile_id, action,
      previous_status, new_status, reason
    ) VALUES (
      1, 'Unauthorized fixture', 1, 'account_suspended',
      'active', 'suspended', 'Client must not write audit rows'
    );
    RAISE EXCEPTION 'Authenticated client unexpectedly inserted an account audit row';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
END;
$still_valid_jwt_denial$;
RESET ROLE;

-- Invalid lifecycle combinations must be rejected by the table constraint.
DO $invalid_state_contract$
BEGIN
  BEGIN
    UPDATE public.profiles
       SET suspended_at = NULL
     WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';
    RAISE EXCEPTION 'Suspended state without timestamp must be rejected';
  EXCEPTION WHEN check_violation THEN
    NULL;
  END;
END;
$invalid_state_contract$;

-- Reactivation clears current-state metadata; history remains a separate ledger.
UPDATE public.profiles
   SET account_status = 'active',
       suspended_at = NULL,
       suspended_by = NULL,
       suspension_reason = NULL
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';

SET ROLE authenticated;
DO $reactivation_contract$
BEGIN
  IF private.tacresq_current_account_active() IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Reactivated profile must pass the current-state helper';
  END IF;
END;
$reactivation_contract$;
RESET ROLE;

-- Reactivation is also a trusted fixture write; restore the same subject for
-- the post-commit active-state assertion.
SELECT set_config('request.jwt.claim.sub', '', false);
UPDATE public.profiles
   SET account_status = 'active',
       suspended_at = NULL,
       suspended_by = NULL,
       suspension_reason = NULL
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000991', false);

SET ROLE authenticated;
DO $reactivation_contract$
BEGIN
  IF private.tacresq_current_account_active() IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Reactivated profile must pass the current-state helper';
  END IF;
END;
$reactivation_contract$;
RESET ROLE;

-- Owner-side fixture insertion proves the audit immutability trigger is active.
INSERT INTO public.account_lifecycle_audit_logs (
  actor_profile_id, actor_identity, target_profile_id, action,
  previous_status, new_status, reason
)
SELECT id, 'Synthetic contract actor', id, 'account_suspended',
       'active', 'suspended', 'Synthetic immutable audit contract'
  FROM public.profiles
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';

DO $immutable_audit_contract$
DECLARE
  audit_id bigint;
BEGIN
  SELECT id INTO audit_id
    FROM public.account_lifecycle_audit_logs
   WHERE actor_identity = 'Synthetic contract actor';
  IF audit_id IS NULL THEN
    RAISE EXCEPTION 'Synthetic lifecycle audit row was not created';
  END IF;
  BEGIN
    UPDATE public.account_lifecycle_audit_logs
       SET reason = 'Tampered audit record'
     WHERE id = audit_id;
    RAISE EXCEPTION 'Account lifecycle audit UPDATE unexpectedly succeeded';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  IF NOT EXISTS (
    SELECT 1 FROM public.account_lifecycle_audit_logs
    WHERE id = audit_id AND reason = 'Synthetic immutable audit contract'
  ) THEN
    RAISE EXCEPTION 'Append-only audit row changed after rejected mutation';
  END IF;
END;
$immutable_audit_contract$;

DROP FUNCTION public.test_account_lifecycle_definer_write();

-- A suspended Admin with a still-valid AAL2/TOTP JWT must fail the shared
-- database MFA gate; the UI gate alone is not the authorization boundary.
SELECT set_config('request.jwt.claim.sub', '', false);
SELECT set_config('request.jwt.claims', '', false);
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000992',
  'account-lifecycle-admin-992@example.test',
  '{"name":"Suspended Admin Contract","phone":"+63917000992","role":"requester"}'::jsonb
);
UPDATE public.profiles
   SET role = 'admin',
       duty_status = NULL,
       account_status = 'suspended',
       suspended_at = clock_timestamp(),
       suspended_by = id,
       suspension_reason = 'Synthetic suspended Admin fixture'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000992';

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000992', false);
SELECT set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-4000-8000-000000000992","aal":"aal2","amr":[{"method":"totp"}]}',
  false
);
SET ROLE authenticated;
DO $suspended_admin_mfa_contract$
BEGIN
  BEGIN
    PERFORM public.api_admin_list_users_page(25, 0, '', 'all');
    RAISE EXCEPTION 'Suspended Admin unexpectedly passed the TOTP-protected directory RPC';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
END;
$suspended_admin_mfa_contract$;
RESET ROLE;

SELECT set_config('request.jwt.claim.sub', '', false);
SELECT set_config('request.jwt.claims', '', false);
DELETE FROM auth.users
 WHERE id IN (
   '00000000-0000-4000-8000-000000000991',
   '00000000-0000-4000-8000-000000000992'
 );
