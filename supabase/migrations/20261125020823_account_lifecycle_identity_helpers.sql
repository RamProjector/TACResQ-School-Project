BEGIN;

-- Role-based RLS and dispatch paths must not resolve a suspended caller to a
-- usable role. The share lock serializes those operations with a future
-- account-state transition that updates the same profile row.
CREATE OR REPLACE FUNCTION private.tacresq_actor_role()
RETURNS text
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_role text;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN NULL;
  END IF;

  SELECT p.role
    INTO v_role
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  RETURN v_role;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_actor_role()
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_actor_role()
  TO authenticated;

-- Incident-chat and Storage policies use this identity resolver. Return no
-- profile for a suspended caller so upload/read policy checks fail closed.
CREATE OR REPLACE FUNCTION private.tacresq_auth_profile_id()
RETURNS bigint
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_profile_id bigint;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN NULL;
  END IF;

  SELECT p.id
    INTO v_profile_id
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  RETURN v_profile_id;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_auth_profile_id()
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_auth_profile_id()
  TO authenticated;

COMMIT;
