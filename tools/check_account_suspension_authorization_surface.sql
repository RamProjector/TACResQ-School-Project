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
  table_owner.rolname AS relation_owner,
  c.relrowsecurity AS row_level_security_enabled,
  c.relforcerowsecurity AS row_level_security_forced,
  (table_owner.rolname IN ('postgres', 'supabase_admin', 'service_role')) AS owner_is_privileged_service_role,
  has_table_privilege('authenticated', c.oid, 'SELECT') AS authenticated_select,
  has_table_privilege('authenticated', c.oid, 'INSERT') AS authenticated_insert,
  has_table_privilege('authenticated', c.oid, 'UPDATE') AS authenticated_update,
  has_table_privilege('authenticated', c.oid, 'DELETE') AS authenticated_delete,
  has_table_privilege('anon', c.oid, 'TRUNCATE') AS anon_truncate,
  has_table_privilege('authenticated', c.oid, 'TRUNCATE') AS authenticated_truncate,
  has_table_privilege('anon', c.oid, 'REFERENCES') AS anon_references,
  has_table_privilege('authenticated', c.oid, 'REFERENCES') AS authenticated_references,
  has_table_privilege('anon', c.oid, 'TRIGGER') AS anon_trigger,
  has_table_privilege('authenticated', c.oid, 'TRIGGER') AS authenticated_trigger,
  coalesce((
    SELECT string_agg(
      format('%s [%s %s] USING=%s CHECK=%s', pol.policyname, pol.permissive, pol.cmd, coalesce(pol.qual, '—'), coalesce(pol.with_check, '—')),
      E'\n' ORDER BY pol.policyname
    )
    FROM pg_catalog.pg_policies AS pol
    WHERE pol.schemaname = 'public' AND pol.tablename = c.relname
  ), '(no policies)') AS policies
FROM pg_catalog.pg_class AS c
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
JOIN pg_catalog.pg_roles AS table_owner ON table_owner.oid = c.relowner
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'p')
ORDER BY c.relname;

\echo '=== Supabase Storage object policies ==='
SELECT
  pol.policyname,
  pol.permissive,
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


\echo '=== Authenticated-readable public views and materialized views ==='
-- Views can expose rows through their owner unless security_invoker is enabled.
-- Keep them separate from ordinary-table RLS inventory: their effective
-- authorization semantics are not established by the base-table policy list.
SELECT
  c.oid::regclass AS relation,
  CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' END AS relation_kind,
  has_table_privilege('authenticated', c.oid, 'SELECT') AS authenticated_select,
  coalesce(array_to_string(c.reloptions, ', '), '(default view options)') AS relation_options,
  pg_catalog.pg_get_viewdef(c.oid, true) AS view_definition
FROM pg_catalog.pg_class AS c
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind IN ('v', 'm')
  AND has_table_privilege('authenticated', c.oid, 'SELECT')
ORDER BY c.relname;


\echo '=== Enabled user-defined triggers on public tables ==='
-- Trigger functions can mutate data or reject/permit writes independently of
-- the calling RPC. Include the function body and trigger definition so source
-- review can trace SECURITY DEFINER execution and implicit side effects.
SELECT
  t.tgrelid::regclass AS table_name,
  t.tgname AS trigger_name,
  CASE WHEN t.tgenabled = 'D' THEN 'DISABLED'
       WHEN t.tgenabled = 'R' THEN 'REPLICA'
       WHEN t.tgenabled = 'A' THEN 'ALWAYS'
       ELSE 'ORIGIN' END AS enabled_mode,
  p.oid::regprocedure AS trigger_function,
  p.prosecdef AS trigger_function_security_definer,
  coalesce(array_to_string(p.proconfig, ', '), '(default search_path)') AS function_settings,
  pg_catalog.pg_get_triggerdef(t.oid, true) AS trigger_definition,
  p.prosrc AS trigger_function_source
