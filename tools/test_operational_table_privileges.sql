-- Runtime contract for read-only operational tables and RPC-backed writes.
DO $operational_table_privilege_boundary$
DECLARE
  table_name text;
  table_oid regclass;
  privilege_name text;
  fcm_register regprocedure := to_regprocedure('public.api_register_fcm_token(text)');
  fcm_unregister regprocedure := to_regprocedure('public.api_unregister_fcm_token(text)');
  location_writer regprocedure := to_regprocedure(
    'public.api_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)'
  );
BEGIN
  IF fcm_register IS NULL OR fcm_unregister IS NULL OR location_writer IS NULL THEN
    RAISE EXCEPTION 'Expected FCM lifecycle and location writer RPCs';
  END IF;

  FOREACH table_name IN ARRAY ARRAY['fcm_tokens', 'incident_status_logs', 'location_logs'] LOOP
    table_oid := to_regclass('public.' || table_name);
    IF table_oid IS NULL THEN
      RAISE EXCEPTION 'Expected public.% table', table_name;
    END IF;

    IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid = table_oid) THEN
      RAISE EXCEPTION 'public.% must retain RLS', table_name;
    END IF;

    IF NOT has_table_privilege('authenticated', table_oid, 'SELECT') THEN
      RAISE EXCEPTION 'authenticated must retain RLS-filtered SELECT on public.%', table_name;
    END IF;

    FOREACH privilege_name IN ARRAY ARRAY[
      'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'
    ] LOOP
      IF has_table_privilege('anon', table_oid, privilege_name) THEN
        RAISE EXCEPTION 'anon unexpectedly has % on public.%', privilege_name, table_name;
      END IF;
      IF has_table_privilege('authenticated', table_oid, privilege_name) THEN
        RAISE EXCEPTION 'authenticated unexpectedly has % on public.%', privilege_name, table_name;
      END IF;
    END LOOP;
  END LOOP;

  -- The FCM worker intentionally removes definitively invalid tokens using
  -- service_role. This server-only path must survive client privilege cleanup.
  IF NOT has_table_privilege('service_role', 'public.fcm_tokens', 'DELETE') THEN
    RAISE EXCEPTION 'service_role must retain invalid FCM token cleanup';
  END IF;

  IF NOT has_function_privilege('authenticated', fcm_register, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', fcm_unregister, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', location_writer, 'EXECUTE') THEN
    RAISE EXCEPTION 'Authenticated clients must retain the reviewed write RPCs';
  END IF;

  IF has_function_privilege('anon', fcm_register, 'EXECUTE')
     OR has_function_privilege('anon', fcm_unregister, 'EXECUTE')
     OR has_function_privilege('anon', location_writer, 'EXECUTE') THEN
    RAISE EXCEPTION 'anon must not execute FCM lifecycle or location writer RPCs';
  END IF;
END;
$operational_table_privilege_boundary$;
