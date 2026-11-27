-- Regression contract for the clean-replay account-suspension callable inventory.
-- This classifies the current public SECURITY DEFINER entry points; it does NOT
-- claim that any entry point already enforces account_status.
DO $account_suspension_definer_inventory$
DECLARE
  expected_signature text;
  resolved_oid oid;
  unexpected_signature text;
  api_callable_count integer;
  api_definer_count integer;
  api_invoker_count integer;
  expected_signatures text[] := ARRAY[
    'public.api_admin_analytics_summary(timestamp with time zone,timestamp with time zone)',
    'public.api_admin_list_account_lifecycle_events(integer,integer)',
    'public.api_admin_list_auth_sessions_page(bigint,integer,timestamp with time zone,uuid)',
    'public.api_admin_list_audit_logs(integer)',
    'public.api_admin_list_audit_logs_page(integer,integer,text,text,timestamp with time zone,timestamp with time zone,bigint,bigint)',
    'public.api_admin_list_investigation_events(integer,integer,text,text,text,timestamp with time zone,timestamp with time zone,bigint,bigint,bigint)',
    'public.api_admin_list_users_page(integer,integer,text,text)',
    'public.api_admin_set_user_role(bigint,text,text)',
    'public.api_admin_set_account_status(bigint,text,text,text)',
    'public.api_cancel_my_incident(bigint)',
    'public.api_claim_incident(bigint)',
    'public.api_claimable_incidents()',
    'public.api_create_incident_v4(text,text,double precision,double precision,real,text,timestamp with time zone,text,uuid)',
    'public.api_mark_notification_read(bigint)',
    'public.api_my_incident_messages(bigint,integer,bigint)',
    'public.api_my_notification(bigint)',
    'public.api_my_notifications(integer)',
    'public.api_register_fcm_token(text)',
    'public.api_unregister_fcm_token(text)',
    'public.api_reserve_incident_image_upload(bigint,text,bigint,uuid)',
    'public.api_send_incident_image_message(bigint,text,text,text,bigint,uuid)',
    'public.api_send_incident_message(bigint,text,uuid)',
    'public.api_set_incident_claimable(bigint,boolean)',
    'public.api_units()',
    'public.api_update_my_incident(bigint,text,text,double precision,double precision,real,text,timestamp with time zone,text,boolean)'
  ];
  expected_invoker_signatures text[] := ARRAY[
    'public.api_active_responder_locations()',
    'public.api_all_incidents()',
    'public.api_assign_incident(bigint,bigint)',
    'public.api_assign_incident_team(bigint,bigint[])',
    'public.api_dependency_health()',
    'public.api_me()',
    'public.api_my_incidents()',
    'public.api_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)',
    'public.api_release_incident_assignment(bigint,bigint)',
    'public.api_set_duty_status(text)',
    'public.api_update_incident_status(bigint,text)'
  ];