FROM pg_catalog.pg_trigger AS t
JOIN pg_catalog.pg_class AS c ON c.oid = t.tgrelid
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
JOIN pg_catalog.pg_proc AS p ON p.oid = t.tgfoid
WHERE n.nspname = 'public'
  AND NOT t.tgisinternal
ORDER BY c.relname, t.tgname;


\echo '=== Auth-schema triggers (including Auth user lifecycle hooks) ==='
-- Auth triggers are outside the public-table inventory but can create or
-- mutate application profiles during signup or account lifecycle operations.
-- Keep this catalog separate so Auth-owned tables are not mistaken for
-- application tables and every trigger's SECURITY DEFINER boundary is visible.
SELECT
  t.tgrelid::regclass AS table_name,
  t.tgname AS trigger_name,
  CASE WHEN t.tgenabled = 'D' THEN 'DISABLED'
       WHEN t.tgenabled = 'R' THEN 'REPLICA'
       WHEN t.tgenabled = 'A' THEN 'ALWAYS'
       ELSE 'ORIGIN' END AS enabled_mode,
  p.oid::regprocedure AS trigger_function,
  p.prosecdef AS trigger_function_security_definer,
  coalesce(array_to_string(p.proconfig, ', '), '(default search_path)') AS function_settings,
  pg_catalog.pg_get_triggerdef(t.oid, true) AS trigger_definition,
  p.prosrc AS trigger_function_source
FROM pg_catalog.pg_trigger AS t
JOIN pg_catalog.pg_class AS c ON c.oid = t.tgrelid
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
JOIN pg_catalog.pg_proc AS p ON p.oid = t.tgfoid
WHERE n.nspname = 'auth'
  AND NOT t.tgisinternal
ORDER BY c.relname, t.tgname;


\echo '=== Effective column-level privileges (including grants hidden by table-only checks) ==='
-- PostgreSQL permits column grants independently of table grants. Report
-- both client roles so a future table-level revocation cannot overlook a
-- surviving column-level path.
SELECT
  c.oid::regclass AS relation,
  a.attname AS column_name,
  has_column_privilege('anon', c.oid, a.attnum, 'SELECT') AS anon_select,
  has_column_privilege('anon', c.oid, a.attnum, 'INSERT') AS anon_insert,
  has_column_privilege('anon', c.oid, a.attnum, 'UPDATE') AS anon_update,
  has_column_privilege('authenticated', c.oid, a.attnum, 'SELECT') AS authenticated_select,
  has_column_privilege('authenticated', c.oid, a.attnum, 'INSERT') AS authenticated_insert,
  has_column_privilege('authenticated', c.oid, a.attnum, 'UPDATE') AS authenticated_update,
  has_column_privilege('anon', c.oid, a.attnum, 'REFERENCES') AS anon_references,
  has_column_privilege('authenticated', c.oid, a.attnum, 'REFERENCES') AS authenticated_references
FROM pg_catalog.pg_class AS c
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.oid
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
  AND a.attnum > 0
  AND NOT a.attisdropped
  AND (
    has_column_privilege('anon', c.oid, a.attnum, 'SELECT')
    OR has_column_privilege('anon', c.oid, a.attnum, 'INSERT')
    OR has_column_privilege('anon', c.oid, a.attnum, 'UPDATE')
    OR has_column_privilege('authenticated', c.oid, a.attnum, 'SELECT')
    OR has_column_privilege('authenticated', c.oid, a.attnum, 'INSERT')
    OR has_column_privilege('authenticated', c.oid, a.attnum, 'UPDATE')
    OR has_column_privilege('anon', c.oid, a.attnum, 'REFERENCES')
    OR has_column_privilege('authenticated', c.oid, a.attnum, 'REFERENCES')
  )
ORDER BY c.relname, a.attnum;

