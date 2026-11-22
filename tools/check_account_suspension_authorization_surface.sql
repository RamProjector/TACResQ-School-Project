-- Read-only inventory of the effective post-replay authorization surface.
-- This is a discovery artifact, not proof that any path is suspension-safe.
\echo '=== Authenticated-callable public functions ==='
SELECT
  p.oid::regprocedure AS signature,
  CASE WHEN p.prosecdef THEN 'SECURITY DEFINER' ELSE 'SECURITY INVOKER' END AS execution_mode,
  coalesce(array_to_string(p.proconfig, ', '), '(default search_path)') AS function_settings,
  has_function_privilege('authenticated', p.oid, 'EXECUTE') AS authenticated_execute,
  p.prosrc AS implementation_source
FROM pg_catalog.pg_proc AS p
JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
WHERE n.nspname = 'public'
  AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
ORDER BY p.proname, p.oid::regprocedure::text;

\echo '=== Public tables, RLS, client privileges, and policies ==='
SELECT
  c.oid::regclass AS relation,
  c.relrowsecurity AS row_level_security_enabled,
  has_table_privilege('authenticated', c.oid, 'SELECT') AS authenticated_select,
  has_table_privilege('authenticated', c.oid, 'INSERT') AS authenticated_insert,
  has_table_privilege('authenticated', c.oid, 'UPDATE') AS authenticated_update,
  has_table_privilege('authenticated', c.oid, 'DELETE') AS authenticated_delete,
  coalesce((
    SELECT string_agg(
      format('%s [%s] USING=%s CHECK=%s', pol.policyname, pol.cmd, coalesce(pol.qual, '—'), coalesce(pol.with_check, '—')),
      E'\n' ORDER BY pol.policyname
    )
    FROM pg_catalog.pg_policies AS pol
    WHERE pol.schemaname = 'public' AND pol.tablename = c.relname
  ), '(no policies)') AS policies
FROM pg_catalog.pg_class AS c
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'p')
ORDER BY c.relname;

\echo '=== Supabase Storage object policies ==='
SELECT
  pol.policyname,
  pol.cmd,
  pol.roles,
  pol.qual,
  pol.with_check
FROM pg_catalog.pg_policies AS pol
WHERE pol.schemaname = 'storage'
  AND pol.tablename = 'objects'
ORDER BY pol.policyname;

\echo '=== Authenticated-callable private functions (possible definer implementations) ==='
SELECT
  p.oid::regprocedure AS signature,
  CASE WHEN p.prosecdef THEN 'SECURITY DEFINER' ELSE 'SECURITY INVOKER' END AS execution_mode,
  coalesce(array_to_string(p.proconfig, ', '), '(default search_path)') AS function_settings,
  has_function_privilege('authenticated', p.oid, 'EXECUTE') AS authenticated_execute,
  p.prosrc AS implementation_source
FROM pg_catalog.pg_proc AS p
JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
WHERE n.nspname = 'private'
  AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
ORDER BY p.proname, p.oid::regprocedure::text;
