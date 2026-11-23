BEGIN;

-- Keep the established release transaction intact as an internal routine.
-- The directly callable private wrapper now locks/rechecks the dispatcher
-- profile before delegating to it, so a valid pre-suspension JWT cannot release
-- a field unit or alter duty availability.
ALTER FUNCTION private.tacresq_release_incident_assignment(bigint, bigint)
  RENAME TO tacresq_release_incident_assignment_internal;
REVOKE ALL ON FUNCTION private.tacresq_release_incident_assignment_internal(bigint, bigint)
  FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION private.tacresq_release_incident_assignment(
  p_id bigint,
  p_user_id bigint
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
    RAISE EXCEPTION 'Only active dispatchers can release incident assignments'
      USING ERRCODE = '42501';
  END IF;

  RETURN private.tacresq_release_incident_assignment_internal(p_id, p_user_id);
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_release_incident_assignment(bigint, bigint)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_release_incident_assignment(bigint, bigint)
  TO authenticated;

COMMIT;
