BEGIN;

-- RLS predicates are evaluated by ordinary profile reads (including api_me
-- and PostgREST profile verification). They must be safe inside a READ ONLY
-- transaction: a SELECT predicate may inspect current account state, but must
-- not acquire a row lock.
CREATE OR REPLACE FUNCTION private.tacresq_current_account_active_read()
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
  SELECT coalesce((
    SELECT p.account_status = 'active'
      FROM public.profiles AS p
     WHERE p.auth_user_id = auth.uid()
  ), false)
$function$;

REVOKE ALL ON FUNCTION private.tacresq_current_account_active_read()
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_current_account_active_read()
  TO authenticated;

-- Keep the locking helper for write-entry triggers and explicit mutation
-- boundaries. It is intentionally not referenced by SELECT/USING policies.
COMMENT ON FUNCTION private.tacresq_current_account_active() IS
  'VOLATILE locking account-state check for write guards and mutation boundaries only; do not call from read-only RLS predicates.';
COMMENT ON FUNCTION private.tacresq_current_account_active_read() IS
  'STABLE, non-locking current account-state predicate for RLS and read-only profile verification. Mutation paths must use their explicit locking guard.';

-- Reconcile every directly accessible public table except fcm_tokens, whose
-- operation-specific restrictive policies intentionally preserve suspended
-- users' narrowly scoped self-unregister DELETE exception.
DO $replace_active_account_read_policies$
DECLARE
  target_table record;
BEGIN
  FOR target_table IN
    SELECT n.nspname AS schema_name, c.relname AS table_name
      FROM pg_catalog.pg_class AS c
      JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p')
       AND c.relrowsecurity
       AND c.relname <> 'fcm_tokens'
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
      'USING ((SELECT private.tacresq_current_account_active_read())) '
      'WITH CHECK ((SELECT private.tacresq_current_account_active_read()))',
      target_table.schema_name,
      target_table.table_name
    );
  END LOOP;
END;
$replace_active_account_read_policies$;

-- FCM has narrower operation-specific restrictive policies. Keep their
-- operation boundaries and suspended self-delete exception unchanged.
DROP POLICY IF EXISTS tacresq_fcm_select_requires_active ON public.fcm_tokens;
DROP POLICY IF EXISTS tacresq_fcm_insert_requires_active ON public.fcm_tokens;
DROP POLICY IF EXISTS tacresq_fcm_update_requires_active ON public.fcm_tokens;

CREATE POLICY tacresq_fcm_select_requires_active
  ON public.fcm_tokens AS RESTRICTIVE FOR SELECT TO authenticated
  USING ((SELECT private.tacresq_current_account_active_read()));

CREATE POLICY tacresq_fcm_insert_requires_active
  ON public.fcm_tokens AS RESTRICTIVE FOR INSERT TO authenticated
  WITH CHECK ((SELECT private.tacresq_current_account_active_read()));

CREATE POLICY tacresq_fcm_update_requires_active
  ON public.fcm_tokens AS RESTRICTIVE FOR UPDATE TO authenticated
  USING ((SELECT private.tacresq_current_account_active_read()))
  WITH CHECK ((SELECT private.tacresq_current_account_active_read()));

COMMIT;
