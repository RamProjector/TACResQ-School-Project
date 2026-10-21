-- Restrict authenticated application RPCs from the anonymous role and PUBLIC.
-- Keep authenticated access explicit; authorization checks inside each function remain
-- defense in depth, not a substitute for correct EXECUTE grants.

REVOKE EXECUTE ON FUNCTION public.api_all_incidents() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_all_incidents() TO authenticated;

REVOKE EXECUTE ON FUNCTION public.api_assign_incident(bigint, bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_assign_incident(bigint, bigint) TO authenticated;

REVOKE EXECUTE ON FUNCTION public.api_create_incident(text, text, double precision, double precision, real) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_create_incident(text, text, double precision, double precision, real) TO authenticated;

REVOKE EXECUTE ON FUNCTION public.api_me() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_me() TO authenticated;

REVOKE EXECUTE ON FUNCTION public.api_my_incidents() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_my_incidents() TO authenticated;

REVOKE EXECUTE ON FUNCTION public.api_register_fcm_token(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_register_fcm_token(text) TO authenticated;

REVOKE EXECUTE ON FUNCTION public.api_set_duty_status(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_set_duty_status(text) TO authenticated;

REVOKE EXECUTE ON FUNCTION public.api_unregister_fcm_token(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_unregister_fcm_token(text) TO authenticated;

REVOKE EXECUTE ON FUNCTION public.api_update_incident_status(bigint, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_update_incident_status(bigint, text) TO authenticated;

-- Clients need only the table operations used by the RLS-protected API. They must not
-- have schema-management-adjacent table privileges such as TRUNCATE, TRIGGER, or REFERENCES.
REVOKE TRUNCATE, REFERENCES, TRIGGER
  ON TABLE public.profiles, public.incidents, public.location_logs,
           public.incident_status_logs, public.fcm_tokens
  FROM PUBLIC, anon, authenticated;
