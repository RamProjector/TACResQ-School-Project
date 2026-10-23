BEGIN;

-- All referenced relations and auth.uid() are schema-qualified in the private
-- SECURITY DEFINER implementation, so no writable schema needs to be trusted.
ALTER FUNCTION private.tacresq_assign_incident_team(bigint, bigint[])
  SET search_path = pg_catalog;

COMMIT;
