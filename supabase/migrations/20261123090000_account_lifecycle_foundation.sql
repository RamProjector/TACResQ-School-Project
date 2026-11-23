BEGIN;

-- Establish a reversible account lifecycle state without overloading field
-- duty status. Existing accounts remain active; no user/session/data is changed.
ALTER TABLE public.profiles
  ADD COLUMN account_status text NOT NULL DEFAULT 'active',
  ADD COLUMN suspended_at timestamptz,
  ADD COLUMN suspended_by bigint,
  ADD COLUMN suspension_reason text,
  ADD CONSTRAINT profiles_account_status_check
    CHECK (account_status IN ('active', 'suspended')),
  ADD CONSTRAINT profiles_account_suspension_metadata_check
    CHECK (
      (
        account_status = 'active'
        AND suspended_at IS NULL
        AND suspended_by IS NULL
        AND suspension_reason IS NULL
      )
      OR
      (
        account_status = 'suspended'
        AND suspended_at IS NOT NULL
        AND suspended_by IS NOT NULL
        AND suspension_reason IS NOT NULL
        AND length(btrim(suspension_reason)) BETWEEN 10 AND 500
      )
    );

CREATE INDEX profiles_account_status_role_idx
  ON public.profiles (account_status, role, id);

COMMENT ON COLUMN public.profiles.account_status IS
  'Account access lifecycle, independent of role and responder/driver duty status. Active is the default; suspension is not enforced until all protected entry points are guarded.';
COMMENT ON COLUMN public.profiles.suspended_at IS
  'Server timestamp for the current suspension; cleared on reactivation. Historical transitions remain in account_lifecycle_audit_logs.';
COMMENT ON COLUMN public.profiles.suspended_by IS
  'Profile ID snapshot of the administrator who applied the current suspension; cleared on reactivation.';
COMMENT ON COLUMN public.profiles.suspension_reason IS
  'Bounded operator-provided reason for the current suspension; cleared on reactivation and retained in immutable audit history.';

-- Immutable transition history is separate from role-change audit because
-- account lifecycle transitions have different before/after state semantics.
CREATE TABLE public.account_lifecycle_audit_logs (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  correlation_id uuid NOT NULL DEFAULT gen_random_uuid(),
  actor_profile_id bigint NOT NULL,
  actor_identity text NOT NULL
    CHECK (length(btrim(actor_identity)) BETWEEN 1 AND 200),
  target_profile_id bigint NOT NULL,
  action text NOT NULL
    CHECK (action IN ('account_suspended', 'account_reactivated')),
  previous_status text NOT NULL
    CHECK (previous_status IN ('active', 'suspended')),
  new_status text NOT NULL
    CHECK (new_status IN ('active', 'suspended')),
  reason text NOT NULL
    CHECK (length(btrim(reason)) BETWEEN 10 AND 500),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT account_lifecycle_audit_transition_check CHECK (
    (action = 'account_suspended' AND previous_status = 'active' AND new_status = 'suspended')
    OR
    (action = 'account_reactivated' AND previous_status = 'suspended' AND new_status = 'active')
  )
);

CREATE INDEX account_lifecycle_audit_created_idx
  ON public.account_lifecycle_audit_logs (created_at DESC, id DESC);
CREATE INDEX account_lifecycle_audit_target_idx
  ON public.account_lifecycle_audit_logs (target_profile_id, created_at DESC, id DESC);

ALTER TABLE public.account_lifecycle_audit_logs ENABLE ROW LEVEL SECURITY;
REVOKE ALL PRIVILEGES ON TABLE public.account_lifecycle_audit_logs
  FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL PRIVILEGES ON SEQUENCE public.account_lifecycle_audit_logs_id_seq
  FROM PUBLIC, anon, authenticated, service_role;

COMMENT ON TABLE public.account_lifecycle_audit_logs IS
  'Append-only, server-authored record of account suspension/reactivation transitions. No client table or sequence access; state mutation and audit insertion must later be performed by one trusted transaction.';

CREATE OR REPLACE FUNCTION private.tacresq_reject_account_lifecycle_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  RAISE EXCEPTION 'Account lifecycle audit history is append-only'
    USING ERRCODE = '42501';
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_reject_account_lifecycle_audit_mutation()
  FROM PUBLIC, anon, authenticated, service_role;

CREATE TRIGGER tacresq_account_lifecycle_audit_immutable
  BEFORE UPDATE OR DELETE ON public.account_lifecycle_audit_logs
  FOR EACH ROW EXECUTE FUNCTION private.tacresq_reject_account_lifecycle_audit_mutation();

-- This helper is a reusable current-database-state predicate for restrictive
-- RLS policies and RPC guards. It deliberately reads profiles on every call;
-- it does not trust app_metadata, user_metadata, or a stale JWT account claim.
CREATE OR REPLACE FUNCTION private.tacresq_current_account_active()
RETURNS boolean
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_is_active boolean;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN false;
  END IF;

  -- Serialize each protected request with account suspension/reactivation.
  -- A suspension transaction must lock this same profile row FOR UPDATE;
  -- operations that acquire this share lock finish before suspension wins.
  SELECT p.account_status = 'active'
    INTO v_is_active
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
   FOR SHARE;

  RETURN coalesce(v_is_active, false);
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_current_account_active()
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_current_account_active()
  TO authenticated;

-- Deliberately do not expose a suspension RPC or add partial table/Storage
-- policies here. Those are enabled only after every callable and direct-access
-- surface has a same-valid-JWT denial test. Until then all new rows are active
-- and no ordinary client can mutate lifecycle columns.
REVOKE UPDATE (account_status, suspended_at, suspended_by, suspension_reason)
  ON TABLE public.profiles
  FROM PUBLIC, anon, authenticated, service_role;

COMMIT;
