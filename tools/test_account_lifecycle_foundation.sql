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
  IF public.api_me() IS NOT NULL THEN
    RAISE EXCEPTION 'Suspended caller must not resolve an active profile through api_me';
  END IF;
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
  BEGIN
    PERFORM public.api_send_incident_message(
      1, 'Suspended idempotent text retry must fail',
      '00000000-0000-4000-8000-000000000981'::uuid
    );
    RAISE EXCEPTION 'Suspended caller unexpectedly replayed a text-message operation';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  BEGIN
    PERFORM public.api_reserve_incident_image_upload(
      1, 'image/jpeg', 4,
      '00000000-0000-4000-8000-000000000982'::uuid
    );
    RAISE EXCEPTION 'Suspended caller unexpectedly replayed an image reservation';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  BEGIN
    PERFORM public.api_send_incident_image_message(
      1, '', '1/1/00000000-0000-4000-8000-000000000983.jpg',
      'image/jpeg', 4, '00000000-0000-4000-8000-000000000983'::uuid
    );
    RAISE EXCEPTION 'Suspended caller unexpectedly replayed an image-message operation';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  BEGIN
    PERFORM public.api_create_incident_v4(
      'medical', 'Suspended SOS must fail', 14.0, 121.0, 5.0,
      'test location', clock_timestamp(), 'gps',
      '00000000-0000-4000-8000-000000000984'::uuid
    );
    RAISE EXCEPTION 'Suspended requester unexpectedly replayed or created an SOS';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  BEGIN
    PERFORM public.api_cancel_my_incident(1);
    RAISE EXCEPTION 'Suspended requester unexpectedly accessed incident cancellation';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
  IF public.api_claim_incident(1)->>'code' IS DISTINCT FROM 'UNAUTHORIZED' THEN
    RAISE EXCEPTION 'Suspended caller must not enter the incident claim workflow';
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

-- Use a real field-unit role for the claim-path negative test. A requester
-- would receive an empty claim queue even while active, which could mask a
-- broken suspended-account guard.
SELECT set_config('request.jwt.claim.sub', '', false);
SELECT set_config('request.jwt.claims', '', false);
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000996',
  'account-lifecycle-responder-996@example.test',
  '{"name":"Suspended Responder Contract","phone":"+63917000996","role":"responder"}'::jsonb
);
UPDATE public.profiles
   SET role = 'responder',
       duty_status = 'available',
       account_status = 'suspended',
       suspended_at = clock_timestamp(),
       suspended_by = id,
       suspension_reason = 'Synthetic suspended responder fixture'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000996';
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000996', false);
SET ROLE authenticated;
DO $suspended_responder_claim_contract$
BEGIN
  IF public.api_claimable_incidents() IS DISTINCT FROM '[]'::jsonb THEN
    RAISE EXCEPTION 'Suspended responder unexpectedly received claimable incident data';
  END IF;
  IF public.api_claim_incident(1)->>'code' IS DISTINCT FROM 'UNAUTHORIZED' THEN
    RAISE EXCEPTION 'Suspended responder unexpectedly entered the incident claim workflow';
  END IF;
END;
$suspended_responder_claim_contract$;
RESET ROLE;

-- Invalid lifecycle combinations must be rejected by the table constraint.
SELECT set_config('request.jwt.claim.sub', '', false);
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

-- Reactivation is also a trusted fixture write; restore the same subject for
-- the post-commit active-state assertion.
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
  BEGIN
    PERFORM public.api_admin_list_account_lifecycle_events(5, 0);
    RAISE EXCEPTION 'Suspended Admin unexpectedly read account lifecycle history';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
END;
$suspended_admin_mfa_contract$;
RESET ROLE;

-- A suspended dispatcher must not receive the field-unit directory through
-- the SECURITY INVOKER RPC or its active-aware role helper.
SELECT set_config('request.jwt.claim.sub', '', false);
SELECT set_config('request.jwt.claims', '', false);
UPDATE public.profiles
   SET role = 'dispatcher',
       duty_status = NULL,
       account_status = 'suspended',
       suspended_at = clock_timestamp(),
       suspended_by = id,
       suspension_reason = 'Synthetic suspended dispatcher fixture'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000991', false);
SET ROLE authenticated;
DO $suspended_dispatcher_directory_contract$
BEGIN
  BEGIN
    PERFORM public.api_units();
    RAISE EXCEPTION 'Suspended dispatcher unexpectedly passed api_units';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
END;
$suspended_dispatcher_directory_contract$;
RESET ROLE;

