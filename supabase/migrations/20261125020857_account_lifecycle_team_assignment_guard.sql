BEGIN;

-- Serialize assignment against account suspension without exposing a
-- SECURITY DEFINER function in the public Data API schema. The original
-- private assignment implementation remains private and independently checks
-- the dispatcher role; this guard additionally requires an active account.
CREATE OR REPLACE FUNCTION private.tacresq_assign_incident_team_guarded(
  p_id bigint,
  p_user_ids bigint[]
)
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
  actor_role text;
BEGIN
  SELECT p.id, p.role
    INTO actor_id, actor_role
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  IF actor_id IS NULL OR actor_role IS DISTINCT FROM 'dispatcher' THEN
    RAISE EXCEPTION 'Only active dispatchers can assign incident teams'
      USING ERRCODE = '42501';
  END IF;

  RETURN private.tacresq_assign_incident_team(p_id, p_user_ids);
END;
$function$;

-- Prevent authenticated clients from bypassing the active-account guard by
-- invoking the underlying implementation directly.
REVOKE ALL ON FUNCTION private.tacresq_assign_incident_team(bigint, bigint[])
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION private.tacresq_assign_incident_team_guarded(bigint, bigint[])
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_assign_incident_team_guarded(bigint, bigint[])
  TO authenticated;

-- SECURITY INVOKER wrappers execute with the caller's schema privileges.
-- USAGE exposes only name resolution; each private function still requires its
-- own explicit EXECUTE grant, and the private schema remains outside the Data API.
GRANT USAGE ON SCHEMA private TO authenticated;

-- Public API functions remain SECURITY INVOKER; only the private helper is
-- privileged and it pins search_path to pg_catalog.
CREATE OR REPLACE FUNCTION public.api_assign_incident_team(
  p_id bigint,
  p_user_ids bigint[]
)
RETURNS jsonb
LANGUAGE sql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT private.tacresq_assign_incident_team_guarded(p_id, p_user_ids);
$function$;

REVOKE ALL ON FUNCTION public.api_assign_incident_team(bigint, bigint[])
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_assign_incident_team(bigint, bigint[])
  TO authenticated;

-- The legacy one-member alias must route through the guarded public entry point.
CREATE OR REPLACE FUNCTION public.api_assign_incident(p_id bigint, p_user_id bigint)
RETURNS jsonb
LANGUAGE sql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT public.api_assign_incident_team(p_id, ARRAY[p_user_id]);
$function$;
REVOKE ALL ON FUNCTION public.api_assign_incident(bigint, bigint)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_assign_incident(bigint, bigint)
  TO authenticated;

COMMIT;
