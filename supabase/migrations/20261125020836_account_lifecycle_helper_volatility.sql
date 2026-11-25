BEGIN;

-- The chat authorization wrapper now calls a VOLATILE profile-ID resolver
-- that takes FOR SHARE. Keep the wrapper VOLATILE too so Storage policy checks
-- can serialize with suspension rather than relying on a stable snapshot.
CREATE OR REPLACE FUNCTION private.tacresq_auth_can_access_incident_chat(
  p_incident_id bigint
)
RETURNS boolean
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_actor_profile_id bigint;
BEGIN
  v_actor_profile_id := private.tacresq_auth_profile_id();
  IF v_actor_profile_id IS NULL THEN
    RETURN false;
  END IF;

  RETURN private.tacresq_can_access_incident_chat(
    p_incident_id,
    v_actor_profile_id
  );
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_auth_can_access_incident_chat(bigint)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_auth_can_access_incident_chat(bigint)
  TO authenticated;

-- This SECURITY INVOKER directory RPC uses the active-aware actor-role helper.
-- It must be VOLATILE because that helper takes a row share lock.
CREATE OR REPLACE FUNCTION public.api_units()
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
BEGIN
  IF private.tacresq_actor_role() IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only active dispatchers can view field units'
      USING ERRCODE = '42501';
  END IF;

  RETURN (
    SELECT coalesce(
      jsonb_agg(
        jsonb_build_object(
          'id', p.id,
          'name', p.name,
          'email', p.email,
          'phone', p.phone,
          'role', p.role,
          'duty_status', p.duty_status
        )
        ORDER BY p.name
      ),
      '[]'::jsonb
    )
    FROM public.profiles p
    WHERE p.role IN ('responder', 'driver')
      AND p.account_status = 'active'
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_units() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_units() TO authenticated;

COMMIT;
