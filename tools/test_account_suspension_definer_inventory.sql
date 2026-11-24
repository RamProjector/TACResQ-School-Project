-- Regression contract for the clean-replay account-suspension callable inventory.
-- This classifies the current public SECURITY DEFINER entry points; it does NOT
-- claim that any entry point already enforces account_status.
DO $account_suspension_definer_inventory$
DECLARE
  expected_signature text;
  resolved_oid oid;
  unexpected_signature text;
  public_callable_count integer;
  public_definer_count integer;
  public_invoker_count integer;
  expected_signatures text[] := ARRAY[
    'public.api_admin_analytics_summary(timestamp with time zone,timestamp with time zone)',
    'public.api_admin_list_account_lifecycle_events(integer,integer)',
    'public.api_admin_list_audit_logs(integer)',
    'public.api_admin_list_audit_logs_page(integer,integer,text,text,timestamp with time zone,timestamp with time zone,bigint,bigint)',
    'public.api_admin_list_investigation_events(integer,integer,text,text,text,timestamp with time zone,timestamp with time zone,bigint,bigint,bigint)',
    'public.api_admin_list_users_page(integer,integer,text,text)',
    'public.api_admin_set_user_role(bigint,text,text)',
    'public.api_admin_set_account_status(bigint,text,text)',
    'public.api_assign_incident_team(bigint,bigint[])',
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

  IF cardinality(expected_signatures) <> 25 THEN
    RAISE EXCEPTION 'Inventory contract must contain exactly 25 classified signatures; found %',
      cardinality(expected_signatures);
  END IF;

  SELECT
    count(*)::integer,
    count(*) FILTER (WHERE p.prosecdef)::integer,
    count(*) FILTER (WHERE NOT p.prosecdef)::integer
  INTO public_callable_count, public_definer_count, public_invoker_count
  FROM pg_catalog.pg_proc AS p
  JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
  WHERE n.nspname = 'public'
    AND p.prokind = 'f'
    AND has_function_privilege('authenticated', p.oid, 'EXECUTE');

  IF public_callable_count <> 75
     OR public_definer_count <> 25
     OR public_invoker_count <> 50 THEN
    RAISE EXCEPTION
      'Public callable function baseline drifted: total %, SECURITY DEFINER %, SECURITY INVOKER %; expected 75/25/50',
      public_callable_count, public_definer_count, public_invoker_count;
  END IF;
END;
$account_suspension_definer_inventory$;

\echo 'PASS: all 25 public SECURITY DEFINER signatures and the 75/25/50 public callable baseline remain inventoried.'
