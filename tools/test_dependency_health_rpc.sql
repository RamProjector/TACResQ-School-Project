-- Runtime privilege and behavior contract for the data-free dependency probe.
DO $dependency_health_rpc_contract$
DECLARE
  health_rpc regprocedure := to_regprocedure('public.api_dependency_health()');
  function_settings text[];
  function_definition text;
BEGIN
  IF health_rpc IS NULL THEN
    RAISE EXCEPTION 'public.api_dependency_health() must exist';
  END IF;

  IF (SELECT prosecdef FROM pg_proc WHERE oid = health_rpc) IS DISTINCT FROM false THEN
    RAISE EXCEPTION 'Dependency health RPC must be SECURITY INVOKER';
  END IF;

  IF (SELECT provolatile FROM pg_proc WHERE oid = health_rpc) IS DISTINCT FROM 'i' THEN
    RAISE EXCEPTION 'Dependency health RPC must be immutable';
  END IF;

  SELECT proconfig INTO function_settings FROM pg_proc WHERE oid = health_rpc;
  IF NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Dependency health RPC must pin search_path to pg_catalog';
  END IF;

  IF NOT has_function_privilege('anon', health_rpc, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', health_rpc, 'EXECUTE') THEN
    RAISE EXCEPTION 'Publishable-key client roles must be able to execute the data-free health RPC';
  END IF;

  IF has_function_privilege('service_role', health_rpc, 'EXECUTE') THEN
    RAISE EXCEPTION 'Service role must not receive unnecessary health RPC privileges';
  END IF;

  SELECT pg_get_functiondef(health_rpc) INTO function_definition;
  IF function_definition ~* '\\m(from|insert|update|delete|truncate)\\M' THEN
    RAISE EXCEPTION 'Dependency health RPC must not read application tables or mutate state';
  END IF;

  IF public.api_dependency_health() IS DISTINCT FROM 'tacresq_database_ok' THEN
    RAISE EXCEPTION 'Dependency health RPC returned an unexpected fixed result';
  END IF;
END;
$dependency_health_rpc_contract$;

SET ROLE anon;
DO $dependency_health_anon_execution$
BEGIN
  IF public.api_dependency_health() IS DISTINCT FROM 'tacresq_database_ok' THEN
    RAISE EXCEPTION 'Anonymous health probe did not return its fixed result';
  END IF;
END;
$dependency_health_anon_execution$;
RESET ROLE;

SET ROLE authenticated;
DO $dependency_health_authenticated_execution$
BEGIN
  IF public.api_dependency_health() IS DISTINCT FROM 'tacresq_database_ok' THEN
    RAISE EXCEPTION 'Authenticated health probe did not return its fixed result';
  END IF;
END;
$dependency_health_authenticated_execution$;
RESET ROLE;
