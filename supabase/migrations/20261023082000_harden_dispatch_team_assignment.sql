BEGIN;

-- Keep the Data API wrapper invoker-safe. The privileged implementation lives
-- in the non-exposed private schema and independently checks auth.uid()/role.
ALTER FUNCTION public.api_assign_incident_team(bigint, bigint[]) SET SCHEMA private;
ALTER FUNCTION private.api_assign_incident_team(bigint, bigint[]) RENAME TO tacresq_assign_incident_team;

REVOKE ALL ON FUNCTION private.tacresq_assign_incident_team(bigint, bigint[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_assign_incident_team(bigint, bigint[]) TO authenticated;

CREATE OR REPLACE FUNCTION public.api_assign_incident_team(p_id bigint, p_user_ids bigint[])
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT private.tacresq_assign_incident_team(p_id, p_user_ids);
$function$;
REVOKE ALL ON FUNCTION public.api_assign_incident_team(bigint, bigint[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_assign_incident_team(bigint, bigint[]) TO authenticated;

-- Preserve the legacy single-unit endpoint without exposing a second
-- SECURITY DEFINER entry point in the public API schema.
CREATE OR REPLACE FUNCTION public.api_assign_incident(p_id bigint, p_user_id bigint)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT public.api_assign_incident_team(p_id, ARRAY[p_user_id]);
$function$;
REVOKE ALL ON FUNCTION public.api_assign_incident(bigint, bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_assign_incident(bigint, bigint) TO authenticated;

COMMIT;
