-- Runtime contract for retired client entry points and the v4 -> v3 internal call.
DO $legacy_incident_create_rpc_contract$
DECLARE
  legacy_oid oid;
  legacy_signature text;
  legacy_signatures text[] := ARRAY[
    'public.api_create_incident(text,text,double precision,double precision,real)',
    'public.api_create_incident_v2(text,text,double precision,double precision,real,text,timestamp with time zone)',
    'public.api_create_incident_v3(text,text,double precision,double precision,real,text,timestamp with time zone,text)'
  ];
  role_name text;
  v4_oid oid := to_regprocedure(
    'public.api_create_incident_v4(text,text,double precision,double precision,real,text,timestamp with time zone,text,uuid)'
  );
  v3_oid oid := to_regprocedure(
    'public.api_create_incident_v3(text,text,double precision,double precision,real,text,timestamp with time zone,text)'
  );
  v4_is_definer boolean;
  v4_source text;
BEGIN
  FOREACH legacy_signature IN ARRAY legacy_signatures LOOP
    legacy_oid := to_regprocedure(legacy_signature);
    IF legacy_oid IS NULL THEN
      RAISE EXCEPTION 'Expected retained legacy RPC signature is missing: %', legacy_signature;
    END IF;

    FOREACH role_name IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
      IF has_function_privilege(role_name, legacy_oid, 'EXECUTE') THEN
        RAISE EXCEPTION 'Legacy incident-create RPC % must not be executable by %', legacy_signature, role_name;
      END IF;
    END LOOP;

    IF EXISTS (
      SELECT 1
      FROM pg_catalog.pg_proc AS routine
      CROSS JOIN LATERAL pg_catalog.aclexplode(
        coalesce(routine.proacl, pg_catalog.acldefault('f', routine.proowner))
      ) AS acl_row
      WHERE routine.oid = legacy_oid
        AND acl_row.grantee = 0
        AND acl_row.privilege_type = 'EXECUTE'
    ) THEN
      RAISE EXCEPTION 'Legacy incident-create RPC % must not be executable by PUBLIC', legacy_signature;
    END IF;
  END LOOP;

  IF v4_oid IS NULL OR v3_oid IS NULL THEN
    RAISE EXCEPTION 'Current v4 requester create RPC or its v3 internal implementation is missing';
  END IF;

  SELECT routine.prosecdef, routine.prosrc
    INTO v4_is_definer, v4_source
    FROM pg_catalog.pg_proc AS routine
   WHERE routine.oid = v4_oid;

  IF v4_is_definer IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'api_create_incident_v4 must remain SECURITY DEFINER';
  END IF;
  IF v4_source NOT LIKE '%api_create_incident_v3%' THEN
    RAISE EXCEPTION 'api_create_incident_v4 must continue delegating to the retained v3 implementation';
  END IF;
  IF NOT has_function_privilege('authenticated', v4_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Authenticated clients must retain the current api_create_incident_v4 entry point';
  END IF;
  IF NOT has_function_privilege('postgres', v3_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'Function owner must retain EXECUTE on the internal v3 implementation';
  END IF;
END;
$legacy_incident_create_rpc_contract$;
