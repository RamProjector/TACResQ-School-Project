BEGIN;

-- The public RPC remains the TOTP/AAL2-enforcing wrapper. Replace only its
-- private implementation so the directory no longer returns or searches
-- account phone numbers for routine role administration.
CREATE OR REPLACE FUNCTION public.api_admin_list_users_page_internal(
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
  normalized_query text := lower(trim(coalesce(p_query, '')));
  normalized_role text := lower(trim(coalesce(p_role, 'all')));
  matching_total bigint;
  page_users jsonb;
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.profiles p
    WHERE p.auth_user_id = auth.uid() AND p.role = 'admin'
  ) THEN
    RAISE EXCEPTION 'Administrator access required' USING ERRCODE = '42501';
  END IF;

  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100
     OR p_offset IS NULL OR p_offset < 0 OR p_offset > 1000000 THEN
    RAISE EXCEPTION 'Invalid user page bounds' USING ERRCODE = '22023';
  END IF;

  IF length(normalized_query) > 120 THEN
    RAISE EXCEPTION 'Search query is too long' USING ERRCODE = '22023';
  END IF;

  IF normalized_role NOT IN ('all', 'requester', 'responder', 'driver', 'dispatcher', 'admin') THEN
    RAISE EXCEPTION 'Unsupported role filter' USING ERRCODE = '22023';
  END IF;

  SELECT count(*) INTO matching_total
  FROM public.profiles p
  WHERE (normalized_role = 'all' OR p.role = normalized_role)
    AND (
      normalized_query = ''
      OR strpos(lower(coalesce(p.name, '')), normalized_query) > 0
      OR strpos(lower(coalesce(p.email, '')), normalized_query) > 0
      OR strpos(p.id::text, normalized_query) > 0
    );

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', page.id,
    'name', page.name,
    'email', page.email,
    'role', page.role,
    'duty_status', page.duty_status,
    'created_at', page.created_at
  ) ORDER BY page.created_at DESC, page.id DESC), '[]'::jsonb)
  INTO page_users
  FROM (
    SELECT p.id, p.name, p.email, p.role, p.duty_status, p.created_at
    FROM public.profiles p
    WHERE (normalized_role = 'all' OR p.role = normalized_role)
      AND (
        normalized_query = ''
        OR strpos(lower(coalesce(p.name, '')), normalized_query) > 0
        OR strpos(lower(coalesce(p.email, '')), normalized_query) > 0
        OR strpos(p.id::text, normalized_query) > 0
      )
    ORDER BY p.created_at DESC, p.id DESC
    LIMIT p_limit OFFSET p_offset
  ) page;

  RETURN jsonb_build_object('users', page_users, 'total_count', matching_total);
END;
$function$;

REVOKE ALL ON FUNCTION public.api_admin_list_users_page_internal(integer, integer, text, text)
  FROM PUBLIC, anon, authenticated;

COMMIT;
