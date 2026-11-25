BEGIN;

-- Reconcile tables introduced by later feature migrations. The original
-- lifecycle RLS migration installs the restrictive policy for the schema
-- inventory present at that point; later migrations can add public tables
-- with authenticated grants. Re-run the same narrowly scoped inventory so
-- every directly accessible RLS-enabled table keeps the active-account gate.
DO $reconcile_active_account_rls$
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
$reconcile_active_account_rls$;

COMMIT;
