BEGIN;

-- A deliberately data-free RPC for checking the PostgREST -> PostgreSQL
-- execution path. It reads no application tables and cannot mutate state.
CREATE OR REPLACE FUNCTION public.api_dependency_health()
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT 'tacresq_database_ok'::text;
$function$;

REVOKE ALL PRIVILEGES
  ON FUNCTION public.api_dependency_health()
  FROM PUBLIC, anon, authenticated, service_role;

-- The result is a fixed literal. No application data or privileged operation
-- is exposed, so the health probe can use the publishable key without a
-- Supabase secret/service-role key.
GRANT EXECUTE
  ON FUNCTION public.api_dependency_health()
  TO anon, authenticated;

COMMENT ON FUNCTION public.api_dependency_health() IS
  'Read-only, data-free dependency probe for PostgREST-to-PostgreSQL execution.';

COMMIT;
