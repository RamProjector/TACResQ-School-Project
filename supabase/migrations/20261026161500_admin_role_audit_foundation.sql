BEGIN;

-- Add the privileged role without granting it to any existing account.
-- Public signup remains requester-only; provisioning the first admin is an
-- explicit trusted-operator action outside the client-facing API.
DO $migration$
DECLARE
  constraint_row record;
  definition text;
BEGIN
  FOR constraint_row IN
    SELECT conname, pg_get_constraintdef(oid) AS definition
    FROM pg_constraint
    WHERE conrelid = 'public.profiles'::regclass
      AND contype = 'c'
  LOOP
    definition := lower(constraint_row.definition);
    IF position('role' IN definition) > 0
       AND position('dispatcher' IN definition) > 0 THEN
      EXECUTE format('ALTER TABLE public.profiles DROP CONSTRAINT %I', constraint_row.conname);
    END IF;
  END LOOP;
END;
$migration$;

ALTER TABLE public.profiles
  ADD CONSTRAINT profiles_role_check
    CHECK (role IN ('requester', 'responder', 'driver', 'dispatcher', 'admin')),
  ADD CONSTRAINT profiles_duty_status_role_check
    CHECK (
      (role IN ('responder', 'driver') AND duty_status IS NOT NULL)
      OR (role IN ('requester', 'dispatcher', 'admin') AND duty_status IS NULL)
    );

