-- Runtime contract for the backend-only device registry.
DO $device_registry_privilege_contract$
DECLARE
  table_name text;
  table_oid regclass;
  privilege_name text;
  constraint_contract record;
  constraint_definition text;
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
            AND conname IN ('device_sessions_expiry_check', 'device_sessions_revocation_consistency_check'))
        OR (conrelid = 'public.device_challenges'::regclass
            AND conname = 'device_challenges_max_lifetime_check')
        OR (conrelid = 'public.device_security_events'::regclass
            AND conname = 'device_security_events_details_size_check')
        OR (conrelid = 'public.device_integrity_checks'::regclass
            AND conname = 'device_integrity_checks_summary_size_check')
      )
  ) <> 9 THEN
    RAISE EXCEPTION 'Device registry metadata, payload-size, and challenge-lifetime constraints are missing or unvalidated';
  END IF;

  -- Verify the actual CHECK expressions, not only their constraint names.
  -- This catches accidental weakening while retaining the migration-history contract.
  FOR constraint_contract IN
      SELECT * FROM (VALUES
        ('user_devices', 'user_devices_device_model_length_check', 'device_model', '120'),
        ('user_devices', 'user_devices_app_version_length_check', 'app_version', '80'),
        ('user_devices', 'user_devices_public_key_length_check', 'public_key', '4096'),
        ('user_devices', 'user_devices_revoked_reason_length_check', 'revoked_reason', '500'),
        ('device_sessions', 'device_sessions_expiry_check', 'expires_at', 'created_at'),
        ('device_sessions', 'device_sessions_revocation_consistency_check', 'revoked_at', 'revoke_reason'),
        ('device_challenges', 'device_challenges_max_lifetime_check', 'expires_at', 'created_at'),
        ('device_security_events', 'device_security_events_details_size_check', 'details', '8192'),
        ('device_integrity_checks', 'device_integrity_checks_summary_size_check', 'result_summary', '4096')
      ) AS contracts(table_name, constraint_name, required_token_one, required_token_two)
    LOOP
      SELECT lower(pg_get_constraintdef(oid))
      INTO constraint_definition
      FROM pg_constraint
      WHERE conrelid = to_regclass('public.' || constraint_contract.table_name)
        AND conname = constraint_contract.constraint_name
        AND convalidated;

      IF constraint_definition IS NULL
         OR position(lower(constraint_contract.required_token_one) IN constraint_definition) = 0
         OR position(lower(constraint_contract.required_token_two) IN constraint_definition) = 0 THEN
        RAISE EXCEPTION 'Device registry CHECK expression is missing expected tokens: %.%',
          constraint_contract.table_name, constraint_contract.constraint_name;
      END IF;
  END LOOP;

  -- The short challenge ceiling is security-sensitive; assert its actual
    -- interval literal rather than relying on the constraint's display name.
  SELECT lower(pg_get_constraintdef(oid))
  INTO constraint_definition
  FROM pg_constraint
  WHERE conrelid = 'public.device_challenges'::regclass
    AND conname = 'device_challenges_max_lifetime_check'
    AND convalidated;
  IF position('00:10:00' IN constraint_definition) = 0 THEN
    RAISE EXCEPTION 'Device challenge lifetime must remain capped at ten minutes';
  END IF;

  -- The dedicated verifier login is the only caller of private device
  -- routines. It has no direct registry-table privileges and no RLS bypass.
  IF NOT EXISTS (
    SELECT 1 FROM pg_roles
    WHERE rolname = 'tacresq_device_verifier'
      AND NOT rolcanlogin
      AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
      AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit
  ) THEN
    RAISE EXCEPTION 'Dedicated device verifier role is missing or over-privileged';
  END IF;

  IF NOT has_function_privilege(
       'tacresq_device_verifier',
       'private.device_verifier_issue_challenge(uuid,uuid,text,uuid)',
       'EXECUTE'
     )
     OR NOT has_function_privilege(
       'tacresq_device_verifier',
       'private.device_verifier_get_challenge(uuid,uuid,uuid)',
       'EXECUTE'
     )
     OR NOT has_function_privilege(
       'tacresq_device_verifier',
       'private.device_verifier_complete_challenge(uuid,uuid,uuid,text,text,text,text,text)',
       'EXECUTE'
     ) THEN
    RAISE EXCEPTION 'Dedicated device verifier role is missing a required private function grant';
  END IF;

  FOREACH privilege_name IN ARRAY ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'] LOOP
    IF has_table_privilege('tacresq_device_verifier', 'public.user_devices', privilege_name)
       OR has_table_privilege('tacresq_device_verifier', 'public.device_sessions', privilege_name)
       OR has_table_privilege('tacresq_device_verifier', 'public.device_challenges', privilege_name)
       OR has_table_privilege('tacresq_device_verifier', 'public.device_security_events', privilege_name)
       OR has_table_privilege('tacresq_device_verifier', 'public.device_integrity_checks', privilege_name) THEN
      RAISE EXCEPTION 'Dedicated verifier role unexpectedly has direct table privilege %', privilege_name;
    END IF;
  END LOOP;

  IF has_function_privilege(
       'authenticated',
       'private.device_verifier_issue_challenge(uuid,uuid,text,uuid)',
       'EXECUTE'
     )
     OR has_function_privilege(
       'service_role',
       'private.device_verifier_issue_challenge(uuid,uuid,text,uuid)',
       'EXECUTE'
     )
     OR has_function_privilege(
       'anon',
       'private.device_verifier_issue_challenge(uuid,uuid,text,uuid)',
       'EXECUTE'
     ) THEN
    RAISE EXCEPTION 'A client or service_role can invoke the private device verifier';
  END IF;

  -- The dedicated verifier's SECURITY DEFINER functions own all writes.
  -- service_role must not read or mutate any device registry table directly.
  FOREACH table_name IN ARRAY table_names LOOP
    table_oid := to_regclass('public.' || table_name);
    FOREACH privilege_name IN ARRAY ARRAY[
      'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'
    ] LOOP
      IF has_table_privilege('service_role', table_oid, privilege_name) THEN
        RAISE EXCEPTION 'service_role unexpectedly has % on public.%', privilege_name, table_name;
      END IF;
    END LOOP;
  END LOOP;

  FOREACH privilege_name IN ARRAY ARRAY['USAGE', 'SELECT', 'UPDATE'] LOOP
    IF has_sequence_privilege('anon', 'public.device_security_events_id_seq', privilege_name)
       OR has_sequence_privilege('authenticated', 'public.device_security_events_id_seq', privilege_name)
       OR has_sequence_privilege('service_role', 'public.device_security_events_id_seq', privilege_name)
       OR has_sequence_privilege('anon', 'public.device_integrity_checks_id_seq', privilege_name)
       OR has_sequence_privilege('authenticated', 'public.device_integrity_checks_id_seq', privilege_name)
       OR has_sequence_privilege('service_role', 'public.device_integrity_checks_id_seq', privilege_name) THEN
      RAISE EXCEPTION 'Client or service role unexpectedly has % on device registry sequences', privilege_name;
    END IF;
  END LOOP;
END;
$device_registry_privilege_contract$;
