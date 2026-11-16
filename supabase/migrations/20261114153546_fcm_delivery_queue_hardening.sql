BEGIN;

-- Durable retry state is separate from the user's unread/read inbox state.
-- A push delivery attempt must not be re-selected forever just because its
-- inbox row remains unread.
ALTER TABLE public.fcm_delivery_attempts
  ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE public.fcm_delivery_attempts
  ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE public.fcm_delivery_attempts
  DROP CONSTRAINT IF EXISTS fcm_delivery_attempts_status_check;
ALTER TABLE public.fcm_delivery_attempts
  ADD CONSTRAINT fcm_delivery_attempts_status_check
  CHECK (status IN ('claimed', 'sent', 'invalid_token', 'failed', 'permanent_failure'));

ALTER TABLE public.fcm_delivery_attempts
  DROP CONSTRAINT IF EXISTS fcm_delivery_attempts_attempt_count_check;
ALTER TABLE public.fcm_delivery_attempts
  ADD CONSTRAINT fcm_delivery_attempts_attempt_count_check
  CHECK (attempt_count BETWEEN 0 AND 8);

CREATE INDEX IF NOT EXISTS fcm_delivery_attempts_due_idx
  ON public.fcm_delivery_attempts (next_attempt_at, notification_id)
  WHERE status = 'failed';

-- The only caller is the trusted backend using the Supabase service-role key.
-- ON CONFLICT serializes competing claims for the unique notification/token pair;
-- the conditional update re-checks the lease/retry eligibility after lock acquisition.
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
    SELECT
      n.id AS notification_id,
      n.recipient_user_id AS profile_id,
      t.token
    FROM public.user_notifications AS n
    JOIN public.fcm_tokens AS t
      ON t.profile_id = n.recipient_user_id
    WHERE n.read_at IS NULL
      AND NOT EXISTS (
        SELECT 1
        FROM public.fcm_delivery_attempts AS existing
        WHERE existing.notification_id = n.id
          AND existing.token = t.token
          AND (
            existing.status IN ('sent', 'invalid_token', 'permanent_failure')
            OR (
              existing.status = 'claimed'
              AND (
                existing.attempt_count >= 8
                OR existing.attempted_at > statement_timestamp()
                  - make_interval(secs => p_lease_seconds)
              )
            )
            OR (
              existing.status = 'failed'
              AND (
                existing.attempt_count >= 8
                OR existing.next_attempt_at > statement_timestamp()
              )
            )
          )
      )
    ORDER BY n.id ASC, t.id ASC
    LIMIT p_limit
  ),
  claimed AS (
    INSERT INTO public.fcm_delivery_attempts AS existing (
      notification_id,
      profile_id,
      token,
      status,
      attempted_at,
      updated_at,
      attempt_count,
      next_attempt_at
    )
    SELECT
      candidates.notification_id,
      candidates.profile_id,
      candidates.token,
      'claimed',
      statement_timestamp(),
      statement_timestamp(),
      1,
      statement_timestamp()
    FROM candidates
    ON CONFLICT ON CONSTRAINT fcm_delivery_attempts_notification_id_token_key DO UPDATE
      SET status = 'claimed',
          attempted_at = statement_timestamp(),
          updated_at = statement_timestamp(),
          attempt_count = existing.attempt_count + 1,
          next_attempt_at = statement_timestamp()
      WHERE (
        (
          existing.status = 'failed'
          AND existing.attempt_count < 8
          AND existing.next_attempt_at <= statement_timestamp()
        )
        OR (
          existing.status = 'claimed'
          AND existing.attempt_count < 8
          AND existing.attempted_at <= statement_timestamp()
            - make_interval(secs => p_lease_seconds)
        )
      )
    RETURNING
      existing.id,
      existing.notification_id,
      existing.profile_id,
      existing.token,
      existing.attempt_count
  )
  SELECT
    claimed.id,
    claimed.notification_id,
    claimed.profile_id,
    claimed.token,
    n.incident_id,
    n.event_type,
    claimed.attempt_count
  FROM claimed
  JOIN public.user_notifications AS n
    ON n.id = claimed.notification_id
  ORDER BY claimed.notification_id ASC, claimed.profile_id ASC;
END;
$function$;

-- SECURITY DEFINER in public is safe here only because PUBLIC and all client
-- roles are explicitly denied; service_role is the sole execution principal.
REVOKE ALL ON FUNCTION public.api_claim_fcm_delivery_batch(integer, integer)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.api_claim_fcm_delivery_batch(integer, integer)
  TO service_role;

COMMIT;
