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

-- Other application identity sequences are consumed only inside database-owned
-- SECURITY DEFINER RPCs, auth bootstrap, or SECURITY DEFINER triggers. The
-- service_role FCM worker only PATCHes delivery attempts and deletes invalid
-- tokens; it does not allocate IDs for those tables. Keep the device verifier,
-- API telemetry, and two legacy invoker-RPC sequence consumers below.
REVOKE ALL PRIVILEGES ON SEQUENCE
  public.fcm_delivery_attempts_id_seq,
  public.fcm_tokens_id_seq,
  public.incident_assignment_events_id_seq,
  public.incident_claimability_logs_id_seq,
  public.incident_messages_id_seq,
  public.incident_request_edit_logs_id_seq,
  public.incident_status_logs_id_seq,
  public.profiles_id_seq,
  public.user_notifications_id_seq
FROM PUBLIC, anon, authenticated, service_role;

-- Laravel's ApiOperationAuditLogger performs a direct service-role PostgREST
-- INSERT. The service_role also retains direct INSERT on incidents/location_logs
-- for the legacy SECURITY INVOKER server paths api_create_incident/v1-v3 and
-- api_record_location. Preserve their minimum sequence access until retirement.
-- The legacy incident v1/v2/v3 functions still have authenticated EXECUTE grants,
-- but authenticated has no direct INSERT on public.incidents; the supported v4
-- SECURITY DEFINER wrapper calls v3 with owner privileges. Therefore clients
-- do not need incidents_id_seq access.
REVOKE ALL PRIVILEGES ON SEQUENCE
  public.incidents_id_seq,
  public.location_logs_id_seq
FROM PUBLIC, anon, authenticated;
REVOKE UPDATE ON SEQUENCE
  public.incidents_id_seq,
  public.location_logs_id_seq
FROM service_role;

-- Preserve only the sequence capabilities required by the remaining server writers.
GRANT USAGE, SELECT ON SEQUENCE
  public.api_operation_logs_id_seq,
  public.incidents_id_seq,
  public.location_logs_id_seq
TO service_role;

-- Do not grant clients sequence access merely because a legacy RPC has EXECUTE:
-- direct table INSERT privilege is a separate gate, and the current requester
-- creation path is the owner-executed v4 wrapper.
COMMIT;
