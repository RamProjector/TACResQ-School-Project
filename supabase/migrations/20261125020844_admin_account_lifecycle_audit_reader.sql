BEGIN;

-- Expose immutable account lifecycle transitions to the existing Admin
-- investigation workflow without granting clients direct access to the ledger.
-- Keep this as a separate bounded reader until the unified feed can be safely
-- extended with full cross-source pagination.
CREATE OR REPLACE FUNCTION public.api_admin_list_account_lifecycle_events(
  p_limit integer DEFAULT 25,
  p_offset integer DEFAULT 0
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_events jsonb;
  v_total bigint;
BEGIN
  PERFORM private.tacresq_require_admin_mfa();

  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100
     OR p_offset IS NULL OR p_offset < 0 OR p_offset > 1000000 THEN
    RAISE EXCEPTION 'Account lifecycle pagination values are outside the allowed range'
      USING ERRCODE = '22023';
  END IF;

  -- Count and page rows in one SQL statement so both values use the same
  -- PostgreSQL statement snapshot. Separate READ COMMITTED statements can
  -- otherwise observe different concurrent audit inserts.
  WITH total AS (
    SELECT count(*)::bigint AS total_count
      FROM public.account_lifecycle_audit_logs
  ),
  lifecycle_page AS (
    SELECT
      a.id,
      a.actor_profile_id,
      a.actor_identity,
      a.target_profile_id,
      coalesce(target.name, 'Profile #' || a.target_profile_id::text) AS target_name,
      a.action,
      a.previous_status,
      a.new_status,
      a.reason,
      a.created_at
    FROM public.account_lifecycle_audit_logs AS a
    LEFT JOIN public.profiles AS target ON target.id = a.target_profile_id
    ORDER BY a.created_at DESC, a.id DESC
    LIMIT p_limit OFFSET p_offset
  )
  SELECT
    total.total_count,
    coalesce(
      jsonb_agg(
        to_jsonb(lifecycle_page)
        ORDER BY lifecycle_page.created_at DESC, lifecycle_page.id DESC
      ) FILTER (WHERE lifecycle_page.id IS NOT NULL),
      '[]'::jsonb
    )
    INTO v_total, v_events
    FROM total
    LEFT JOIN lifecycle_page ON true
    GROUP BY total.total_count;

  RETURN jsonb_build_object('events', v_events, 'total_count', v_total);
END;
$function$;

REVOKE ALL ON FUNCTION public.api_admin_list_account_lifecycle_events(integer, integer)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_admin_list_account_lifecycle_events(integer, integer)
  TO authenticated;

COMMENT ON FUNCTION public.api_admin_list_account_lifecycle_events(integer, integer) IS
  'Bounded, read-only account suspension/reactivation history for active Admins with verified TOTP/AAL2. Direct ledger access remains revoked; no denied-attempt logging is implied.';

COMMIT;
