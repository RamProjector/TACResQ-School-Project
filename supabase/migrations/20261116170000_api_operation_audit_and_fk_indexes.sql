BEGIN;

-- Application-level API operation telemetry. This deliberately stores metadata
-- only: no request/response bodies, credentials, tokens, coordinates, URLs,
-- notification content, or incident narratives.
CREATE TABLE public.api_operation_logs (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  correlation_id uuid NOT NULL,
  actor_auth_user_id uuid,
  provider text NOT NULL CHECK (provider IN ('supabase', 'geoapify', 'private_osrm', 'fcm')),
  operation text NOT NULL CHECK (length(operation) BETWEEN 1 AND 100),
  source text NOT NULL CHECK (source IN ('render_api', 'render_worker')),
  outcome text NOT NULL CHECK (outcome IN ('success', 'failure', 'rejected', 'timeout')),
  http_status smallint CHECK (http_status BETWEEN 100 AND 599),
  duration_ms integer NOT NULL CHECK (duration_ms >= 0),
  provider_request_id text CHECK (provider_request_id IS NULL OR length(provider_request_id) <= 255),
  error_code text CHECK (error_code IS NULL OR length(error_code) <= 80),
  created_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE INDEX api_operation_logs_created_idx
  ON public.api_operation_logs (created_at DESC, id DESC);
CREATE INDEX api_operation_logs_provider_operation_idx
  ON public.api_operation_logs (provider, operation, created_at DESC);
CREATE INDEX api_operation_logs_correlation_idx
  ON public.api_operation_logs (correlation_id);

ALTER TABLE public.api_operation_logs ENABLE ROW LEVEL SECURITY;
REVOKE ALL PRIVILEGES ON TABLE public.api_operation_logs FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT, INSERT ON TABLE public.api_operation_logs TO service_role;
REVOKE ALL PRIVILEGES ON SEQUENCE public.api_operation_logs_id_seq FROM PUBLIC, anon, authenticated, service_role;
GRANT USAGE, SELECT ON SEQUENCE public.api_operation_logs_id_seq TO service_role;

-- Cover the four foreign keys identified by the live Supabase performance
-- advisor. Do not remove other indexes based only on low-traffic "unused" flags.
CREATE INDEX fcm_delivery_attempts_profile_id_idx
  ON public.fcm_delivery_attempts (profile_id);
CREATE INDEX incident_assignments_assigned_by_idx
  ON public.incident_assignments (assigned_by);
CREATE INDEX incident_request_edit_logs_requester_id_idx
  ON public.incident_request_edit_logs (requester_id);
CREATE INDEX user_notifications_incident_id_idx
  ON public.user_notifications (incident_id);

COMMIT;