\echo '=== Effective client privileges on public sequences ==='
-- Sequence USAGE/UPDATE can survive table privilege changes and can expose
-- identifier-generation operations. Record current effective grants.
SELECT
  c.oid::regclass AS sequence_name,
  has_sequence_privilege('anon', c.oid, 'USAGE') AS anon_usage,
  has_sequence_privilege('anon', c.oid, 'SELECT') AS anon_select,
  has_sequence_privilege('anon', c.oid, 'UPDATE') AS anon_update,
  has_sequence_privilege('authenticated', c.oid, 'USAGE') AS authenticated_usage,
  has_sequence_privilege('authenticated', c.oid, 'SELECT') AS authenticated_select,
  has_sequence_privilege('authenticated', c.oid, 'UPDATE') AS authenticated_update
FROM pg_catalog.pg_class AS c
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind = 'S'
  AND (
    has_sequence_privilege('anon', c.oid, 'USAGE')
    OR has_sequence_privilege('anon', c.oid, 'SELECT')
    OR has_sequence_privilege('anon', c.oid, 'UPDATE')
    OR has_sequence_privilege('authenticated', c.oid, 'USAGE')
    OR has_sequence_privilege('authenticated', c.oid, 'SELECT')
    OR has_sequence_privilege('authenticated', c.oid, 'UPDATE')
  )
ORDER BY c.relname;

\echo '=== Client schema USAGE privileges ==='
-- Schema USAGE is a prerequisite for resolving objects through PostgREST.
-- Keep it visible alongside object-level grants.
SELECT
  schema_name,
  has_schema_privilege('anon', schema_name, 'USAGE') AS anon_usage,
  has_schema_privilege('anon', schema_name, 'CREATE') AS anon_create,
  has_schema_privilege('authenticated', schema_name, 'USAGE') AS authenticated_usage,
  has_schema_privilege('authenticated', schema_name, 'CREATE') AS authenticated_create
FROM (VALUES ('public'), ('private'), ('storage'), ('auth')) AS schemas(schema_name)
ORDER BY schema_name;


\echo '=== All private SECURITY DEFINER functions (including non-client-callable helpers) ==='
-- A private helper may be reached from a SECURITY DEFINER wrapper even when
-- authenticated has no EXECUTE grant on the helper itself. Inventory every
-- private definer body, not only helpers directly executable by client roles.
SELECT
  p.oid::regprocedure AS signature,
  has_function_privilege('anon', p.oid, 'EXECUTE') AS anon_execute,
  has_function_privilege('authenticated', p.oid, 'EXECUTE') AS authenticated_execute,
  coalesce(array_to_string(p.proconfig, ', '), '(default search_path)') AS function_settings,
  p.prosrc AS implementation_source
FROM pg_catalog.pg_proc AS p
JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
WHERE n.nspname = 'private'
  AND p.prokind = 'f'
  AND p.prosecdef
ORDER BY p.proname, p.oid::regprocedure::text;


\echo '=== Client membership in superuser or BYPASSRLS roles ==='
-- RLS guarantees are invalidated if a client role inherits a role with
-- BYPASSRLS or superuser capability. Report indirect membership as well.
SELECT
  client_role.rolname AS client_role,
  elevated_role.rolname AS elevated_role,
  elevated_role.rolsuper AS elevated_role_is_superuser,
  elevated_role.rolbypassrls AS elevated_role_bypasses_rls,
  pg_catalog.pg_has_role(client_role.oid, elevated_role.oid, 'MEMBER') AS is_member
FROM pg_catalog.pg_roles AS client_role
CROSS JOIN pg_catalog.pg_roles AS elevated_role
WHERE client_role.rolname IN ('anon', 'authenticated')
  AND (elevated_role.rolsuper OR elevated_role.rolbypassrls)
  AND pg_catalog.pg_has_role(client_role.oid, elevated_role.oid, 'MEMBER')
ORDER BY client_role.rolname, elevated_role.rolname;


