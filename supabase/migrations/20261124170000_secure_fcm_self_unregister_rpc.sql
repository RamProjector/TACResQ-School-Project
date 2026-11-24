BEGIN;

-- Clients have no direct DELETE privilege on fcm_tokens. Keep sign-out cleanup
-- available through a narrowly scoped definer RPC that can remove only the
-- caller's own token, including after account suspension.
CREATE OR REPLACE FUNCTION public.api_unregister_fcm_token(p_token text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  caller_profile_id bigint;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Authentication required'
      USING ERRCODE = '42501';
  END IF;

  IF p_token IS NULL OR length(p_token) < 20 OR length(p_token) > 4096 THEN
    RAISE EXCEPTION 'Invalid FCM token'
      USING ERRCODE = '22023';
  END IF;

  SELECT profile.id
    INTO caller_profile_id
    FROM public.profiles AS profile
   WHERE profile.auth_user_id = auth.uid();

  IF caller_profile_id IS NULL THEN
    RAISE EXCEPTION 'Profile not found'
      USING ERRCODE = '42501';
  END IF;

  DELETE FROM public.fcm_tokens AS token_row
   WHERE token_row.profile_id = caller_profile_id
     AND token_row.token = p_token;
END;
$function$;

REVOKE ALL ON FUNCTION public.api_unregister_fcm_token(text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_unregister_fcm_token(text)
  TO authenticated;

COMMIT;
