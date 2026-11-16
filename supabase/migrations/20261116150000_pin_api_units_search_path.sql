-- api_units remains SECURITY DEFINER because its dispatcher-only response
-- intentionally reads responder contact and assignment state under the guarded
-- dispatcher role check. Its relation and auth references are schema-qualified,
-- so public/auth are not required in the function lookup path.
ALTER FUNCTION public.api_units()
  SET search_path = pg_catalog;
