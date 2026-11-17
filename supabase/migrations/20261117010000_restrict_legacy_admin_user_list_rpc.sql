BEGIN;

-- The Admin Console now uses api_admin_list_users_page(limit, offset, query, role).
-- Retain the old RPC definition for compatibility with database history, but
-- remove client execution so an admin session cannot request an unbounded PII list.
REVOKE ALL ON FUNCTION public.api_admin_list_users() FROM PUBLIC, anon, authenticated;

COMMENT ON FUNCTION public.api_admin_list_users() IS
  'Legacy unbounded Admin user listing retained for compatibility; client EXECUTE is revoked. Use api_admin_list_users_page instead.';

COMMIT;
