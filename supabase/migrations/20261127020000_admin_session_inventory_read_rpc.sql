BEGIN;

-- Read-only Auth session inventory for active administrators. Auth-owned
-- session data is never exposed through direct table grants or client queries.
CREATE OR REPLACE FUNCTION public.api_admin_list_auth_sessions_page(
  p_target_profile_id bigint,
  p_limit integer DEFAULT 25,
  p_before_created_at timestamptz DEFAULT NULL,
  p_before_session_id uuid DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_target_auth_user_id uuid;
  v_items jsonb;
  v_has_more boolean;
  v_next_created_at timestamptz;
  v_next_session_id uuid;
BEGIN
  PERFORM private.tacresq_require_admin_mfa();

  IF p_target_profile_id IS NULL OR p_target_profile_id < 1 THEN
    RAISE EXCEPTION 'A valid target profile is required'
      USING ERRCODE = '22023';
  END IF;
  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 50 THEN
    RAISE EXCEPTION 'Session page size must be between 1 and 50'
      USING ERRCODE = '22023';
  END IF;
  IF p_before_session_id IS NULL AND p_before_created_at IS NOT NULL THEN
    RAISE EXCEPTION 'A session cursor requires its session identifier'
      USING ERRCODE = '22023';
  END IF;

  -- Resolve Auth ownership from the canonical profile mapping. Never accept
  -- an Auth UUID from the browser as the target identity.
  SELECT p.auth_user_id
    INTO v_target_auth_user_id
    FROM public.profiles AS p
   WHERE p.id = p_target_profile_id;

  IF NOT FOUND OR v_target_auth_user_id IS NULL THEN
    RAISE EXCEPTION 'Target profile has no linked Auth account'
      USING ERRCODE = '22023';
  END IF;

  -- Fetch one extra row to determine whether another page exists. The
  -- explicit projection excludes IP, user-agent, factor IDs, OAuth metadata,
  -- token counters, token HMAC material, scopes, and all raw Auth fields.
  WITH page AS MATERIALIZED (
    SELECT
      s.id,
      s.created_at,
      s.updated_at,
      s.refreshed_at,
      s.aal,
      s.not_after
    FROM auth.sessions AS s
    WHERE s.user_id = v_target_auth_user_id
      AND (
        p_before_session_id IS NULL
        OR (
          p_before_created_at IS NOT NULL
          AND (
            s.created_at IS NULL
            OR s.created_at < p_before_created_at
            OR (
              s.created_at = p_before_created_at
              AND s.id < p_before_session_id
            )
          )
        )
        OR (
          p_before_created_at IS NULL
          AND s.created_at IS NULL
          AND s.id < p_before_session_id
        )
      )
    ORDER BY s.created_at DESC NULLS LAST, s.id DESC
    LIMIT p_limit + 1
  ),
  items AS MATERIALIZED (
    SELECT *
    FROM page
    ORDER BY created_at DESC NULLS LAST, id DESC
    LIMIT p_limit
  ),
  page_state AS (
    SELECT count(*) > p_limit AS has_more
    FROM page
  ),
  next_cursor AS (
    SELECT created_at, id
    FROM items
    ORDER BY created_at ASC NULLS FIRST, id ASC
    LIMIT 1
    OFFSET p_limit - 1
  )
  SELECT
    coalesce(
      (
        SELECT jsonb_agg(
          jsonb_build_object(
            'session_id', i.id,
            'created_at', i.created_at,
            'updated_at', i.updated_at,
            'last_refreshed_at', i.refreshed_at,
            'aal', i.aal::text,
            'not_after', i.not_after,
            'record_status', 'record_present',
            'validity_assessment', 'not_evaluated'
          )
          ORDER BY i.created_at DESC NULLS LAST, i.id DESC
        )
        FROM items AS i
      ),
      '[]'::jsonb
    ),
    (SELECT has_more FROM page_state),
    (SELECT created_at FROM next_cursor),
    (SELECT id FROM next_cursor)
  INTO v_items, v_has_more, v_next_created_at, v_next_session_id;

  RETURN jsonb_build_object(
    'items', v_items,
    'has_more', v_has_more,
    'next_cursor',
      CASE WHEN v_has_more THEN jsonb_build_object(
        'created_at', v_next_created_at,
        'session_id', v_next_session_id
      ) ELSE NULL END,
    'source', 'supabase_auth_sessions',
    'source_status', 'available',
    'observed_at', statement_timestamp(),
    'status_semantics',
      'A row proves that an Auth session record is present. It does not by itself prove current validity; Auth timeout policy is not evaluated by this reader.'
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_admin_list_auth_sessions_page(bigint, integer, timestamptz, uuid)
  FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.api_admin_list_auth_sessions_page(bigint, integer, timestamptz, uuid)
  TO authenticated;

COMMENT ON FUNCTION public.api_admin_list_auth_sessions_page(bigint, integer, timestamptz, uuid) IS
  'Read-only, keyset-paginated Supabase Auth session metadata for active Admins with AAL2 and TOTP. Does not expose IP, user-agent, tokens, or token HMAC material and does not assert session validity.';

COMMIT;
