BEGIN;

-- The privileged dispatcher progress implementation schema-qualifies every
-- application/auth reference. It does not need public or auth on its lookup
-- path; keep SECURITY DEFINER name resolution confined to pg_catalog.
ALTER FUNCTION private.tacresq_dispatch_update_incident_status(bigint, text)
  SET search_path = pg_catalog;

COMMIT;
