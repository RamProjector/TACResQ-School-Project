BEGIN;

-- Trigger functions are invoked by PostgreSQL's trigger machinery. Clients do
-- not need EXECUTE on the trigger function to insert/update the protected row.
-- Remove the default PUBLIC execute grant and any direct client/service grants
-- so this trigger-only implementation is not exposed as an RPC surface.
REVOKE ALL ON FUNCTION public.tacresq_require_incident_location()
  FROM PUBLIC, anon, authenticated, service_role;

COMMENT ON FUNCTION public.tacresq_require_incident_location() IS
  'Trigger-only incident input invariant. Direct EXECUTE is revoked from client and service roles; PostgreSQL invokes it through incidents_require_location_before_write.';

COMMIT;
