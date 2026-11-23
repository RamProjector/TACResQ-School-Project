BEGIN;

-- Service-role workers bypass RLS, so the delivery claim itself must enforce
-- account lifecycle. A stale unread notification must never enqueue a push
-- to a profile that has since been suspended.
CREATE OR REPLACE FUNCTION public.api_claim_fcm_delivery_batch(
  p_limit integer DEFAULT 50,
  p_lease_seconds integer DEFAULT 600
)
RETURNS TABLE (
  attempt_id bigint,
  notification_id bigint,
  profile_id bigint,
  token text,
  incident_id bigint,
  event_type text,
  attempt_count integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 50 THEN
    RAISE EXCEPTION 'FCM delivery batch limit must be between 1 and 50'
      USING ERRCODE = '22023';
  END IF;

  IF p_lease_seconds IS NULL OR p_lease_seconds < 60 OR p_lease_seconds > 1800 THEN
    RAISE EXCEPTION 'FCM delivery lease must be between 60 and 1800 seconds'
      USING ERRCODE = '22023';
  END IF;

  RETURN QUERY
  WITH candidates AS MATERIALIZED (
    SELECT n.id AS notification_id, n.recipient_user_id AS profile_id, t.token
    FROM public.user_notifications AS n
    JOIN public.profiles AS recipient_profile
      ON recipient_profile.id = n.recipient_user_id
     AND recipient_profile.account_status = 'active'
    JOIN public.fcm_tokens AS t
      ON t.profile_id = recipient_profile.id
    WHERE n.read_at IS NULL
      AND NOT EXISTS (
        SELECT 1 FROM public.fcm_delivery_attempts AS existing
        WHERE existing.notification_id = n.id
          AND existing.token = t.token
          AND (
            existing.status IN ('sent', 'invalid_token', 'permanent_failure')
            OR (existing.status = 'claimed' AND (
              existing.attempt_count >= 8
              OR existing.attempted_at > statement_timestamp() - make_interval(secs => p_lease_seconds)
            ))
            OR (existing.status = 'failed' AND (
              existing.attempt_count >= 8
              OR existing.next_attempt_at > statement_timestamp()
            ))
          )
      )
    ORDER BY n.id ASC, t.id ASC
    LIMIT p_limit
  ),
  claimed AS (
    INSERT INTO public.fcm_delivery_attempts AS existing (
      notification_id, profile_id, token, status, attempted_at,
      updated_at, attempt_count, next_attempt_at
    )
    SELECT notification_id, profile_id, token, 'claimed', statement_timestamp(),
      statement_timestamp(), 1, statement_timestamp()
    FROM candidates
    ON CONFLICT ON CONSTRAINT fcm_delivery_attempts_notification_id_token_key DO UPDATE
      SET status = 'claimed', attempted_at = statement_timestamp(),
          updated_at = statement_timestamp(),
          attempt_count = existing.attempt_count + 1,
          next_attempt_at = statement_timestamp()
      WHERE (
        (existing.status = 'failed' AND existing.attempt_count < 8
          AND existing.next_attempt_at <= statement_timestamp())
        OR (existing.status = 'claimed' AND existing.attempt_count < 8
          AND existing.attempted_at <= statement_timestamp() - make_interval(secs => p_lease_seconds))
      )
    RETURNING existing.id, existing.notification_id, existing.profile_id,
      existing.token, existing.attempt_count
  )
  SELECT claimed.id, claimed.notification_id, claimed.profile_id,
    claimed.token, n.incident_id, n.event_type, claimed.attempt_count
  FROM claimed
  JOIN public.user_notifications AS n ON n.id = claimed.notification_id
  ORDER BY claimed.notification_id ASC, claimed.profile_id ASC;
END;
$function$;

REVOKE ALL ON FUNCTION public.api_claim_fcm_delivery_batch(integer, integer)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.api_claim_fcm_delivery_batch(integer, integer)
  TO service_role;

COMMIT;
