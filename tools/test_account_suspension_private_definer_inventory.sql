-- Regression contract for authenticated-callable private SECURITY DEFINER helpers.
-- These helpers are directly reachable by authenticated clients and must be
-- reviewed independently of their public wrappers. This is an inventory gate,
-- not proof that any helper enforces account suspension.
DO $account_suspension_private_definer_inventory$
DECLARE
  expected_signature text;
  resolved_oid oid;
  unexpected_signature text;
  expected_internal_signature text;
  internal_oid oid;
  expected_signatures text[] := ARRAY[
    'private.tacresq_active_responder_locations()',
    'private.tacresq_actor_role()',
    'private.tacresq_assign_incident_team_guarded(bigint,bigint[])',
    'private.tacresq_auth_can_access_incident_chat(bigint)',
    'private.tacresq_auth_profile_id()',
    'private.tacresq_auth_profile_id_for_token_cleanup()',
    'private.tacresq_current_account_active()',
    'private.tacresq_current_account_active_read()',
    'private.tacresq_dispatch_update_incident_status(bigint,text)',
    'private.tacresq_is_incident_assignee(bigint)',
    'private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)',
    'private.tacresq_release_incident_assignment(bigint,bigint)',
    'private.tacresq_upload_reservation_allows_insert(text,text,bigint)'
  ];
BEGIN
  FOREACH expected_signature IN ARRAY expected_signatures LOOP
    resolved_oid := to_regprocedure(expected_signature);
    IF resolved_oid IS NULL THEN
      RAISE EXCEPTION 'Account-suspension private helper signature is missing: %', expected_signature;
    END IF;
    IF NOT EXISTS (
      SELECT 1
      FROM pg_catalog.pg_proc AS p
      JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
      WHERE p.oid = resolved_oid
        AND n.nspname = 'private'
        AND p.prosecdef
        AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
    ) THEN
      RAISE EXCEPTION 'Expected authenticated-callable private SECURITY DEFINER contract changed: %',
        expected_signature;
    END IF;

    IF has_function_privilege('anon', resolved_oid, 'EXECUTE')
       OR EXISTS (
         SELECT 1
         FROM pg_catalog.pg_proc AS p
         CROSS JOIN LATERAL pg_catalog.aclexplode(
           coalesce(p.proacl, pg_catalog.acldefault('f', p.proowner))
         ) AS acl
         WHERE p.oid = resolved_oid
           AND acl.grantee = 0
           AND acl.privilege_type = 'EXECUTE'
       ) THEN
      RAISE EXCEPTION 'Callable private SECURITY DEFINER must not be executable by anon or PUBLIC: %',
        expected_signature;
    END IF;
  END LOOP;

  SELECT p.oid::regprocedure::text
    INTO unexpected_signature
  FROM pg_catalog.pg_proc AS p
  JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
  WHERE n.nspname = 'private'
    AND p.prokind = 'f'
    AND p.prosecdef
    AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
    AND NOT (p.oid = ANY (
      SELECT to_regprocedure(signature)::oid
      FROM unnest(expected_signatures) AS expected(signature)
    ))
  ORDER BY p.oid::regprocedure::text
  LIMIT 1;

  IF unexpected_signature IS NOT NULL THEN
    RAISE EXCEPTION 'New authenticated-callable private SECURITY DEFINER helper needs explicit review: %',
      unexpected_signature;
  END IF;

  IF cardinality(expected_signatures) <> 13 THEN
    RAISE EXCEPTION 'Private callable-definer inventory must contain exactly 13 signatures; found %',
      cardinality(expected_signatures);
  END IF;

  -- The private schema is needed for SECURITY INVOKER wrappers and Storage
  -- policy name resolution, but remains outside the PostgREST exposed schema
  -- list. Do not grant anonymous name resolution or client CREATE privileges.
  IF NOT has_schema_privilege('authenticated', 'private', 'USAGE')
     OR has_schema_privilege('anon', 'private', 'USAGE')
     OR has_schema_privilege('authenticated', 'private', 'CREATE')
     OR has_schema_privilege('anon', 'private', 'CREATE') THEN
    RAISE EXCEPTION 'Private schema grants must remain authenticated USAGE only; no anon USAGE or client CREATE';
  END IF;

  -- Privileged implementations are callable only through their guarded
  -- wrappers. Their direct EXECUTE grants must not drift back to clients.
  FOREACH expected_internal_signature IN ARRAY ARRAY[
    'private.tacresq_active_responder_locations_internal()',
    'private.tacresq_assign_incident_team(bigint,bigint[])',
    'private.tacresq_can_access_incident_chat(bigint,bigint)',
    'private.tacresq_record_location_v2_internal(bigint,double precision,double precision,real,timestamp with time zone)',
    'private.tacresq_release_incident_assignment_internal(bigint,bigint)'
  ] LOOP
    internal_oid := to_regprocedure(expected_internal_signature);
    IF internal_oid IS NULL
       OR has_function_privilege('authenticated', internal_oid, 'EXECUTE')
       OR has_function_privilege('anon', internal_oid, 'EXECUTE')
       OR EXISTS (
         SELECT 1
         FROM pg_catalog.pg_proc AS p
         CROSS JOIN LATERAL pg_catalog.aclexplode(
           coalesce(p.proacl, pg_catalog.acldefault('f', p.proowner))
         ) AS acl
         WHERE p.oid = internal_oid
           AND acl.grantee = 0
           AND acl.privilege_type = 'EXECUTE'
       ) THEN
      RAISE EXCEPTION 'Internal private implementation must remain non-client-executable: %',
        expected_internal_signature;
    END IF;
  END LOOP;
END;
$account_suspension_private_definer_inventory$;

\echo 'PASS: all 13 authenticated-callable private SECURITY DEFINER helpers remain inventoried; private schema grants and internal implementation EXECUTE boundaries remain constrained.'
