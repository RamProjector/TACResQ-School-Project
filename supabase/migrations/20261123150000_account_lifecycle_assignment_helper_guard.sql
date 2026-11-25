BEGIN;

-- This authenticated-callable SECURITY DEFINER helper participates in incident
-- RLS and is itself directly executable. Do not reveal assignment membership
-- for a suspended caller; acquire the same profile share lock used by the other
-- account-state guards.
CREATE OR REPLACE FUNCTION private.tacresq_is_incident_assignee(p_incident_id bigint)
RETURNS boolean
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_profile_id bigint;
BEGIN
  IF auth.uid() IS NULL OR p_incident_id IS NULL OR p_incident_id < 1 THEN
    RETURN false;
  END IF;

  SELECT p.id
    INTO v_profile_id
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  IF v_profile_id IS NULL THEN
    RETURN false;
  END IF;

  RETURN EXISTS (
    SELECT 1
      FROM public.incident_assignments a
     WHERE a.incident_id = p_incident_id
       AND a.user_id = v_profile_id
  );
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_is_incident_assignee(bigint)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_is_incident_assignee(bigint)
  TO authenticated;

COMMIT;
