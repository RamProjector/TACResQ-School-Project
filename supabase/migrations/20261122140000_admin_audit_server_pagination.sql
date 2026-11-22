BEGIN;

-- Dedicated, searchable audit-history endpoint. The public wrapper repeats the
-- shared TOTP + current Admin-role check; the table remains read-only to clients.
CREATE OR REPLACE FUNCTION public.api_admin_list_audit_logs_page(
  p_limit integer DEFAULT 25,
  p_offset integer DEFAULT 0,
  p_query text DEFAULT '',
  p_action text DEFAULT 'all',
  p_from timestamptz DEFAULT NULL,
  p_to timestamptz DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_query text := lower(btrim(coalesce(p_query, '')));
  v_events jsonb;
  v_total_count bigint;
BEGIN
  PERFORM private.tacresq_require_admin_mfa();

  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100
     OR p_offset IS NULL OR p_offset < 0 OR p_offset > 1000000 THEN
    RAISE EXCEPTION 'Audit pagination values are outside the allowed range'
      USING ERRCODE = '22023';
  END IF;
  IF length(v_query) > 120 THEN
    RAISE EXCEPTION 'Audit search must be 120 characters or fewer'
      USING ERRCODE = '22023';
  END IF;
  IF p_action IS NULL OR p_action NOT IN ('all', 'user_role_changed') THEN
    RAISE EXCEPTION 'Unsupported audit action filter'
      USING ERRCODE = '22023';
  END IF;
  IF p_from IS NOT NULL AND p_to IS NOT NULL AND p_from >= p_to THEN
    RAISE EXCEPTION 'Audit date range start must be before its end'
      USING ERRCODE = '22023';
  END IF;

  SELECT count(*)
    INTO v_total_count
    FROM public.admin_audit_logs a
   WHERE (p_action = 'all' OR a.action = p_action)
     AND (p_from IS NULL OR a.created_at >= p_from)
     AND (p_to IS NULL OR a.created_at < p_to)
     AND (
       v_query = ''
       OR strpos(lower(coalesce(a.actor_identity, '')), v_query) > 0
       OR strpos(lower(coalesce(a.reason, '')), v_query) > 0
       OR strpos(lower(coalesce(a.action, '')), v_query) > 0
       OR strpos(lower(coalesce(a.old_role, '')), v_query) > 0
       OR strpos(lower(coalesce(a.new_role, '')), v_query) > 0
       OR strpos(coalesce(a.actor_user_id::text, ''), v_query) > 0
       OR strpos(coalesce(a.target_user_id::text, ''), v_query) > 0
       OR strpos(a.created_at::text, v_query) > 0
     );

  SELECT coalesce(
      jsonb_agg(to_jsonb(page_row) ORDER BY page_row.created_at DESC, page_row.id DESC),
      '[]'::jsonb
    )
    INTO v_events
    FROM (
      SELECT a.id, a.actor_user_id, a.actor_identity, a.target_user_id,
             a.action, a.old_role, a.new_role, a.reason, a.created_at
        FROM public.admin_audit_logs a
       WHERE (p_action = 'all' OR a.action = p_action)
         AND (p_from IS NULL OR a.created_at >= p_from)
         AND (p_to IS NULL OR a.created_at < p_to)
         AND (
           v_query = ''
           OR strpos(lower(coalesce(a.actor_identity, '')), v_query) > 0
           OR strpos(lower(coalesce(a.reason, '')), v_query) > 0
           OR strpos(lower(coalesce(a.action, '')), v_query) > 0
           OR strpos(lower(coalesce(a.old_role, '')), v_query) > 0
           OR strpos(lower(coalesce(a.new_role, '')), v_query) > 0
           OR strpos(coalesce(a.actor_user_id::text, ''), v_query) > 0
           OR strpos(coalesce(a.target_user_id::text, ''), v_query) > 0
           OR strpos(a.created_at::text, v_query) > 0
         )
       ORDER BY a.created_at DESC, a.id DESC
       LIMIT p_limit OFFSET p_offset
    ) page_row;

  RETURN jsonb_build_object(
    'events', v_events,
    'total_count', v_total_count
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_admin_list_audit_logs_page(integer, integer, text, text, timestamptz, timestamptz)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_list_audit_logs_page(integer, integer, text, text, timestamptz, timestamptz)
  TO authenticated;

COMMIT;
