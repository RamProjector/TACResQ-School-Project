BEGIN;

-- These legacy invoker signatures are not usable client entry points:
-- authenticated has no direct INSERT on public.incidents, and the current
-- Android/Laravel callers use api_create_incident_v4 instead.
-- Keep the signatures for catalog/history compatibility, but remove direct
-- execution from all API roles. The function owner retains implicit EXECUTE.
REVOKE ALL PRIVILEGES ON FUNCTION
  public.api_create_incident(text, text, double precision, double precision, real)
FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL PRIVILEGES ON FUNCTION
  public.api_create_incident_v2(text, text, double precision, double precision, real, text, timestamptz)
FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL PRIVILEGES ON FUNCTION
  public.api_create_incident_v3(text, text, double precision, double precision, real, text, timestamptz, text)
FROM PUBLIC, anon, authenticated, service_role;

COMMENT ON FUNCTION public.api_create_incident(text, text, double precision, double precision, real) IS
  'Legacy requester-create signature retained for history only; client and service-role EXECUTE revoked. Use api_create_incident_v4.';
COMMENT ON FUNCTION public.api_create_incident_v2(text, text, double precision, double precision, real, text, timestamptz) IS
  'Legacy requester-create signature retained for history only; client and service-role EXECUTE revoked. Use api_create_incident_v4.';
COMMENT ON FUNCTION public.api_create_incident_v3(text, text, double precision, double precision, real, text, timestamptz, text) IS
  'Internal implementation called by SECURITY DEFINER api_create_incident_v4; do not grant EXECUTE to API roles.';

COMMIT;
