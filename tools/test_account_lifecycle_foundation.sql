-- Runtime contract for the staged account-lifecycle schema and current-state helper.
-- Executed only by the disposable PostgreSQL migration replay harness.
DO $schema_contract$
DECLARE
  helper_oid oid := to_regprocedure('private.tacresq_current_account_active()');
  helper_settings text[];
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

  SELECT p.prosecdef, p.proconfig
    INTO helper_is_definer, helper_settings
    FROM pg_catalog.pg_proc p
   WHERE p.oid = to_regprocedure('private.tacresq_current_account_active()');
  IF helper_is_definer IS DISTINCT FROM true
     OR NOT (coalesce(helper_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Current-account helper must be SECURITY DEFINER with a pinned search_path';
  END IF;
  IF NOT has_function_privilege('authenticated', helper_oid, 'EXECUTE')
     OR has_function_privilege('anon', helper_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Current-account helper must be callable only by authenticated clients';
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

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000991', false);
SET ROLE authenticated;
DO $active_account_contract$
BEGIN
  IF private.tacresq_current_account_active() IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Newly created profile must be active for its authenticated caller';
  END IF;
END;
$active_account_contract$;
RESET ROLE;

-- Trusted fixture setup simulates the future atomic suspension transaction.
-- It deliberately does not call a client-facing suspension RPC.
UPDATE public.profiles
   SET account_status = 'suspended',
       suspended_at = clock_timestamp(),
       suspended_by = id,
       suspension_reason = 'Synthetic suspension contract fixture'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000991';

SET ROLE authenticated;
DO $still_valid_jwt_denial$
BEGIN
  IF private.tacresq_current_account_active() IS DISTINCT FROM false THEN
    RAISE EXCEPTION 'A suspended profile must fail the active-state helper with the same valid JWT';
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

SELECT set_config('request.jwt.claim.sub', '', false);
