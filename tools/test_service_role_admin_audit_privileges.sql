-- Runtime catalog contract for the targeted service_role Admin/audit reduction.
-- Run after the complete migration replay in the disposable PostgreSQL lane.
DO $service_role_admin_audit_contract$
DECLARE
  privilege_name text;
  sequence_name text;
  function_signature text;
  function_oid oid;
  protected_functions text[] := ARRAY[
    'public.api_admin_analytics_summary(timestamptz,timestamptz)',
    'public.api_admin_analytics_summary_internal(timestamptz,timestamptz)',
    'public.api_admin_list_account_lifecycle_events(integer,integer)',
    'public.api_admin_list_audit_logs(integer)',
    'public.api_admin_list_audit_logs_internal(integer)',
    'public.api_admin_list_audit_logs_page(integer,integer,text,text,timestamptz,timestamptz,bigint,bigint)',
    'public.api_admin_list_investigation_events(integer,integer,text,text,text,timestamptz,timestamptz,bigint,bigint,bigint)',
    'public.api_admin_list_users()',
    'public.api_admin_list_users_page(integer,integer,text,text)',
    'public.api_admin_list_users_page_internal(integer,integer,text,text)',
    'public.api_admin_set_account_status(bigint,text,text,text)',
    'public.api_admin_set_user_role(bigint,text,text)',
    'public.api_admin_set_user_role_internal(bigint,text,text)'
  ];
  authenticated_functions text[] := ARRAY[
    'public.api_admin_analytics_summary(timestamptz,timestamptz)',
    'public.api_admin_list_account_lifecycle_events(integer,integer)',
    'public.api_admin_list_audit_logs(integer)',
    'public.api_admin_list_audit_logs_page(integer,integer,text,text,timestamptz,timestamptz,bigint,bigint)',
    'public.api_admin_list_investigation_events(integer,integer,text,text,text,timestamptz,timestamptz,bigint,bigint,bigint)',
    'public.api_admin_list_users_page(integer,integer,text,text)',
    'public.api_admin_set_account_status(bigint,text,text,text)',
    'public.api_admin_set_user_role(bigint,text,text)'
  ];
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_class
    WHERE oid = 'public.admin_audit_logs'::regclass
      AND relowner = 'postgres'::regrole
      AND relrowsecurity
  ) THEN
    RAISE EXCEPTION 'admin_audit_logs must remain postgres-owned with RLS enabled';
  END IF;

  FOREACH privilege_name IN ARRAY ARRAY[
    'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'
  ] LOOP
    IF has_table_privilege('service_role', 'public.admin_audit_logs', privilege_name) THEN
      RAISE EXCEPTION 'service_role unexpectedly retains % on admin_audit_logs', privilege_name;
    END IF;
  END LOOP;

  IF EXISTS (
    SELECT 1
    FROM pg_catalog.pg_attribute AS attribute_row
    CROSS JOIN LATERAL pg_catalog.aclexplode(attribute_row.attacl) AS column_privilege
    WHERE attribute_row.attrelid = 'public.admin_audit_logs'::regclass
      AND attribute_row.attnum > 0
      AND NOT attribute_row.attisdropped
      AND column_privilege.grantee = 'service_role'::regrole
  ) THEN
    RAISE EXCEPTION 'service_role unexpectedly retains column-level privileges on admin_audit_logs';
  END IF;

  sequence_name := pg_get_serial_sequence('public.admin_audit_logs', 'id');
  IF sequence_name IS NULL THEN
    RAISE EXCEPTION 'admin_audit_logs identity sequence is missing';
  END IF;
  FOREACH privilege_name IN ARRAY ARRAY['USAGE', 'SELECT', 'UPDATE'] LOOP
    IF has_sequence_privilege('service_role', sequence_name, privilege_name) THEN
      RAISE EXCEPTION 'service_role unexpectedly retains % on %', privilege_name, sequence_name;
    END IF;
  END LOOP;

  -- Service-role must not invoke Admin-only or internal Admin functions directly.
  FOREACH function_signature IN ARRAY protected_functions LOOP
    function_oid := to_regprocedure(function_signature);
    IF function_oid IS NULL THEN
      RAISE EXCEPTION 'Expected Admin function is missing: %', function_signature;
    END IF;
    IF has_function_privilege('service_role', function_oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'service_role unexpectedly retains EXECUTE on %', function_signature;
    END IF;
    IF NOT has_function_privilege('postgres', function_oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'Function owner lost EXECUTE on %', function_signature;
    END IF;
  END LOOP;

  -- Catch future Admin RPCs too: default ACLs or a new migration must not
  -- silently restore service_role EXECUTE on the Admin API family.
  IF EXISTS (
    SELECT 1
    FROM pg_catalog.pg_proc AS function_row
    JOIN pg_catalog.pg_namespace AS namespace_row
      ON namespace_row.oid = function_row.pronamespace
    WHERE namespace_row.nspname = 'public'
      AND function_row.proname LIKE 'api_admin_%'
      AND has_function_privilege('service_role', function_row.oid, 'EXECUTE')
  ) THEN
    RAISE EXCEPTION 'service_role must not have EXECUTE on any public api_admin_* function';
  END IF;

  -- Preserve the actual authenticated Admin RPC surface; role/MFA checks in
  -- each function remain authoritative and are exercised by separate tests.
  FOREACH function_signature IN ARRAY authenticated_functions LOOP
    function_oid := to_regprocedure(function_signature);
    IF function_oid IS NULL OR NOT has_function_privilege('authenticated', function_oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'Authenticated Admin RPC grant was lost: %', function_signature;
    END IF;
  END LOOP;

  IF NOT has_table_privilege('authenticated', 'public.admin_audit_logs', 'SELECT') THEN
    RAISE EXCEPTION 'Authenticated Admin audit reader lost its SELECT grant';
  END IF;
  IF has_table_privilege('authenticated', 'public.admin_audit_logs', 'INSERT')
     OR has_table_privilege('authenticated', 'public.admin_audit_logs', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.admin_audit_logs', 'DELETE')
     OR has_table_privilege('authenticated', 'public.admin_audit_logs', 'TRUNCATE') THEN
    RAISE EXCEPTION 'Authenticated users must not gain direct audit-table writes';
  END IF;
END;
$service_role_admin_audit_contract$;
