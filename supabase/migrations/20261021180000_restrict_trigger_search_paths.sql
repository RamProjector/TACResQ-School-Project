-- Remove writable schemas from SECURITY DEFINER trigger functions' lookup paths.
-- These bodies schema-qualify application tables and auth.uid(); pg_catalog is sufficient.
ALTER FUNCTION public.tacresq_guard_profile_update()
  SET search_path = pg_catalog;

ALTER FUNCTION public.tacresq_log_incident_status()
  SET search_path = pg_catalog;

ALTER FUNCTION public.tacresq_profile_on_signup()
  SET search_path = pg_catalog;