-- Append-only administrative audit events. No client can write, update, or
-- delete these rows directly; trusted RPCs insert events transactionally.
CREATE TABLE public.admin_audit_logs (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_user_id bigint REFERENCES public.profiles(id) ON DELETE SET NULL,
  target_user_id bigint REFERENCES public.profiles(id) ON DELETE SET NULL,
  action text NOT NULL CHECK (action IN ('user_role_changed')),
  old_role text NOT NULL CHECK (old_role IN ('requester', 'responder', 'driver', 'dispatcher', 'admin')),
  new_role text NOT NULL CHECK (new_role IN ('requester', 'responder', 'driver', 'dispatcher', 'admin')),
  reason text NOT NULL CHECK (length(trim(reason)) BETWEEN 10 AND 500),
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.admin_audit_logs ENABLE ROW LEVEL SECURITY;
REVOKE ALL PRIVILEGES ON TABLE public.admin_audit_logs FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.admin_audit_logs TO authenticated;
DROP POLICY IF EXISTS admin_audit_logs_read_admin ON public.admin_audit_logs;
CREATE POLICY admin_audit_logs_read_admin ON public.admin_audit_logs
  FOR SELECT TO authenticated
  USING (private.tacresq_actor_role() = 'admin');

-- Role changes made by the protected RPC must pass the existing profile
-- guard. Ordinary users still cannot update another user's profile because
-- the only direct UPDATE policy remains profiles_update_self. Admin RPCs are
-- SECURITY DEFINER and are the only client-facing path for changing another
-- account's role; admin accounts themselves cannot be changed by this RPC.
CREATE OR REPLACE FUNCTION public.tacresq_guard_profile_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_role text;
BEGIN
  IF auth.uid() IS NOT NULL AND pg_trigger_depth() = 1 THEN
    SELECT p.role INTO actor_role
    FROM public.profiles p
    WHERE p.auth_user_id = auth.uid();

    IF NEW.auth_user_id IS DISTINCT FROM OLD.auth_user_id
       OR NEW.name IS DISTINCT FROM OLD.name
       OR NEW.email IS DISTINCT FROM OLD.email
       OR NEW.phone IS DISTINCT FROM OLD.phone THEN
      RAISE EXCEPTION 'Profile identity fields cannot be changed through the application API'
        USING ERRCODE = '42501';
    END IF;

    IF NEW.role IS DISTINCT FROM OLD.role
       AND actor_role IS DISTINCT FROM 'admin' THEN
      RAISE EXCEPTION 'Only an administrator can change account roles'
        USING ERRCODE = '42501';
    END IF;

    IF NEW.role IS DISTINCT FROM OLD.role
       AND (OLD.role = 'admin' OR NEW.role = 'admin') THEN
      RAISE EXCEPTION 'Administrator promotion and demotion require trusted operator provisioning'
        USING ERRCODE = '42501';
    END IF;

    IF NEW.role IN ('responder', 'driver') AND NEW.duty_status IS NULL THEN
      RAISE EXCEPTION 'Field-unit accounts require an explicit duty status'
        USING ERRCODE = '22023';
    ELSIF NEW.role IN ('requester', 'dispatcher', 'admin') AND NEW.duty_status IS NOT NULL THEN
      RAISE EXCEPTION 'This role cannot have a field-unit duty status'
        USING ERRCODE = '22023';
    END IF;

    IF NEW.duty_status IS DISTINCT FROM OLD.duty_status
       AND NEW.role IN ('responder', 'driver')
       AND NEW.duty_status NOT IN ('available', 'off_duty') THEN
      RAISE EXCEPTION 'Invalid user-set duty status'
        USING ERRCODE = '22023';
    END IF;

    IF EXISTS (
      SELECT 1 FROM public.incidents
      WHERE assigned_to = OLD.id
        AND status IN ('assigned', 'en_route', 'on_scene', 'transporting')
    ) AND (NEW.role IS DISTINCT FROM OLD.role OR NEW.duty_status IS DISTINCT FROM OLD.duty_status) THEN
      RAISE EXCEPTION 'Finish the active incident before changing a field unit role or duty status'
        USING ERRCODE = '22023';
    END IF;
  END IF;

  NEW.updated_at := now();
  RETURN NEW;
END;
$function$;
REVOKE ALL ON FUNCTION public.tacresq_guard_profile_update() FROM PUBLIC, anon, authenticated;

-- List only the profile fields needed for role administration. This function
-- deliberately does not return auth tokens, password hashes, or raw metadata.
CREATE OR REPLACE FUNCTION public.api_admin_list_users()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.profiles p
    WHERE p.auth_user_id = auth.uid() AND p.role = 'admin'
  ) THEN
    RAISE EXCEPTION 'Administrator access required' USING ERRCODE = '42501';
  END IF;

  RETURN (
    SELECT coalesce(jsonb_agg(jsonb_build_object(
      'id', p.id,
      'name', p.name,
      'email', p.email,
      'phone', p.phone,
      'role', p.role,
      'duty_status', p.duty_status,
      'created_at', p.created_at
    ) ORDER BY p.created_at DESC, p.id), '[]'::jsonb)
    FROM public.profiles p
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.api_admin_list_users() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_list_users() TO authenticated;

-- Only ordinary operational roles can be assigned through the console.
-- Creating or removing an administrator is intentionally out-of-band.
CREATE OR REPLACE FUNCTION public.api_admin_set_user_role(
  p_user_id bigint,
  p_role text,
  p_reason text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
  target_row public.profiles;
  old_role text;
BEGIN
  SELECT p.id INTO actor_id
  FROM public.profiles p
  WHERE p.auth_user_id = auth.uid() AND p.role = 'admin';

  IF actor_id IS NULL THEN
    RAISE EXCEPTION 'Administrator access required' USING ERRCODE = '42501';
  END IF;

  IF p_user_id IS NULL OR p_role NOT IN ('requester', 'responder', 'driver', 'dispatcher') THEN
    RAISE EXCEPTION 'Unsupported target or role; administrator provisioning is operator-only'
      USING ERRCODE = '22023';
  END IF;

  IF p_reason IS NULL OR length(trim(p_reason)) NOT BETWEEN 10 AND 500 THEN
    RAISE EXCEPTION 'Provide an audit reason between 10 and 500 characters'
      USING ERRCODE = '22023';
  END IF;

  SELECT * INTO target_row
  FROM public.profiles p
  WHERE p.id = p_user_id
  FOR UPDATE;

  IF target_row.id IS NULL THEN
    RAISE EXCEPTION 'User not found' USING ERRCODE = 'P0002';
  END IF;

  IF target_row.role = 'admin' THEN
    RAISE EXCEPTION 'Administrator accounts must be changed by a trusted operator'
      USING ERRCODE = '42501';
  END IF;

  old_role := target_row.role;
  IF old_role = p_role THEN
    RAISE EXCEPTION 'The account already has this role' USING ERRCODE = '22023';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.incidents
    WHERE assigned_to = target_row.id
      AND status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) AND target_row.role IN ('responder', 'driver') THEN
    RAISE EXCEPTION 'Finish the active incident before changing this field unit role'
      USING ERRCODE = '22023';
  END IF;

  UPDATE public.profiles
  SET role = p_role,
      duty_status = CASE WHEN p_role IN ('responder', 'driver') THEN 'off_duty' ELSE NULL END,
      updated_at = now()
  WHERE id = target_row.id;

  INSERT INTO public.admin_audit_logs(actor_user_id, target_user_id, action, old_role, new_role, reason)
  VALUES (actor_id, target_row.id, 'user_role_changed', old_role, p_role, trim(p_reason));

  RETURN jsonb_build_object(
    'id', target_row.id,
    'name', target_row.name,
    'email', target_row.email,
    'old_role', old_role,
    'role', p_role,
    'duty_status', CASE WHEN p_role IN ('responder', 'driver') THEN 'off_duty' ELSE NULL END
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.api_admin_set_user_role(bigint, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_set_user_role(bigint, text, text) TO authenticated;

CREATE OR REPLACE FUNCTION public.api_admin_list_audit_logs(p_limit integer DEFAULT 100)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.profiles p
    WHERE p.auth_user_id = auth.uid() AND p.role = 'admin'
  ) THEN
    RAISE EXCEPTION 'Administrator access required' USING ERRCODE = '42501';
  END IF;

  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 200 THEN
    RAISE EXCEPTION 'Audit page size must be between 1 and 200' USING ERRCODE = '22023';
  END IF;

  RETURN (
    SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY a.created_at DESC, a.id DESC), '[]'::jsonb)
    FROM (
      SELECT id, actor_user_id, target_user_id, action, old_role, new_role, reason, created_at
      FROM public.admin_audit_logs
      ORDER BY created_at DESC, id DESC
      LIMIT p_limit
    ) a
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.api_admin_list_audit_logs(integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_list_audit_logs(integer) TO authenticated;

COMMIT;
