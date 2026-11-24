BEGIN;

-- Assignment creation must serialize with account suspension even when an
-- older SECURITY DEFINER RPC writes the legacy lead-unit column directly.
-- The profile row lock makes the race deterministic:
--   assignment locks first -> suspension waits and then sees active work;
--   suspension locks first -> assignment waits and then sees suspended state.
CREATE OR REPLACE FUNCTION private.tacresq_require_active_assignment_target()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  target_profile_id bigint;
  target_status text;
BEGIN
  IF TG_TABLE_NAME = 'incident_assignments' THEN
    target_profile_id := NEW.user_id;
  ELSIF TG_TABLE_NAME = 'incidents' THEN
    target_profile_id := NEW.assigned_to;
  ELSE
    RAISE EXCEPTION 'Unsupported assignment target table'
      USING ERRCODE = '22023';
  END IF;

  -- NULL is valid for pending/unassigned incidents and is not a target.
  IF target_profile_id IS NULL THEN
    RETURN NEW;
  END IF;

  SELECT p.account_status
    INTO target_status
    FROM public.profiles p
   WHERE p.id = target_profile_id
   FOR SHARE;

  IF target_status IS DISTINCT FROM 'active' THEN
    RAISE EXCEPTION 'Only active field units can receive incident assignments'
      USING ERRCODE = '55006';
  END IF;

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_require_active_assignment_target()
  FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS tacresq_active_assignment_target_guard
  ON public.incident_assignments;
CREATE TRIGGER tacresq_active_assignment_target_guard
BEFORE INSERT OR UPDATE ON public.incident_assignments
FOR EACH ROW
EXECUTE FUNCTION private.tacresq_require_active_assignment_target();

DROP TRIGGER IF EXISTS tacresq_active_legacy_assignment_target_guard
  ON public.incidents;
CREATE TRIGGER tacresq_active_legacy_assignment_target_guard
BEFORE INSERT OR UPDATE OF assigned_to ON public.incidents
FOR EACH ROW
EXECUTE FUNCTION private.tacresq_require_active_assignment_target();

COMMIT;