BEGIN
  FOREACH expected_signature IN ARRAY expected_signatures LOOP
    resolved_oid := to_regprocedure(expected_signature);
    IF resolved_oid IS NULL THEN
      RAISE EXCEPTION 'Account-suspension inventory signature is missing: %', expected_signature;
    END IF;
    IF NOT EXISTS (
      SELECT 1
      FROM pg_catalog.pg_proc AS p
      JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
      WHERE p.oid = resolved_oid
        AND n.nspname = 'public'
        AND p.prosecdef
        AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
    ) THEN
      RAISE EXCEPTION 'Expected callable SECURITY DEFINER contract changed: %', expected_signature;
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
      RAISE EXCEPTION 'Callable public SECURITY DEFINER must not be executable by anon or PUBLIC: %',
        expected_signature;
    END IF;
  END LOOP;

  FOREACH expected_signature IN ARRAY expected_invoker_signatures LOOP
    resolved_oid := to_regprocedure(expected_signature);
    IF resolved_oid IS NULL THEN
      RAISE EXCEPTION 'Application invoker signature is missing: %', expected_signature;
    END IF;
    IF NOT EXISTS (
      SELECT 1
      FROM pg_catalog.pg_proc AS p
      JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
      WHERE p.oid = resolved_oid
        AND n.nspname = 'public'
        AND p.prokind = 'f'
        AND NOT p.prosecdef
        AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
    ) THEN
      RAISE EXCEPTION 'Expected authenticated-callable application SECURITY INVOKER changed: %', expected_signature;
    END IF;
    IF expected_signature <> 'public.api_dependency_health()'
       AND has_function_privilege('anon', resolved_oid, 'EXECUTE') THEN
      RAISE EXCEPTION 'Application invoker must not be executable by anon: %', expected_signature;
    END IF;
  END LOOP;

  IF NOT has_function_privilege('anon', 'public.api_dependency_health()', 'EXECUTE') THEN
    RAISE EXCEPTION 'The fixed-result dependency health probe must remain available to anon';
  END IF;

  SELECT p.oid::regprocedure::text
    INTO unexpected_signature
  FROM pg_catalog.pg_proc AS p
  JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
  WHERE n.nspname = 'public'
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
    RAISE EXCEPTION 'New authenticated-callable public SECURITY DEFINER function needs explicit account-suspension review: %',
      unexpected_signature;
  END IF;

  SELECT p.oid::regprocedure::text
    INTO unexpected_signature
  FROM pg_catalog.pg_proc AS p
  JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
  WHERE n.nspname = 'public'
    AND p.prokind = 'f'
    AND left(p.proname, 4) = 'api_'
    AND NOT p.prosecdef
    AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
    AND NOT (p.oid = ANY (
      SELECT to_regprocedure(signature)::oid
      FROM unnest(expected_invoker_signatures) AS expected(signature)
    ))
  ORDER BY p.oid::regprocedure::text
  LIMIT 1;

  IF unexpected_signature IS NOT NULL THEN
    RAISE EXCEPTION 'New authenticated-callable public application invoker needs explicit authorization review: %',
      unexpected_signature;
  END IF;

  -- The Admin session inventory is a new callable definer. Its own body
  -- must reuse the current active-Admin/AAL2/TOTP guard and expose only the
  -- provider-owned Auth session reader behind that boundary.
  IF NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_proc AS p
    JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
    WHERE p.oid = to_regprocedure(
      'public.api_admin_list_auth_sessions_page(bigint,integer,timestamp with time zone,uuid)'
    )
      AND n.nspname = 'public'
      AND p.prosecdef
      AND p.provolatile = 'v'
      AND coalesce(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']
      AND p.prosrc LIKE '%private.tacresq_require_admin_mfa()%'
      AND p.prosrc LIKE '%FROM auth.sessions AS s%'
      AND p.prosrc LIKE '%FROM public.profiles AS p%'
      AND p.prosrc LIKE '%p.auth_user_id%'
  ) THEN
    RAISE EXCEPTION 'Admin session reader must be a volatile, pinned SECURITY DEFINER wrapper guarded by the active Admin MFA helper and canonical profile mapping';
  END IF;

  IF cardinality(expected_signatures) <> 25 OR cardinality(expected_invoker_signatures) <> 11 THEN
    RAISE EXCEPTION 'Inventory contract must contain exactly 25 classified signatures; found %',
      cardinality(expected_signatures);
  END IF;

  SELECT
    count(*)::integer,
    count(*) FILTER (WHERE p.prosecdef)::integer,
    count(*) FILTER (WHERE NOT p.prosecdef)::integer
  INTO api_callable_count, api_definer_count, api_invoker_count
  FROM pg_catalog.pg_proc AS p
  JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
  WHERE n.nspname = 'public'
    AND p.prokind = 'f'
    AND left(p.proname, 4) = 'api_'
    AND has_function_privilege('authenticated', p.oid, 'EXECUTE');

  IF api_callable_count <> 35
     OR api_definer_count <> 24
     OR api_invoker_count <> 11 THEN
    RAISE EXCEPTION
      'Application API callable baseline drifted: total %, SECURITY DEFINER %, SECURITY INVOKER %; expected 36/25/11',
      api_callable_count, api_definer_count, api_invoker_count;
  END IF;
END;
$account_suspension_definer_inventory$;

\echo 'PASS: all 25 public API SECURITY DEFINER and 11 public API SECURITY INVOKER signatures remain inventoried; legacy requester-create invokers are tested separately.'
