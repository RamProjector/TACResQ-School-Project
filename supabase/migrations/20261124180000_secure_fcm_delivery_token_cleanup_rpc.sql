BEGIN;

-- Trusted Laravel workers use the server-only Supabase key, not an end-user
-- JWT. Keep invalid-token cleanup on a separate service_role-only RPC and
-- require the exact profile/token pair returned by the delivery claim.
CREATE OR REPLACE FUNCTION public.api_unregister_fcm_token_for_delivery(
  p_profile_id bigint,
  p_token text
)
RETURNS void
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  IF p_profile_id IS NULL OR p_profile_id < 1 THEN
    RAISE EXCEPTION 'Invalid FCM recipient profile'
      USING ERRCODE = '22023';
  END IF;

  IF p_token IS NULL OR length(p_token) < 20 OR length(p_token) > 4096 THEN
    RAISE EXCEPTION 'Invalid FCM token'
      USING ERRCODE = '22023';
  END IF;

  DELETE FROM public.fcm_tokens AS token_row
   WHERE token_row.profile_id = p_profile_id
     AND token_row.token = p_token;
END;
$function$;

REVOKE ALL ON FUNCTION public.api_unregister_fcm_token_for_delivery(bigint, text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.api_unregister_fcm_token_for_delivery(bigint, text)
  TO service_role;

COMMIT;
