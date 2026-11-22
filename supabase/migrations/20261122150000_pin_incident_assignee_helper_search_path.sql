BEGIN;

-- The assignee predicate is SECURITY DEFINER because incident and assignment
-- RLS policies need to test membership without recursive policy evaluation.
-- Its body already schema-qualifies public.incident_assignments,
-- public.profiles, and auth.uid(), so public is not needed in its lookup path.
-- Keep the helper callable by authenticated policy evaluation, but remove
-- unnecessary application schemas from name resolution.
ALTER FUNCTION private.tacresq_is_incident_assignee(bigint)
  SET search_path = pg_catalog;

COMMIT;
