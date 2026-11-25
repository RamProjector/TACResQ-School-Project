BEGIN;

-- Admin website access is allowed only while the administrator's own profile
-- remains active. This applies to all browser-callable Admin RPC wrappers that
-- share this helper, including the account directory and lifecycle history.
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
      AND p.account_status = 'active'
  ) THEN
    RAISE EXCEPTION 'Active administrator access required'
      USING ERRCODE = '42501';
  END IF;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_require_admin_mfa()
  FROM PUBLIC, anon, authenticated;

-- Include current lifecycle state in the existing bounded website directory.
-- The underlying implementation remains private and continues to enforce its
-- existing search, pagination, and data-minimization rules.
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
DECLARE
  v_page jsonb;
  v_users jsonb;
  v_expected_count integer;
BEGIN
  PERFORM private.tacresq_require_admin_mfa();
  v_page := public.api_admin_list_users_page_internal(p_limit, p_offset, p_query, p_role);

  SELECT count(*)::integer
    INTO v_expected_count
    FROM jsonb_array_elements(coalesce(v_page -> 'users', '[]'::jsonb));

  SELECT coalesce(
    jsonb_agg(
      entry.value || jsonb_build_object('account_status', p.account_status)
      ORDER BY entry.ordinality
    ),
    '[]'::jsonb
  )
    INTO v_users
    FROM jsonb_array_elements(coalesce(v_page -> 'users', '[]'::jsonb))
      WITH ORDINALITY AS entry(value, ordinality)
    JOIN public.profiles p
      ON p.id = (entry.value ->> 'id')::bigint;

  IF jsonb_array_length(v_users) <> v_expected_count THEN
    RAISE EXCEPTION 'The Admin directory could not resolve every account status'
      USING ERRCODE = 'P0001';
  END IF;

  RETURN jsonb_set(v_page, '{users}', v_users, true);
END;
$function$;

REVOKE ALL ON FUNCTION public.api_admin_list_users_page(integer, integer, text, text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_list_users_page(integer, integer, text, text)
  TO authenticated;

-- The transition function independently checks AAL2/TOTP, current active
-- Admin state, self-change, protected Admin targets, and atomic audit writes.
-- Expose only this narrowly scoped transition RPC to authenticated clients.
GRANT EXECUTE ON FUNCTION public.api_admin_set_account_status(bigint, text, text)
  TO authenticated;

COMMENT ON FUNCTION public.api_admin_set_account_status(bigint, text, text) IS
  'Website Admin account suspension/reactivation. Authenticated execution is limited by the function''s AAL2/TOTP and active-Admin checks; transitions and immutable audit entries commit atomically.';

COMMIT;
