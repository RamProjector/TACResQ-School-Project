BEGIN;

-- These backend-only append-only tables are written by trusted server RPCs.
-- The service_role needs USAGE/SELECT for identity-backed inserts, but no
-- client role and no service-role caller needs sequence UPDATE.
REVOKE ALL PRIVILEGES ON SEQUENCE
  public.device_security_events_id_seq,
  public.device_integrity_checks_id_seq
FROM PUBLIC, anon, authenticated, service_role;

GRANT USAGE, SELECT ON SEQUENCE
  public.device_security_events_id_seq,
  public.device_integrity_checks_id_seq
TO service_role;

COMMIT;
