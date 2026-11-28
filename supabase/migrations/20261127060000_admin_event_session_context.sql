BEGIN;

-- A signed PostgREST JWT carries session_id independently of the caller's
-- request body. Capture that claim at the event-table boundary so the Admin
-- session inventory can find activity across all trusted database writers.
-- This is attribution only: it does not assert that the Auth session row is
-- still present or that the access JWT has not expired.
CREATE OR REPLACE FUNCTION private.tacresq_current_auth_session_id()
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_session_id text;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN NULL;
  END IF;

  v_session_id := (SELECT auth.jwt()) ->> 'session_id';
  IF v_session_id IS NULL OR v_session_id !~*
     '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
    RETURN NULL;
  END IF;

  RETURN v_session_id::uuid;
EXCEPTION WHEN OTHERS THEN
  -- Audit attribution must never turn an otherwise valid business write into
  -- an outage if an upstream request context is absent or malformed.
  RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_current_auth_session_id()
  FROM PUBLIC, anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION private.tacresq_stamp_auth_session_id()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  IF NEW.auth_session_id IS NULL THEN
    NEW.auth_session_id := private.tacresq_current_auth_session_id();
  END IF;
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_stamp_auth_session_id()
  FROM PUBLIC, anon, authenticated, service_role;

-- The existing writers remain unchanged. A SECURITY DEFINER BEFORE INSERT
-- trigger stamps the verified JWT claim and preserves any explicit server-
-- verified value already supplied by the Laravel bridge.
ALTER TABLE public.admin_audit_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;
ALTER TABLE public.incident_status_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;
ALTER TABLE public.incident_claimability_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;
ALTER TABLE public.incident_request_edit_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;
ALTER TABLE public.incident_assignment_events
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;
ALTER TABLE public.account_lifecycle_audit_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;
ALTER TABLE public.app_action_audit_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;
ALTER TABLE public.api_operation_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;
ALTER TABLE public.device_security_events
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;

DO $triggers$
DECLARE
  v_table text;
BEGIN
  FOREACH v_table IN ARRAY ARRAY[
    'admin_audit_logs',
    'incident_status_logs',
    'incident_claimability_logs',
    'incident_request_edit_logs',
    'incident_assignment_events',
    'account_lifecycle_audit_logs',
    'app_action_audit_logs',
    'api_operation_logs',
    'device_security_events'
  ] LOOP
    EXECUTE format(
      'DROP TRIGGER IF EXISTS tacresq_stamp_auth_session_id ON public.%I',
      v_table
    );
    EXECUTE format(
      'CREATE TRIGGER tacresq_stamp_auth_session_id
       BEFORE INSERT ON public.%I
       FOR EACH ROW EXECUTE FUNCTION private.tacresq_stamp_auth_session_id()',
      v_table
    );
  END LOOP;
END;
$triggers$;

CREATE INDEX IF NOT EXISTS admin_audit_logs_auth_session_id_idx
  ON public.admin_audit_logs (auth_session_id)
  WHERE auth_session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS incident_status_logs_auth_session_id_idx
  ON public.incident_status_logs (auth_session_id)
  WHERE auth_session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS incident_claimability_logs_auth_session_id_idx
  ON public.incident_claimability_logs (auth_session_id)
  WHERE auth_session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS incident_request_edit_logs_auth_session_id_idx
  ON public.incident_request_edit_logs (auth_session_id)
  WHERE auth_session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS incident_assignment_events_auth_session_id_idx
  ON public.incident_assignment_events (auth_session_id)
  WHERE auth_session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS account_lifecycle_audit_logs_auth_session_id_idx
  ON public.account_lifecycle_audit_logs (auth_session_id)
  WHERE auth_session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS app_action_audit_logs_auth_session_id_idx
  ON public.app_action_audit_logs (auth_session_id)
  WHERE auth_session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS api_operation_logs_auth_session_id_idx
  ON public.api_operation_logs (auth_session_id)
  WHERE auth_session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS device_security_events_auth_session_id_idx
  ON public.device_security_events (auth_session_id)
  WHERE auth_session_id IS NOT NULL;

COMMENT ON FUNCTION private.tacresq_current_auth_session_id() IS
  'Returns the UUID session_id from the verified PostgREST JWT context; NULL for service/system requests or missing/malformed claims. This is event attribution, not a live-session validity check.';
COMMENT ON FUNCTION private.tacresq_stamp_auth_session_id() IS
  'Stamps append-only event rows with the signed JWT session_id when the trusted writer did not already provide a server-verified value.';

COMMIT;
