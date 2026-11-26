BEGIN;

-- Profile creation is owned by the Auth-user trigger. The trigger function is
-- SECURITY DEFINER and inserts using the Auth identity; app clients must not
-- create profile rows directly through PostgREST.
REVOKE INSERT ON TABLE public.profiles FROM PUBLIC, anon, authenticated;

-- A table-level revoke does not remove independent column-level INSERT ACLs.
-- Clear any historical per-column grants as well.
DO $revoke_profile_column_insert_grants$
DECLARE
  v_columns text;
BEGIN
  SELECT string_agg(format('%I', attname), ', ' ORDER BY attnum)
    INTO v_columns
    FROM pg_catalog.pg_attribute
   WHERE attrelid = 'public.profiles'::regclass
     AND attnum > 0
     AND NOT attisdropped;

  IF v_columns IS NOT NULL THEN
    EXECUTE format(
      'REVOKE INSERT (%1$s) ON TABLE public.profiles FROM PUBLIC, anon, authenticated',
      v_columns
    );
  END IF;
END;
$revoke_profile_column_insert_grants$;

COMMENT ON TABLE public.profiles IS
  'Profile creation is Auth-trigger-managed; clients retain RLS-controlled reads and narrowly scoped duty-status updates, but no direct INSERT, DELETE, or TRUNCATE privileges.';

COMMIT;
