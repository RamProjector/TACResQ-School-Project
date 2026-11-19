BEGIN;

-- These tables are read-only to PostgREST client roles. Token lifecycle changes
-- go through api_register_fcm_token/api_unregister_fcm_token; FCM invalid-token
-- cleanup remains a server-side service_role operation. Location writes go
-- through the assignment-checked private writer behind api_record_location_v2.
-- Incident status history is written by trusted database triggers.

REVOKE ALL PRIVILEGES
  ON TABLE public.fcm_tokens, public.incident_status_logs, public.location_logs
  FROM PUBLIC, anon, authenticated;

GRANT SELECT
  ON TABLE public.fcm_tokens, public.incident_status_logs, public.location_logs
  TO authenticated;

-- State this server-worker permission explicitly so fresh migration replays
-- and existing deployments have the same documented FCM cleanup contract.
GRANT DELETE ON TABLE public.fcm_tokens TO service_role;

COMMENT ON TABLE public.fcm_tokens IS
  'Authenticated clients may read only their own token rows through RLS; token registration/removal uses dedicated RPCs.';

COMMENT ON TABLE public.incident_status_logs IS
  'Append-only incident lifecycle history written by trusted database triggers; clients have RLS-filtered read access only.';

COMMENT ON TABLE public.location_logs IS
  'Responder location writes use the assignment-checked location RPC; clients retain only RLS-filtered read access.';

COMMIT;