-- The transition function is implemented but remains unavailable to client roles
-- until the complete callable/Storage/bridge release gate is closed.
DO $transition_rpc_exposure$
BEGIN
  IF has_function_privilege('authenticated', 'public.api_admin_set_account_status(bigint, text, text)', 'EXECUTE')
     OR has_function_privilege('anon', 'public.api_admin_set_account_status(bigint, text, text)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Account lifecycle transition RPC must remain unexposed before the release gate';
  END IF;
END;
$transition_rpc_exposure$;

-- Synthetic active Admin and target profiles exercise atomic transition logic.
SELECT set_config('request.jwt.claim.sub', '', false);
SELECT set_config('request.jwt.claims', '', false);
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000993', 'account-lifecycle-target-993@example.test',
   '{"name":"Lifecycle Target","phone":"+63917000993","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000994', 'account-lifecycle-rollback-994@example.test',
   '{"name":"Lifecycle Rollback Target","phone":"+63917000994","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000995', 'account-lifecycle-admin-995@example.test',
   '{"name":"Lifecycle Active Admin","phone":"+63917000995","role":"requester"}'::jsonb);
UPDATE public.profiles
   SET role = 'responder', duty_status = 'available'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000993';
UPDATE public.profiles
   SET role = 'admin', duty_status = NULL
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000995';

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000995', false);
SELECT set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-4000-8000-000000000995","aal":"aal2","amr":[{"method":"totp"}]}',
  false
);

DO $atomic_account_transition_contract$
DECLARE
  actor_id bigint;
  target_id bigint;
  admin_target_id bigint;
  response jsonb;
  current_status text;
  current_duty_status text;
  audit_count bigint;
  denied boolean;