\echo '=== Global and schema-scoped default privileges ==='
-- Default ACLs determine privileges inherited by objects created in the
-- future. Current-object grants alone cannot reveal these future exposure
-- paths. Include global defaults and schema-specific overrides for all
-- object types, and retain PUBLIC/role grantees explicitly.
SELECT
  owner_role.rolname AS object_owner,
  coalesce(schema_name.nspname, '<global>') AS schema_scope,
  CASE defaults.defaclobjtype
    WHEN 'r' THEN 'TABLES'
    WHEN 'S' THEN 'SEQUENCES'
    WHEN 'f' THEN 'FUNCTIONS'
    WHEN 'T' THEN 'TYPES'
    WHEN 'n' THEN 'SCHEMAS'
    ELSE defaults.defaclobjtype::text
  END AS object_type,
  CASE WHEN expanded.grantee = 0 THEN 'PUBLIC'
       ELSE grantee_role.rolname END AS grantee,
  expanded.privilege_type,
  expanded.is_grantable
FROM pg_catalog.pg_default_acl AS defaults
JOIN pg_catalog.pg_roles AS owner_role
  ON owner_role.oid = defaults.defaclrole
LEFT JOIN pg_catalog.pg_namespace AS schema_name
  ON schema_name.oid = defaults.defaclnamespace
CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) AS expanded
LEFT JOIN pg_catalog.pg_roles AS grantee_role
  ON grantee_role.oid = expanded.grantee
ORDER BY owner_role.rolname, schema_scope, object_type, grantee, expanded.privilege_type;

\\echo '=== Anon-callable public routines (effective EXECUTE, including PUBLIC grants) ==='
-- Anonymous exposure is a separate authorization surface from authenticated
-- access. has_function_privilege includes inherited and PUBLIC privileges.
SELECT
  p.oid::regprocedure AS signature,
  owner_role.rolname AS routine_owner,
  CASE WHEN p.prosecdef THEN 'SECURITY DEFINER' ELSE 'SECURITY INVOKER' END AS execution_mode,
  coalesce(array_to_string(p.proconfig, ', '), '(default search_path)') AS function_settings,
  has_function_privilege('anon', p.oid, 'EXECUTE') AS anon_execute,
  has_function_privilege('authenticated', p.oid, 'EXECUTE') AS authenticated_execute,
  p.prosrc AS implementation_source
FROM pg_catalog.pg_proc AS p
JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
JOIN pg_catalog.pg_roles AS owner_role ON owner_role.oid = p.proowner
WHERE n.nspname = 'public'
  AND p.prokind IN ('f', 'p')
  AND has_function_privilege('anon', p.oid, 'EXECUTE')
ORDER BY p.proname, p.oid::regprocedure::text;


\\echo '=== Anon-callable private routines (effective EXECUTE, including PUBLIC grants) ==='
-- Private-schema exposure is still relevant when the schema is exposed or
-- callable through another client path. Record it independently of public RPCs.
SELECT
  p.oid::regprocedure AS signature,
  owner_role.rolname AS routine_owner,
  CASE WHEN p.prosecdef THEN 'SECURITY DEFINER' ELSE 'SECURITY INVOKER' END AS execution_mode,
  coalesce(array_to_string(p.proconfig, ', '), '(default search_path)') AS function_settings,
  has_function_privilege('anon', p.oid, 'EXECUTE') AS anon_execute,
  has_function_privilege('authenticated', p.oid, 'EXECUTE') AS authenticated_execute,
  p.prosrc AS implementation_source
FROM pg_catalog.pg_proc AS p
JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
JOIN pg_catalog.pg_roles AS owner_role ON owner_role.oid = p.proowner
WHERE n.nspname = 'private'
  AND p.prokind IN ('f', 'p')
  AND has_function_privilege('anon', p.oid, 'EXECUTE')
ORDER BY p.proname, p.oid::regprocedure::text;
