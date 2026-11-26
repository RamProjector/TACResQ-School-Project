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
-- INSERT. Two legacy service-role invoker RPCs also still allocate incident
-- and location IDs; preserve their minimum sequence access until retirement.
GRANT USAGE, SELECT ON SEQUENCE
  public.api_operation_logs_id_seq,
  public.incidents_id_seq,
  public.location_logs_id_seq
TO service_role;

-- Preserve the two legacy service-role invoker RPC paths until they are
-- explicitly retired: api_create_incident/v1-v3 can INSERT incidents, and
-- api_record_location can INSERT location_logs. They are not in the current
-- Android/Render bridge allowlist, but remain callable by service_role today.
-- Their sequence privilege is narrowed to USAGE/SELECT, never UPDATE/setval.

COMMIT;