BEGIN
  SELECT id INTO actor_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000995';
  SELECT id INTO target_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000993';
  SELECT id INTO admin_target_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000992';

  response := public.api_admin_set_account_status(
    target_id, 'suspended', 'Synthetic account suspension reason'
  );
  IF response->>'status' IS DISTINCT FROM 'changed'
     OR response->>'account_status' IS DISTINCT FROM 'suspended'
     OR response->>'audit_id' IS NULL THEN
    RAISE EXCEPTION 'Admin suspension transition returned an unexpected result: %', response;
  END IF;

  SELECT account_status, duty_status
    INTO current_status, current_duty_status
    FROM public.profiles WHERE id = target_id;
  IF current_status IS DISTINCT FROM 'suspended'
     OR current_duty_status IS DISTINCT FROM 'available' THEN
    RAISE EXCEPTION 'Suspension must preserve the target field unit duty status';
  END IF;

  SELECT count(*) INTO audit_count
    FROM public.account_lifecycle_audit_logs
   WHERE target_profile_id = target_id;
  IF audit_count <> 1 THEN
    RAISE EXCEPTION 'Suspension and audit insertion must commit exactly one event';
  END IF;

  response := public.api_admin_set_account_status(
    target_id, 'suspended', 'Synthetic account suspension reason'
  );
  SELECT count(*) INTO audit_count
    FROM public.account_lifecycle_audit_logs
   WHERE target_profile_id = target_id;
  IF response->>'status' IS DISTINCT FROM 'unchanged' OR audit_count <> 1 THEN
    RAISE EXCEPTION 'Repeated suspension must be idempotent and must not duplicate audit events';
  END IF;

  response := public.api_admin_set_account_status(
    target_id, 'active', 'Synthetic account reactivation reason'
  );
  SELECT account_status, duty_status
    INTO current_status, current_duty_status
    FROM public.profiles WHERE id = target_id;
  SELECT count(*) INTO audit_count
    FROM public.account_lifecycle_audit_logs
   WHERE target_profile_id = target_id;
  IF response->>'status' IS DISTINCT FROM 'changed'
     OR current_status IS DISTINCT FROM 'active'
     OR current_duty_status IS DISTINCT FROM 'available'
     OR audit_count <> 2
     OR EXISTS (
       SELECT 1 FROM public.profiles
        WHERE id = target_id
          AND (suspended_at IS NOT NULL OR suspended_by IS NOT NULL OR suspension_reason IS NOT NULL)
     ) THEN
    RAISE EXCEPTION 'Reactivation must clear current suspension metadata and preserve duty state';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_set_account_status(
      actor_id, 'suspended', 'Synthetic self-suspension reason'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'An Admin must not suspend their own account';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_set_account_status(
      admin_target_id, 'active', 'Synthetic Admin reactivation reason'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Administrator accounts must remain outside ordinary account suspension management';
  END IF;
END;
$atomic_account_transition_contract$;

CREATE OR REPLACE FUNCTION public.test_reject_account_lifecycle_audit_insert()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $test$
BEGIN
  RAISE EXCEPTION 'Synthetic lifecycle audit insertion failure'
    USING ERRCODE = 'P0001';
END;
$test$;
CREATE TRIGGER test_reject_account_lifecycle_audit_insert
  BEFORE INSERT ON public.account_lifecycle_audit_logs
  FOR EACH ROW EXECUTE FUNCTION public.test_reject_account_lifecycle_audit_insert();

DO $audit_failure_rollback_contract$
DECLARE
  rollback_target_id bigint;
  current_status text;
  denied boolean := false;
BEGIN
  SELECT id INTO rollback_target_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000994';
  BEGIN
    PERFORM public.api_admin_set_account_status(
      rollback_target_id, 'suspended', 'Synthetic rollback contract reason'
    );
  EXCEPTION WHEN raise_exception THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Synthetic audit failure did not abort the transition';
  END IF;

  SELECT account_status INTO current_status
    FROM public.profiles WHERE id = rollback_target_id;
  IF current_status IS DISTINCT FROM 'active'
     OR EXISTS (
       SELECT 1 FROM public.account_lifecycle_audit_logs
        WHERE target_profile_id = rollback_target_id
     ) THEN
    RAISE EXCEPTION 'Account state must roll back when its audit insert fails';
  END IF;
END;
$audit_failure_rollback_contract$;

DROP TRIGGER test_reject_account_lifecycle_audit_insert
  ON public.account_lifecycle_audit_logs;
DROP FUNCTION public.test_reject_account_lifecycle_audit_insert();


SELECT set_config('request.jwt.claim.sub', '', false);
SELECT set_config('request.jwt.claims', '', false);
DELETE FROM auth.users
 WHERE id IN (
   '00000000-0000-4000-8000-000000000991',
   '00000000-0000-4000-8000-000000000992',
   '00000000-0000-4000-8000-000000000993',
   '00000000-0000-4000-8000-000000000994',
   '00000000-0000-4000-8000-000000000995',
   '00000000-0000-4000-8000-000000000996'
 );


-- The dispatcher progress helper is a directly callable private definer
-- implementation behind public.api_update_incident_status. Locking an active
-- dispatcher profile is part of its runtime authorization contract.
DO $dispatcher_progress_contract$
DECLARE
  routine_oid oid := to_regprocedure('private.tacresq_dispatch_update_incident_status(bigint,text)');
  routine_source text;
  routine_settings text[];
  routine_volatility "char";
  routine_is_definer boolean;
BEGIN
  IF routine_oid IS NULL THEN
    RAISE EXCEPTION 'Private dispatcher progress implementation is missing';
  END IF;
  SELECT p.prosrc, p.proconfig, p.provolatile, p.prosecdef
    INTO routine_source, routine_settings, routine_volatility, routine_is_definer
    FROM pg_catalog.pg_proc p
   WHERE p.oid = routine_oid;
  IF routine_is_definer IS DISTINCT FROM true
     OR routine_volatility IS DISTINCT FROM 'v'
     OR NOT (coalesce(routine_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR routine_source NOT ILIKE '%account_status = ''active''%'
     OR routine_source NOT ILIKE '%FOR SHARE%'
     OR routine_source NOT ILIKE '%Only active dispatchers can update incident progress%' THEN
    RAISE EXCEPTION 'Dispatcher progress helper must lock an active dispatcher under a VOLATILE, pinned SECURITY DEFINER body';
  END IF;
  IF NOT has_function_privilege('authenticated', routine_oid, 'EXECUTE')
     OR has_function_privilege('anon', routine_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Dispatcher progress helper must remain authenticated-only';
  END IF;
END;
$dispatcher_progress_contract$;


-- Team assignment, assignment release, and location recording are callable
-- SECURITY DEFINER boundaries. Their original implementations remain private
-- internal routines; only active-profile-locking wrappers retain client grants.
DO $active_assignment_location_entry_contract$
DECLARE
  v_signature text;
  v_oid oid;
  v_source text;
  v_config text[];
  v_volatility "char";
  v_definer boolean;
  required_routines text[] := ARRAY[
    'public.api_assign_incident_team(bigint,bigint[])',
    'private.tacresq_release_incident_assignment(bigint,bigint)',
    'private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)'
  ];
  internal_routines text[] := ARRAY[
    'private.tacresq_assign_incident_team_internal(bigint,bigint[])',
    'private.tacresq_release_incident_assignment_internal(bigint,bigint)',
    'private.tacresq_record_location_v2_internal(bigint,double precision,double precision,real,timestamp with time zone)'
  ];
BEGIN
  FOREACH v_signature IN ARRAY required_routines LOOP
    v_oid := to_regprocedure(v_signature);
    IF v_oid IS NULL THEN
      RAISE EXCEPTION 'Expected guarded callable is missing: %', v_signature;
    END IF;
    SELECT p.prosrc, p.proconfig, p.provolatile, p.prosecdef
      INTO v_source, v_config, v_volatility, v_definer
      FROM pg_catalog.pg_proc p
     WHERE p.oid = v_oid;
    IF v_definer IS DISTINCT FROM true
       OR v_volatility IS DISTINCT FROM 'v'
       OR NOT (coalesce(v_config, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
       OR v_source NOT ILIKE '%account_status = ''active''%'
       OR v_source NOT ILIKE '%FOR SHARE%' THEN
      RAISE EXCEPTION 'Callable % must be VOLATILE, pinned SECURITY DEFINER with an active-profile share lock', v_signature;
    END IF;
    IF NOT has_function_privilege('authenticated', v_oid, 'EXECUTE')
       OR has_function_privilege('anon', v_oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'Guarded callable % must be authenticated-only', v_signature;
    END IF;
  END LOOP;

  FOREACH v_signature IN ARRAY internal_routines LOOP
    v_oid := to_regprocedure(v_signature);
    IF v_oid IS NULL
       OR has_function_privilege('authenticated', v_oid, 'EXECUTE')
       OR has_function_privilege('anon', v_oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'Internal implementation must exist and be inaccessible to client roles: %', v_signature;
    END IF;
  END LOOP;

  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_proc p
     WHERE p.oid = to_regprocedure('public.api_assign_incident(bigint,bigint)')
       AND p.prosrc ILIKE '%api_assign_incident_team%'
  ) THEN
    RAISE EXCEPTION 'Legacy single-unit assignment alias must route through guarded team assignment';
  END IF;
END;
$active_assignment_location_entry_contract$;


-- Dispatcher directory and queue reads must not disclose operational roster
-- data to a suspended dispatcher using an otherwise-valid JWT.
DO $active_dispatcher_read_contract$
DECLARE
  v_oid oid;
  v_source text;
  v_config text[];
  v_volatility "char";
  v_definer boolean;
BEGIN
  v_oid := to_regprocedure('public.api_units()');
  IF v_oid IS NULL THEN RAISE EXCEPTION 'Guarded field-unit directory RPC is missing'; END IF;
  SELECT prosrc, proconfig, provolatile, prosecdef
    INTO v_source, v_config, v_volatility, v_definer
    FROM pg_catalog.pg_proc WHERE oid = v_oid;
  IF v_definer IS DISTINCT FROM true
     OR v_volatility IS DISTINCT FROM 'v'
     OR NOT (coalesce(v_config, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR v_source NOT ILIKE '%account_status = ''active''%'
     OR v_source NOT ILIKE '%FOR SHARE%'
     OR v_source NOT ILIKE '%tacresq_api_units_internal%' THEN
    RAISE EXCEPTION 'Field-unit directory must be a VOLATILE pinned definer with active dispatcher lock and private delegation';
  END IF;
  IF NOT has_function_privilege('authenticated', v_oid, 'EXECUTE')
     OR has_function_privilege('anon', v_oid, 'EXECUTE')
     OR has_function_privilege('authenticated', to_regprocedure('private.tacresq_api_units_internal()'), 'EXECUTE')
     OR has_function_privilege('anon', to_regprocedure('private.tacresq_api_units_internal()'), 'EXECUTE') THEN
    RAISE EXCEPTION 'Directory RPC grants must expose only the guarded wrapper to authenticated users';
  END IF;

  v_oid := to_regprocedure('public.api_all_incidents()');
  IF v_oid IS NULL THEN RAISE EXCEPTION 'Dispatcher queue RPC is missing'; END IF;
  SELECT prosrc, proconfig, provolatile, prosecdef
    INTO v_source, v_config, v_volatility, v_definer
    FROM pg_catalog.pg_proc WHERE oid = v_oid;
  IF v_definer IS DISTINCT FROM false
     OR v_volatility IS DISTINCT FROM 's'
     OR v_source NOT ILIKE '%tacresq_actor_role()%'
     OR v_source NOT ILIKE '%only active dispatchers can view the queue%' THEN
    RAISE EXCEPTION 'Dispatcher queue must use the active-aware role helper as a stable invoker';
  END IF;
  IF NOT has_function_privilege('authenticated', v_oid, 'EXECUTE')
     OR has_function_privilege('anon', v_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Dispatcher queue RPC must be authenticated-only';
  END IF;
END;
$active_dispatcher_read_contract$;


-- Dispatcher team assignment must expose only the active-state wrapper.
DO $team_assignment_catalog_contract$
DECLARE
  wrapper_oid oid := to_regprocedure('public.api_assign_incident_team(bigint,bigint[])');
  legacy_oid oid := to_regprocedure('public.api_assign_incident(bigint,bigint)');
  internal_oid oid := to_regprocedure('private.tacresq_assign_incident_team_internal(bigint,bigint[])');
  wrapper_source text;
  wrapper_settings text[];
BEGIN
  IF wrapper_oid IS NULL OR legacy_oid IS NULL OR internal_oid IS NULL THEN
    RAISE EXCEPTION 'Team assignment lifecycle wrapper, alias, or internal routine is missing';
  END IF;
  SELECT p.prosrc, p.proconfig INTO wrapper_source, wrapper_settings
    FROM pg_catalog.pg_proc p WHERE p.oid = wrapper_oid;
  IF (SELECT NOT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = wrapper_oid)
     OR NOT (coalesce(wrapper_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR wrapper_source NOT ILIKE '%account_status = ''active''%'
     OR wrapper_source NOT ILIKE '%FOR SHARE%'
     OR wrapper_source NOT ILIKE '%tacresq_assign_incident_team_internal%' THEN
    RAISE EXCEPTION 'Team assignment wrapper must be a pinned VOLATILE definer with active-profile share lock';
  END IF;
  IF NOT has_function_privilege('authenticated', wrapper_oid, 'EXECUTE')
     OR has_function_privilege('anon', wrapper_oid, 'EXECUTE')
     OR has_function_privilege('authenticated', internal_oid, 'EXECUTE')
     OR has_function_privilege('anon', internal_oid, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', legacy_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Team assignment wrapper grants or internal implementation grants are unsafe';
  END IF;
  IF (SELECT prosecdef FROM pg_catalog.pg_proc WHERE oid = legacy_oid) THEN
    RAISE EXCEPTION 'Legacy single-member assignment alias must remain SECURITY INVOKER';
  END IF;
END;
$team_assignment_catalog_contract$;


-- The release entry point must be guarded, and its preserved implementation
-- must not remain directly callable by client roles.
DO $assignment_release_catalog_contract$
DECLARE
  wrapper_oid oid := to_regprocedure('private.tacresq_release_incident_assignment(bigint,bigint)');
  internal_oid oid := to_regprocedure('private.tacresq_release_incident_assignment_internal(bigint,bigint)');
  wrapper_source text;
  wrapper_settings text[];
BEGIN
  IF wrapper_oid IS NULL OR internal_oid IS NULL THEN
    RAISE EXCEPTION 'Assignment release wrapper or internal routine is missing';
  END IF;
  SELECT p.prosrc, p.proconfig INTO wrapper_source, wrapper_settings
    FROM pg_catalog.pg_proc p WHERE p.oid = wrapper_oid;
  IF (SELECT NOT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = wrapper_oid)
     OR NOT (coalesce(wrapper_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR wrapper_source NOT ILIKE '%account_status = ''active''%'
     OR wrapper_source NOT ILIKE '%FOR SHARE%'
     OR wrapper_source NOT ILIKE '%tacresq_release_incident_assignment_internal%' THEN
    RAISE EXCEPTION 'Assignment release must use a pinned active-dispatcher wrapper and share lock';
  END IF;
  IF NOT has_function_privilege('authenticated', wrapper_oid, 'EXECUTE')
     OR has_function_privilege('anon', wrapper_oid, 'EXECUTE')
     OR has_function_privilege('authenticated', internal_oid, 'EXECUTE')
     OR has_function_privilege('anon', internal_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Assignment release wrapper/internal grants are unsafe';
  END IF;
END;
$assignment_release_catalog_contract$;


-- GPS writes must retain the validated implementation behind the active
-- responder/driver lifecycle wrapper.
DO $location_writer_catalog_contract$
DECLARE
  wrapper_oid oid := to_regprocedure('private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamptz)');
  internal_oid oid := to_regprocedure('private.tacresq_record_location_v2_internal(bigint,double precision,double precision,real,timestamptz)');
  wrapper_source text;
  wrapper_settings text[];
BEGIN
  IF wrapper_oid IS NULL OR internal_oid IS NULL THEN
    RAISE EXCEPTION 'GPS writer wrapper or internal routine is missing';
  END IF;
  SELECT p.prosrc, p.proconfig INTO wrapper_source, wrapper_settings
    FROM pg_catalog.pg_proc p WHERE p.oid = wrapper_oid;
  IF (SELECT NOT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = wrapper_oid)
     OR NOT (coalesce(wrapper_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR wrapper_source NOT ILIKE '%account_status = ''active''%'
     OR wrapper_source NOT ILIKE '%FOR SHARE%'
     OR wrapper_source NOT ILIKE '%responder%'
     OR wrapper_source NOT ILIKE '%driver%'
     OR wrapper_source NOT ILIKE '%tacresq_record_location_v2_internal%' THEN
    RAISE EXCEPTION 'GPS writer must require an active responder/driver and lock the profile';
  END IF;
  IF NOT has_function_privilege('authenticated', wrapper_oid, 'EXECUTE')
     OR has_function_privilege('anon', wrapper_oid, 'EXECUTE')
     OR has_function_privilege('authenticated', internal_oid, 'EXECUTE')
     OR has_function_privilege('anon', internal_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'GPS writer wrapper/internal grants are unsafe';
  END IF;
END;
$location_writer_catalog_contract$;


-- The dispatcher directory and queue are separate read boundaries: the
-- directory is a guarded definer wrapper; the queue delegates to the shared
-- active-aware identity helper as an invoker.
DO $dispatcher_read_catalog_contract$
DECLARE
  units_oid oid := to_regprocedure('public.api_units()');
  units_internal_oid oid := to_regprocedure('private.tacresq_api_units_internal()');
  queue_oid oid := to_regprocedure('public.api_all_incidents()');
  units_source text;
  units_settings text[];
  queue_source text;
BEGIN
  IF units_oid IS NULL OR units_internal_oid IS NULL OR queue_oid IS NULL THEN
    RAISE EXCEPTION 'Dispatcher directory or queue routine is missing';
  END IF;
  SELECT p.prosrc, p.proconfig INTO units_source, units_settings
    FROM pg_catalog.pg_proc p WHERE p.oid = units_oid;
  IF (SELECT NOT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = units_oid)
     OR NOT (coalesce(units_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR units_source NOT ILIKE '%account_status = ''active''%'
     OR units_source NOT ILIKE '%FOR SHARE%'
     OR units_source NOT ILIKE '%tacresq_api_units_internal%' THEN
    RAISE EXCEPTION 'Field-unit directory must use a pinned active-dispatcher wrapper';
  END IF;
  SELECT p.prosrc INTO queue_source FROM pg_catalog.pg_proc p WHERE p.oid = queue_oid;
  IF (SELECT prosecdef OR provolatile <> 's' FROM pg_catalog.pg_proc WHERE oid = queue_oid)
     OR queue_source NOT ILIKE '%private.tacresq_actor_role()%'
     OR queue_source NOT ILIKE '%IS DISTINCT FROM ''dispatcher''%' THEN
    RAISE EXCEPTION 'Incident queue must be a stable invoker guarded by the active-aware dispatcher helper';
  END IF;
  IF NOT has_function_privilege('authenticated', units_oid, 'EXECUTE')
     OR has_function_privilege('anon', units_oid, 'EXECUTE')
     OR has_function_privilege('authenticated', units_internal_oid, 'EXECUTE')
     OR has_function_privilege('anon', units_internal_oid, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', queue_oid, 'EXECUTE')
     OR has_function_privilege('anon', queue_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Dispatcher read wrapper/internal grants are unsafe';
  END IF;
END;
$dispatcher_read_catalog_contract$;


-- Dispatch progress is a private SECURITY DEFINER routine invoked through
-- existing trusted call paths. Its own body must re-check and share-lock the
-- active dispatcher profile rather than relying on caller-side authorization.
DO $dispatch_progress_catalog_contract$
DECLARE
  routine_oid oid := to_regprocedure('private.tacresq_dispatch_update_incident_status(bigint,text)');
  routine_source text;
  routine_settings text[];
BEGIN
  IF routine_oid IS NULL THEN
    RAISE EXCEPTION 'Dispatcher progress routine is missing';
  END IF;
  SELECT p.prosrc, p.proconfig INTO routine_source, routine_settings
    FROM pg_catalog.pg_proc p WHERE p.oid = routine_oid;
  IF (SELECT NOT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = routine_oid)
     OR NOT (coalesce(routine_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR routine_source NOT ILIKE '%account_status = ''active''%'
     OR routine_source NOT ILIKE '%FOR SHARE%'
     OR routine_source NOT ILIKE '%Only active dispatchers can update incident progress%' THEN
    RAISE EXCEPTION 'Dispatch progress must re-check an active dispatcher under a pinned share-locking definer';
  END IF;
  IF NOT has_function_privilege('authenticated', routine_oid, 'EXECUTE')
     OR has_function_privilege('anon', routine_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Dispatch progress must be authenticated-only';
  END IF;
END;
$dispatch_progress_catalog_contract$;


-- Notification and claimable-incident SECURITY DEFINER reads must resolve an
-- active identity before returning private account/incident data.
DO $notification_claimable_read_catalog_contract$
DECLARE
  f text;
  expected text[] := ARRAY[
    'public.api_my_notifications(integer)',
    'public.api_my_notification(bigint)',
    'public.api_my_incident_messages(bigint,integer,bigint)',
    'public.api_claimable_incidents()'
  ];
BEGIN
  FOREACH f IN ARRAY expected LOOP
    IF to_regprocedure(f) IS NULL THEN RAISE EXCEPTION 'Lifecycle-guarded read RPC is missing: %', f; END IF;
    IF (SELECT NOT p.prosecdef OR p.provolatile <> 'v'
          OR NOT (coalesce(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
        FROM pg_catalog.pg_proc p WHERE p.oid = to_regprocedure(f)) THEN
      RAISE EXCEPTION 'Read RPC must be a pinned VOLATILE SECURITY DEFINER: %', f;
    END IF;
    IF NOT has_function_privilege('authenticated', to_regprocedure(f), 'EXECUTE')
       OR has_function_privilege('anon', to_regprocedure(f), 'EXECUTE') THEN
      RAISE EXCEPTION 'Read RPC grants must be authenticated-only: %', f;
    END IF;
  END LOOP;
  IF (SELECT count(*) FROM pg_catalog.pg_proc p
       WHERE p.oid = ANY (ARRAY[
         to_regprocedure('public.api_my_notifications(integer)'),
         to_regprocedure('public.api_my_notification(bigint)'),
         to_regprocedure('public.api_my_incident_messages(bigint,integer,bigint)')
       ])
       AND p.prosrc ILIKE '%tacresq_auth_profile_id()%') <> 3 THEN
    RAISE EXCEPTION 'Notification/chat reads must resolve the account-aware profile';
  END IF;
  IF (SELECT prosrc NOT ILIKE '%account_status = ''active''%'
             OR prosrc NOT ILIKE '%FOR SHARE%'
        FROM pg_catalog.pg_proc WHERE oid = to_regprocedure('public.api_claimable_incidents()')) THEN
    RAISE EXCEPTION 'Claimable-incident discovery must lock and require an active profile';
  END IF;
END;
$notification_claimable_read_catalog_contract$;



-- The private assignee helper is directly executable by authenticated users
-- because incident RLS policies call it; it must independently reject suspended
-- profiles and remain unavailable to anon/PUBLIC.
DO $incident_assignee_helper_catalog_contract$
DECLARE
  routine_oid oid := to_regprocedure('private.tacresq_is_incident_assignee(bigint)');
  routine_source text;
  routine_settings text[];
BEGIN
  IF routine_oid IS NULL THEN RAISE EXCEPTION 'Incident assignee helper is missing'; END IF;
  SELECT p.prosrc, p.proconfig INTO routine_source, routine_settings
    FROM pg_catalog.pg_proc p WHERE p.oid = routine_oid;
  IF (SELECT NOT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = routine_oid)
     OR NOT (coalesce(routine_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR routine_source NOT ILIKE '%account_status = ''active''%'
     OR routine_source NOT ILIKE '%FOR SHARE%'
     OR routine_source NOT ILIKE '%auth.uid()%' THEN
    RAISE EXCEPTION 'Assignee helper must be a pinned active-aware SECURITY DEFINER with a profile lock';
  END IF;
  IF NOT has_function_privilege('authenticated', routine_oid, 'EXECUTE')
     OR has_function_privilege('anon', routine_oid, 'EXECUTE')
     OR has_function_privilege('public', routine_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Assignee helper must be authenticated-only';
  END IF;
END;
$incident_assignee_helper_catalog_contract$;



-- Chat send and upload-reservation RPCs must resolve active identity before
-- replaying an idempotency key or reading prior message/reservation state.
DO $chat_idempotency_catalog_contract$
DECLARE
  f text;
  expected text[] := ARRAY[
    'public.api_reserve_incident_image_upload(bigint,text,bigint,uuid)',
    'public.api_send_incident_message(bigint,text,uuid)',
    'public.api_send_incident_image_message(bigint,text,text,text,bigint,uuid)'
  ];
  routine_source text;
  routine_settings text[];
BEGIN
  FOREACH f IN ARRAY expected LOOP
    IF to_regprocedure(f) IS NULL THEN RAISE EXCEPTION 'Chat idempotency RPC is missing: %', f; END IF;
    SELECT p.prosrc, p.proconfig INTO routine_source, routine_settings
      FROM pg_catalog.pg_proc p WHERE p.oid = to_regprocedure(f);
    IF (SELECT NOT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = to_regprocedure(f))
       OR NOT (coalesce(routine_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
       OR routine_source NOT ILIKE '%tacresq_auth_profile_id()%'
       OR routine_source NOT ILIKE '%tacresq_can_access_incident_chat%' THEN
      RAISE EXCEPTION 'Chat RPC must use pinned active-aware identity and chat authorization: %', f;
    END IF;
    IF NOT has_function_privilege('authenticated', to_regprocedure(f), 'EXECUTE')
       OR has_function_privilege('anon', to_regprocedure(f), 'EXECUTE')
       OR has_function_privilege('public', to_regprocedure(f), 'EXECUTE') THEN
      RAISE EXCEPTION 'Chat RPC grants must be authenticated-only: %', f;
    END IF;
  END LOOP;
END;
$chat_idempotency_catalog_contract$;



-- Requester SOS create/retry and cancellation must lock an active requester
-- before returning an idempotent response or mutating incident state.
DO $requester_sos_write_catalog_contract$
DECLARE
  f text;
  expected text[] := ARRAY[
    'public.api_create_incident_v4(text,text,double precision,double precision,real,text,timestamp with time zone,text,uuid)',
    'public.api_cancel_my_incident(bigint)'
  ];
  routine_source text;
  routine_settings text[];
BEGIN
  FOREACH f IN ARRAY expected LOOP
    IF to_regprocedure(f) IS NULL THEN RAISE EXCEPTION 'Requester SOS RPC is missing: %', f; END IF;
    SELECT p.prosrc, p.proconfig INTO routine_source, routine_settings
      FROM pg_catalog.pg_proc p WHERE p.oid = to_regprocedure(f);
    IF (SELECT NOT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = to_regprocedure(f))
       OR NOT (coalesce(routine_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
       OR routine_source NOT ILIKE '%account_status = ''active''%'
       OR routine_source NOT ILIKE '%FOR UPDATE%'
       OR routine_source NOT ILIKE '%requester%' THEN
      RAISE EXCEPTION 'Requester SOS RPC must lock an active requester under a pinned definer: %', f;
    END IF;
    IF NOT has_function_privilege('authenticated', to_regprocedure(f), 'EXECUTE')
       OR has_function_privilege('anon', to_regprocedure(f), 'EXECUTE') THEN
      RAISE EXCEPTION 'Requester SOS RPC grants must be authenticated-only: %', f;
    END IF;
  END LOOP;
END;
$requester_sos_write_catalog_contract$;



-- FCM registration is an active-account write, while unregister is the
-- intentionally narrow self-cleanup path retained for suspended sign-out.
DO $fcm_token_lifecycle_catalog_contract$
DECLARE
  register_oid oid := to_regprocedure('public.api_register_fcm_token(text)');
  unregister_oid oid := to_regprocedure('public.api_unregister_fcm_token(text)');
  cleanup_oid oid := to_regprocedure('private.tacresq_auth_profile_id_for_token_cleanup()');
  register_source text;
  unregister_source text;
  cleanup_source text;
  register_settings text[];
  unregister_settings text[];
BEGIN
  IF register_oid IS NULL OR unregister_oid IS NULL OR cleanup_oid IS NULL THEN
    RAISE EXCEPTION 'FCM registration, unregister, or cleanup identity helper is missing';
  END IF;
  SELECT prosrc, proconfig INTO register_source, register_settings
    FROM pg_catalog.pg_proc WHERE oid = register_oid;
  SELECT prosrc, proconfig INTO unregister_source, unregister_settings
    FROM pg_catalog.pg_proc WHERE oid = unregister_oid;
  SELECT prosrc INTO cleanup_source FROM pg_catalog.pg_proc WHERE oid = cleanup_oid;
  IF (SELECT NOT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = register_oid)
     OR NOT (coalesce(register_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR register_source NOT ILIKE '%account_status = ''active''%'
     OR register_source NOT ILIKE '%FOR SHARE%' THEN
    RAISE EXCEPTION 'FCM registration must require and lock an active profile';
  END IF;
  IF (SELECT prosecdef OR provolatile <> 'v' FROM pg_catalog.pg_proc WHERE oid = unregister_oid)
     OR NOT (coalesce(unregister_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR unregister_source NOT ILIKE '%tacresq_auth_profile_id_for_token_cleanup()%'
     OR unregister_source NOT ILIKE '%DELETE FROM public.fcm_tokens%' THEN
    RAISE EXCEPTION 'FCM unregister must remain an invoker self-cleanup operation';
  END IF;
  IF (SELECT NOT prosecdef OR provolatile <> 's' FROM pg_catalog.pg_proc WHERE oid = cleanup_oid)
     OR cleanup_source NOT ILIKE '%auth.uid()%' THEN
    RAISE EXCEPTION 'FCM cleanup identity helper must remain stable and narrowly scoped';
  END IF;
  IF NOT has_function_privilege('authenticated', register_oid, 'EXECUTE')
     OR has_function_privilege('anon', register_oid, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', unregister_oid, 'EXECUTE')
     OR has_function_privilege('anon', unregister_oid, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', cleanup_oid, 'EXECUTE')
     OR has_function_privilege('anon', cleanup_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'FCM lifecycle routine grants are unsafe';
  END IF;
END;
$fcm_token_lifecycle_catalog_contract$;

