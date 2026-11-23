BEGIN;

-- Restrict direct PostgREST table access for every public table that currently
-- grants any table- or column-level operation to authenticated clients.
-- Existing permissive ownership/role policies remain intact and are ANDed
-- with this restrictive current-account-state requirement.
DO $install_active_account_rls$
DECLARE
  target_table record;
BEGIN
  FOR target_table IN
    SELECT n.nspname AS schema_name, c.relname AS table_name
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p')
       AND c.relrowsecurity
       AND (
         has_table_privilege('authenticated', c.oid, 'SELECT')
         OR has_table_privilege('authenticated', c.oid, 'INSERT')
         OR has_table_privilege('authenticated', c.oid, 'UPDATE')
         OR has_table_privilege('authenticated', c.oid, 'DELETE')
         OR has_any_column_privilege('authenticated', c.oid, 'SELECT')
         OR has_any_column_privilege('authenticated', c.oid, 'INSERT')
         OR has_any_column_privilege('authenticated', c.oid, 'UPDATE')
       )
     ORDER BY n.nspname, c.relname
  LOOP
    EXECUTE format(
      'DROP POLICY IF EXISTS tacresq_active_account_required ON %I.%I',
      target_table.schema_name,
      target_table.table_name
    );
    EXECUTE format(
      'CREATE POLICY tacresq_active_account_required ON %I.%I '
      'AS RESTRICTIVE FOR ALL TO authenticated '
      'USING ((SELECT private.tacresq_current_account_active())) '
      'WITH CHECK ((SELECT private.tacresq_current_account_active()))',
      target_table.schema_name,
      target_table.table_name
    );
  END LOOP;
END;
$install_active_account_rls$;

-- Keep a machine-readable description on the policy predicate itself: this
-- gate applies only to direct authenticated table operations. SECURITY
-- DEFINER RPCs and Storage policies require their own explicit guards.
COMMENT ON FUNCTION private.tacresq_current_account_active() IS
  'Returns true only when auth.uid() resolves to an active public.profiles row. Used by restrictive direct-table RLS policies; SECURITY DEFINER RPCs and Supabase Storage must enforce this predicate independently.';

COMMIT;
