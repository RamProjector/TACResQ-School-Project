BEGIN;

-- Preserve the existing dispatcher assignment implementation as a private
-- internal routine. The new public entry point serializes against lifecycle
-- transitions by locking and rechecking the active dispatcher profile first.
ALTER FUNCTION public.api_assign_incident_team(bigint, bigint[]) SET SCHEMA private;
ALTER FUNCTION private.api_assign_incident_team(bigint, bigint[])
  RENAME TO tacresq_assign_incident_team_internal;
REVOKE ALL ON FUNCTION private.tacresq_assign_incident_team_internal(bigint, bigint[])
  FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.api_assign_incident_team(
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

  RETURN private.tacresq_assign_incident_team_internal(p_id, p_user_ids);
END;
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
