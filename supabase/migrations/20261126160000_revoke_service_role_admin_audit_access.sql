BEGIN;

-- service_role is not the Admin web identity. The Admin UI reaches these
-- SECURITY DEFINER APIs with an authenticated Admin JWT; their owner performs
-- the underlying audit-table reads/writes. No inspected server consumer needs
-- direct service_role access to the append-only audit table or Admin RPC family.
REVOKE ALL PRIVILEGES ON TABLE public.admin_audit_logs FROM service_role;
REVOKE ALL PRIVILEGES ON SEQUENCE public.admin_audit_logs_id_seq FROM PUBLIC, anon, authenticated, service_role;

-- Keep the Admin RPC surface tied to authenticated user sessions. Internal
-- helpers remain callable by their postgres owner from SECURITY DEFINER wrappers.
REVOKE EXECUTE ON FUNCTION public.api_admin_analytics_summary(timestamptz, timestamptz) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_analytics_summary_internal(timestamptz, timestamptz) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_list_account_lifecycle_events(integer, integer) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_list_audit_logs(integer) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_list_audit_logs_internal(integer) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_list_audit_logs_page(integer, integer, text, text, timestamptz, timestamptz, bigint, bigint) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_list_investigation_events(integer, integer, text, text, text, timestamptz, timestamptz, bigint, bigint, bigint) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_list_users() FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_list_users_page(integer, integer, text, text) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_list_users_page_internal(integer, integer, text, text) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_set_account_status(bigint, text, text, text) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_set_user_role(bigint, text, text) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.api_admin_set_user_role_internal(bigint, text, text) FROM service_role;

COMMIT;
