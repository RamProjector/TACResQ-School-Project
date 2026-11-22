BEGIN;

-- Require a verified Supabase MFA session before any browser-callable Admin RPC.
-- The helper is private and is only invoked by the SECURITY DEFINER wrappers below.
CREATE OR REPLACE FUNCTION private.tacresq_require_admin_mfa()
RETURNS void
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  IF auth.uid() IS NULL
     OR (auth.jwt() ->> 'aal') IS DISTINCT FROM 'aal2'
     OR NOT EXISTS (
       SELECT 1
       FROM jsonb_array_elements(coalesce(auth.jwt() -> 'amr', '[]'::jsonb)) AS method
       WHERE CASE jsonb_typeof(method)
         WHEN 'object' THEN method ->> 'method'
         WHEN 'string' THEN method #>> '{}'
         ELSE NULL
       END = 'totp'
     ) THEN
    RAISE EXCEPTION 'Administrator TOTP verification required'
      USING ERRCODE = '42501';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.profiles p
    WHERE p.auth_user_id = auth.uid()
      AND p.role = 'admin'
  ) THEN
    RAISE EXCEPTION 'Administrator access required'
      USING ERRCODE = '42501';
  END IF;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_require_admin_mfa()
  FROM PUBLIC, anon, authenticated;

-- Preserve the reviewed implementations as private internal functions. Their
-- old client grants are removed; only the MFA-enforcing wrappers remain callable.
-- Keep the legacy unbounded RPC name for compatibility, but it remains
-- explicitly unavailable to every client role.
REVOKE ALL ON FUNCTION public.api_admin_list_users()
  FROM PUBLIC, anon, authenticated;

ALTER FUNCTION public.api_admin_list_users_page(integer, integer, text, text)
  RENAME TO api_admin_list_users_page_internal;
REVOKE ALL ON FUNCTION public.api_admin_list_users_page_internal(integer, integer, text, text)
  FROM PUBLIC, anon, authenticated;

ALTER FUNCTION public.api_admin_set_user_role(bigint, text, text)
  RENAME TO api_admin_set_user_role_internal;
REVOKE ALL ON FUNCTION public.api_admin_set_user_role_internal(bigint, text, text)
  FROM PUBLIC, anon, authenticated;

ALTER FUNCTION public.api_admin_list_audit_logs(integer)
  RENAME TO api_admin_list_audit_logs_internal;
REVOKE ALL ON FUNCTION public.api_admin_list_audit_logs_internal(integer)
  FROM PUBLIC, anon, authenticated;

ALTER FUNCTION public.api_admin_analytics_summary(timestamptz, timestamptz)
  RENAME TO api_admin_analytics_summary_internal;
REVOKE ALL ON FUNCTION public.api_admin_analytics_summary_internal(timestamptz, timestamptz)
  FROM PUBLIC, anon, authenticated;

-- Keep the legacy unbounded user-list RPC revoked; the console uses bounded pagination.

CREATE OR REPLACE FUNCTION public.api_admin_list_users_page(
  p_limit integer DEFAULT 25,
  p_offset integer DEFAULT 0,
  p_query text DEFAULT '',
  p_role text DEFAULT 'all'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  PERFORM private.tacresq_require_admin_mfa();
  RETURN public.api_admin_list_users_page_internal(p_limit, p_offset, p_query, p_role);
END;
$function$;

CREATE OR REPLACE FUNCTION public.api_admin_set_user_role(
  p_user_id bigint,
  p_role text,
  p_reason text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  PERFORM private.tacresq_require_admin_mfa();
  RETURN public.api_admin_set_user_role_internal(p_user_id, p_role, p_reason);
END;
$function$;

CREATE OR REPLACE FUNCTION public.api_admin_list_audit_logs(p_limit integer DEFAULT 100)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  PERFORM private.tacresq_require_admin_mfa();
  RETURN public.api_admin_list_audit_logs_internal(p_limit);
END;
$function$;

CREATE OR REPLACE FUNCTION public.api_admin_analytics_summary(
  p_from timestamptz,
  p_to timestamptz
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  PERFORM private.tacresq_require_admin_mfa();
  RETURN public.api_admin_analytics_summary_internal(p_from, p_to);
END;
$function$;

REVOKE ALL ON FUNCTION public.api_admin_list_users_page(integer, integer, text, text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_list_users_page(integer, integer, text, text)
  TO authenticated;

REVOKE ALL ON FUNCTION public.api_admin_set_user_role(bigint, text, text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_set_user_role(bigint, text, text)
  TO authenticated;

REVOKE ALL ON FUNCTION public.api_admin_list_audit_logs(integer)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_list_audit_logs(integer)
  TO authenticated;

REVOKE ALL ON FUNCTION public.api_admin_analytics_summary(timestamptz, timestamptz)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_analytics_summary(timestamptz, timestamptz)
  TO authenticated;

-- Direct table reads of audit history must also require AAL2; a permissive
-- admin-role policy alone is not enough to enforce MFA.
DROP POLICY IF EXISTS admin_audit_logs_require_aal2 ON public.admin_audit_logs;
CREATE POLICY admin_audit_logs_require_aal2
  ON public.admin_audit_logs
  AS RESTRICTIVE
  FOR SELECT
  TO authenticated
  USING (
    (SELECT auth.jwt() ->> 'aal') = 'aal2'
    AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(coalesce((SELECT auth.jwt()) -> 'amr', '[]'::jsonb)) AS method
      WHERE CASE jsonb_typeof(method)
        WHEN 'object' THEN method ->> 'method'
        WHEN 'string' THEN method #>> '{}'
        ELSE NULL
      END = 'totp'
    )
  );

COMMIT;
