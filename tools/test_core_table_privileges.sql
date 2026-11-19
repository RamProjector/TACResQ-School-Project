-- Runtime contract for profiles/incidents destructive privilege boundary.
-- Profile creation is handled by the guarded auth-user trigger; incident creation
-- is routed through authorization-checked RPCs, not direct table INSERT.
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

    -- Verify table-level grants separately from column-level UPDATE grants.
    -- PostgreSQL has_table_privilege(..., 'UPDATE') intentionally returns false
    -- when only specific columns are writable; do not broaden those grants.
    -- Neither table should expose direct client INSERT.
    required_privileges := ARRAY['SELECT'];

    FOREACH required_privilege IN ARRAY required_privileges LOOP
      IF NOT has_table_privilege('authenticated', table_oid, required_privilege) THEN
        RAISE EXCEPTION 'authenticated must retain % on public.% for current guarded workflows',
          required_privilege, table_name;
      END IF;
    END LOOP;

    IF has_table_privilege('authenticated', table_oid, 'INSERT') THEN
      RAISE EXCEPTION 'authenticated must not directly INSERT into public.%', table_name;
    END IF;

    IF table_name = 'profiles' THEN
      IF NOT has_column_privilege('authenticated', table_oid, 'duty_status', 'UPDATE')
         OR NOT has_column_privilege('authenticated', table_oid, 'updated_at', 'UPDATE') THEN
        RAISE EXCEPTION 'authenticated must retain only the required profile duty-status update columns';
      END IF;
      IF has_column_privilege('authenticated', table_oid, 'role', 'UPDATE')
         OR has_column_privilege('authenticated', table_oid, 'auth_user_id', 'UPDATE')
         OR has_column_privilege('authenticated', table_oid, 'email', 'UPDATE')
         OR has_column_privilege('authenticated', table_oid, 'name', 'UPDATE')
         OR has_column_privilege('authenticated', table_oid, 'phone', 'UPDATE') THEN
        RAISE EXCEPTION 'authenticated must not update profile identity or role columns directly';
      END IF;
    ELSIF table_name = 'incidents' THEN
      IF NOT has_column_privilege('authenticated', table_oid, 'status', 'UPDATE')
         OR NOT has_column_privilege('authenticated', table_oid, 'assigned_to', 'UPDATE')
         OR NOT has_column_privilege('authenticated', table_oid, 'updated_at', 'UPDATE') THEN
        RAISE EXCEPTION 'authenticated must retain required incident progress update columns';
      END IF;
      IF has_column_privilege('authenticated', table_oid, 'requester_id', 'UPDATE')
         OR has_column_privilege('authenticated', table_oid, 'type', 'UPDATE')
         OR has_column_privilege('authenticated', table_oid, 'latitude', 'UPDATE')
         OR has_column_privilege('authenticated', table_oid, 'longitude', 'UPDATE') THEN
        RAISE EXCEPTION 'authenticated must not update incident ownership or report fields directly';
      END IF;
    END IF;

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
