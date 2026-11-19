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


  -- Cross-account associations must be impossible even if verifier code
  -- accidentally supplies a valid device UUID with another profile ID.
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'device_sessions_device_owner_fk'
      AND conrelid = 'public.device_sessions'::regclass
      AND contype = 'f' AND convalidated
  ) OR NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'device_challenges_device_owner_fk'
      AND conrelid = 'public.device_challenges'::regclass
      AND contype = 'f' AND convalidated
  ) OR NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'device_integrity_checks_device_owner_fk'
      AND conrelid = 'public.device_integrity_checks'::regclass
      AND contype = 'f' AND convalidated
  ) THEN
    RAISE EXCEPTION 'Device/session/challenge/integrity ownership constraints are missing or unvalidated';
  END IF;

  -- Input bounds and short challenge lifetimes are enforced by PostgreSQL,
  -- not left to individual verifier implementations.
  IF (
    SELECT count(*) FROM pg_constraint
    WHERE convalidated
      AND (
        (conrelid = 'public.user_devices'::regclass AND conname IN (
          'user_devices_device_model_length_check',
          'user_devices_app_version_length_check',
          'user_devices_public_key_length_check',
          'user_devices_revoked_reason_length_check'
        ))
        OR (conrelid = 'public.device_sessions'::regclass
            AND conname = 'device_sessions_expiry_check')
        OR (conrelid = 'public.device_challenges'::regclass
            AND conname = 'device_challenges_max_lifetime_check')
        OR (conrelid = 'public.device_security_events'::regclass
            AND conname = 'device_security_events_details_size_check')
        OR (conrelid = 'public.device_integrity_checks'::regclass
            AND conname = 'device_integrity_checks_summary_size_check')
      )
  ) <> 8 THEN
    RAISE EXCEPTION 'Device registry metadata, payload-size, and challenge-lifetime constraints are missing or unvalidated';
  END IF;

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
