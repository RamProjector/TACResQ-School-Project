BEGIN;
REVOKE ALL ON FUNCTION public.api_admin_set_account_status(bigint, text, text) FROM PUBLIC, anon, authenticated;
DROP FUNCTION public.api_admin_set_account_status(bigint, text, text);

CREATE OR REPLACE FUNCTION public.api_admin_set_account_status(
  p_target_profile_id bigint,
  p_expected_account_status text,
  p_account_status text,
  p_reason text
)
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_actor_id bigint;
  v_actor_name text;
  v_actor_email text;
  v_target public.profiles%ROWTYPE;
  v_reason text := btrim(coalesce(p_reason, ''));
  v_audit_id bigint;
  v_audit_created_at timestamptz;
  v_action text;
BEGIN
  -- The same transaction-scoped advisory lock is taken by the Admin role
  -- mutation wrapper before it validates MFA and changes a target profile.
  PERFORM pg_advisory_xact_lock(hashtext('tacresq-admin-account-lifecycle'));
  PERFORM private.tacresq_require_admin_mfa();

  SELECT p.id, p.name, p.email
    INTO v_actor_id, v_actor_name, v_actor_email
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.role = 'admin'
     AND p.account_status = 'active'
   FOR SHARE;

  IF v_actor_id IS NULL THEN
    RAISE EXCEPTION 'Active administrator access required'
      USING ERRCODE = '42501';
  END IF;

  IF p_target_profile_id IS NULL OR p_target_profile_id < 1
     OR p_expected_account_status NOT IN ('active', 'suspended')
     OR p_expected_account_status IS NULL
     OR p_account_status NOT IN ('active', 'suspended')
     OR p_account_status IS NULL THEN
    RAISE EXCEPTION 'Account lifecycle target or status is invalid'
      USING ERRCODE = '22023';
  END IF;
  IF length(v_reason) NOT BETWEEN 10 AND 500 THEN
    RAISE EXCEPTION 'Account lifecycle reason must be between 10 and 500 characters'
      USING ERRCODE = '22023';
  END IF;
  IF p_target_profile_id = v_actor_id THEN
    RAISE EXCEPTION 'Administrators cannot change their own account status'
      USING ERRCODE = '42501';
  END IF;

  SELECT p.*
    INTO v_target
    FROM public.profiles p
   WHERE p.id = p_target_profile_id
   FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Target account was not found'
      USING ERRCODE = 'P0002';
  END IF;

  -- This workflow manages ordinary TACResQ accounts only. Administrator
  -- provisioning/recovery remains a separate trusted-operator boundary.
  IF v_target.role = 'admin' THEN
    RAISE EXCEPTION 'Administrator accounts cannot be changed through this workflow'
      USING ERRCODE = '42501';
  END IF;

  IF v_target.account_status IS DISTINCT FROM p_expected_account_status THEN
    RAISE EXCEPTION 'Account status changed since it was reviewed; refresh before retrying'
      USING ERRCODE = '40001';
  END IF;

  IF v_target.account_status = p_account_status THEN
    RETURN jsonb_build_object(
      'status', 'unchanged',
      'target_profile_id', v_target.id,
      'account_status', v_target.account_status
    );
  END IF;

  IF p_account_status = 'suspended' THEN
    v_action := 'account_suspended';
    UPDATE public.profiles
       SET account_status = 'suspended',
           suspended_at = clock_timestamp(),
           suspended_by = v_actor_id,
           suspension_reason = v_reason
     WHERE id = v_target.id;
  ELSE
    v_action := 'account_reactivated';
    UPDATE public.profiles
       SET account_status = 'active',
           suspended_at = NULL,
           suspended_by = NULL,
           suspension_reason = NULL
     WHERE id = v_target.id;
  END IF;

  INSERT INTO public.account_lifecycle_audit_logs (
    actor_profile_id,
    actor_identity,
    target_profile_id,
    action,
    previous_status,
    new_status,
    reason
  ) VALUES (
    v_actor_id,
    left(coalesce(nullif(btrim(v_actor_name), ''), nullif(btrim(v_actor_email), ''),
      'Admin profile #' || v_actor_id::text), 200),
    v_target.id,
    v_action,
    v_target.account_status,
    p_account_status,
    v_reason
  )
  RETURNING id, created_at
    INTO v_audit_id, v_audit_created_at;

  RETURN jsonb_build_object(
    'status', 'changed',
    'action', v_action,
    'target_profile_id', v_target.id,
    'account_status', p_account_status,
    'audit_id', v_audit_id,
    'changed_at', v_audit_created_at
  );
END;
$function$;


REVOKE ALL ON FUNCTION public.api_admin_set_account_status(bigint, text, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.api_admin_set_account_status(bigint, text, text, text) TO authenticated;
COMMENT ON FUNCTION public.api_admin_set_account_status(bigint, text, text, text) IS
  'Website Admin lifecycle transition with expected-status compare-and-set under target row lock; AAL2/TOTP, active Admin, and atomic audit required.';
COMMIT;
