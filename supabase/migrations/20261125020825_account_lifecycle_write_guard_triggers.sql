BEGIN;

-- SECURITY DEFINER RPCs bypass RLS, but their table writes still fire normal
-- row triggers. Install a caller-state guard on every public table so a stale,
-- still-valid JWT cannot commit a write after the suspension transaction wins.
CREATE OR REPLACE FUNCTION private.tacresq_reject_suspended_account_write()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  -- Trusted Auth triggers and service-role jobs without an end-user JWT retain
  -- their existing server-side write paths. When a caller JWT is present,
  -- require its current profile to remain active and acquire the profile share
  -- lock so suspension and writes serialize on the same row.
  IF auth.uid() IS NOT NULL
     AND NOT private.tacresq_current_account_active() THEN
    -- Allow only the authenticated self-unregister RPC to remove its own
    -- installation token during sign-out. Keep this exception scoped by
    -- operation, relation, transaction-local marker, and profile ownership.
    IF TG_OP = 'DELETE' THEN
      IF TG_TABLE_SCHEMA = 'public' THEN
        IF TG_TABLE_NAME = 'fcm_tokens' THEN
          IF current_setting('tacresq.allow_suspended_fcm_self_cleanup', true) = 'on' THEN
            IF OLD.profile_id = private.tacresq_auth_profile_id_for_token_cleanup() THEN
              RETURN OLD;
            END IF;
          END IF;
        END IF;
      END IF;
    END IF;

    RAISE EXCEPTION 'Suspended accounts cannot perform protected writes'
      USING ERRCODE = '42501';
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_reject_suspended_account_write()
  FROM PUBLIC, anon, authenticated, service_role;

DO $install_account_write_guard$
DECLARE
  target_table record;
BEGIN
  FOR target_table IN
    SELECT n.nspname AS schema_name, c.relname AS table_name
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p')
     ORDER BY n.nspname, c.relname
  LOOP
    EXECUTE format(
      'DROP TRIGGER IF EXISTS tacresq_active_account_write_guard ON %I.%I',
      target_table.schema_name,
      target_table.table_name
    );
    EXECUTE format(
      'CREATE TRIGGER tacresq_active_account_write_guard '
      'BEFORE INSERT OR UPDATE OR DELETE ON %I.%I '
      'FOR EACH ROW EXECUTE FUNCTION private.tacresq_reject_suspended_account_write()',
      target_table.schema_name,
      target_table.table_name
    );
  END LOOP;
END;
$install_account_write_guard$;

COMMENT ON FUNCTION private.tacresq_reject_suspended_account_write() IS
  'Rejects public-table row writes made with a suspended end-user JWT, including writes inside SECURITY DEFINER RPCs. Trusted no-user-JWT server/Auth operations retain their existing path.';

COMMIT;
