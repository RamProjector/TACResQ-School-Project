-- Runtime contract for profiles/incidents destructive privilege boundary.
-- Profile creation is handled by the guarded auth-user trigger, not direct client INSERT.
DO $core_table_privilege_boundary$
DECLARE
  table_name text;
  table_oid regclass;
  required_privilege text;
  required_privileges text[];
BEGIN
  FOREACH table_name IN ARRAY ARRAY['profiles', 'incidents'] LOOP
    table_oid := to_regclass('public.' || table_name);
    IF table_oid IS NULL THEN
      RAISE EXCEPTION 'Expected public.% table', table_name;
    END IF;

    IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid = table_oid) THEN
      RAISE EXCEPTION 'public.% must retain RLS', table_name;
    END IF;

    -- Preserve only the grants needed by the existing client workflows.
    -- Profiles are created by the SECURITY DEFINER signup trigger; clients
    -- must not be able to insert arbitrary profile/role records directly.
    required_privileges := CASE table_name
      WHEN 'profiles' THEN ARRAY['SELECT', 'UPDATE']
      WHEN 'incidents' THEN ARRAY['SELECT', 'INSERT', 'UPDATE']
    END;

    FOREACH required_privilege IN ARRAY required_privileges LOOP
      IF NOT has_table_privilege('authenticated', table_oid, required_privilege) THEN
        RAISE EXCEPTION 'authenticated must retain % on public.% for current guarded workflows',
          required_privilege, table_name;
      END IF;
    END LOOP;

    IF has_table_privilege('anon', table_oid, 'SELECT')
       OR has_table_privilege('anon', table_oid, 'INSERT')
       OR has_table_privilege('anon', table_oid, 'UPDATE')
       OR has_table_privilege('anon', table_oid, 'DELETE')
       OR has_table_privilege('anon', table_oid, 'TRUNCATE') THEN
      RAISE EXCEPTION 'anon must have no direct privileges on public.%', table_name;
    END IF;

    IF has_table_privilege('authenticated', table_oid, 'DELETE')
       OR has_table_privilege('authenticated', table_oid, 'TRUNCATE') THEN
      RAISE EXCEPTION 'authenticated must not directly delete or truncate public.%', table_name;
    END IF;
  END LOOP;
END;
$core_table_privilege_boundary$;
