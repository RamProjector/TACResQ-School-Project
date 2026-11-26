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
-- INSERT. Legacy SECURITY INVOKER incident-creation RPCs (api_create_incident,
-- api_create_incident_v2, and api_create_incident_v3) remain explicitly
-- callable by authenticated clients and INSERT into public.incidents. Their
-- direct callers therefore still require sequence access until those
-- compatibility signatures are retired after client-version review.
-- The legacy location_logs writer is no longer directly callable by clients;
-- its server-side path remains service_role-only.
REVOKE ALL PRIVILEGES ON SEQUENCE
  public.incidents_id_seq,
  public.location_logs_id_seq
FROM PUBLIC, anon;
REVOKE ALL PRIVILEGES ON SEQUENCE
  public.location_logs_id_seq
FROM authenticated;
REVOKE UPDATE ON SEQUENCE
  public.incidents_id_seq,
  public.location_logs_id_seq
FROM authenticated, service_role;
REVOKE SELECT ON SEQUENCE public.incidents_id_seq FROM authenticated;

-- Preserve only the sequence capabilities required by the remaining writers.
GRANT USAGE ON SEQUENCE public.incidents_id_seq TO authenticated;

GRANT USAGE, SELECT ON SEQUENCE
  public.api_operation_logs_id_seq,
  public.incidents_id_seq,
  public.location_logs_id_seq
TO service_role;

-- Do not revoke authenticated access to incidents_id_seq while v1/v2/v3
-- remain authenticated-callable SECURITY INVOKER functions. Their access is
-- bounded to USAGE/SELECT (no UPDATE/setval); retire the RPC grants separately
-- only after supported-client compatibility and lifecycle-guard review.

COMMIT;
