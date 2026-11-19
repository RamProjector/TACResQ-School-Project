-- Runtime contract for the backend-only device registry.
DO $device_registry_privilege_contract$
DECLARE
  table_name text;
  table_oid regclass;
  privilege_name text;
  table_names text[] := ARRAY[
    'user_devices',
    'device_sessions',
    'device_challenges',
    'device_security_events',
    'device_integrity_checks'
  ];
BEGIN
  FOREACH table_name IN ARRAY table_names LOOP
    table_oid := to_regclass('public.' || table_name);
    IF table_oid IS NULL THEN
      RAISE EXCEPTION 'Expected public.% table', table_name;
    END IF;

    IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid = table_oid) THEN
      RAISE EXCEPTION 'RLS must be enabled on public.%', table_name;
    END IF;

    FOREACH privilege_name IN ARRAY ARRAY[
      'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'
    ] LOOP
      IF has_table_privilege('anon', table_oid, privilege_name)
         OR has_table_privilege('authenticated', table_oid, privilege_name) THEN
        RAISE EXCEPTION 'Client/PUBLIC role unexpectedly has % on public.%',
          privilege_name, table_name;
      END IF;
    END LOOP;
  END LOOP;

  -- Mutable registry records are server-managed; event/integrity records
  -- are insert-only so service_role cannot rewrite or erase their history.
  IF NOT has_table_privilege('service_role', 'public.user_devices', 'SELECT')
     OR NOT has_table_privilege('service_role', 'public.user_devices', 'INSERT')
     OR NOT has_table_privilege('service_role', 'public.user_devices', 'UPDATE')
     OR has_table_privilege('service_role', 'public.user_devices', 'DELETE')
     OR has_table_privilege('service_role', 'public.user_devices', 'TRUNCATE') THEN
    RAISE EXCEPTION 'Unexpected service_role privileges on user_devices';
  END IF;

  FOREACH table_name IN ARRAY ARRAY['device_sessions', 'device_challenges'] LOOP
    table_oid := to_regclass('public.' || table_name);
    IF NOT has_table_privilege('service_role', table_oid, 'SELECT')
       OR NOT has_table_privilege('service_role', table_oid, 'INSERT')
       OR NOT has_table_privilege('service_role', table_oid, 'UPDATE')
       OR has_table_privilege('service_role', table_oid, 'DELETE')
       OR has_table_privilege('service_role', table_oid, 'TRUNCATE') THEN
      RAISE EXCEPTION 'Unexpected service_role privileges on public.%', table_name;
    END IF;
  END LOOP;

  FOREACH table_name IN ARRAY ARRAY['device_security_events', 'device_integrity_checks'] LOOP
    table_oid := to_regclass('public.' || table_name);
    IF NOT has_table_privilege('service_role', table_oid, 'SELECT')
       OR NOT has_table_privilege('service_role', table_oid, 'INSERT')
       OR has_table_privilege('service_role', table_oid, 'UPDATE')
       OR has_table_privilege('service_role', table_oid, 'DELETE')
       OR has_table_privilege('service_role', table_oid, 'TRUNCATE') THEN
      RAISE EXCEPTION 'Unexpected service_role privileges on append-only public.%', table_name;
    END IF;
  END LOOP;
END;
$device_registry_privilege_contract$;
