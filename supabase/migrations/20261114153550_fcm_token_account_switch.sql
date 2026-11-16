BEGIN;

-- An FCM registration token identifies an app installation, not an account.
-- When that installation signs into a different TACResQ account, its token must
-- move to the newly authenticated profile or the global UNIQUE(token) constraint
-- prevents registration and the previous account can keep receiving pushes.
--
-- This SECURITY DEFINER function is intentionally narrow: the caller must be
-- authenticated, the profile is derived only from auth.uid(), and the only
-- mutable row is the supplied installation token. It cannot select recipients,
-- read notification data, or send a push.
CREATE OR REPLACE FUNCTION public.api_register_fcm_token(p_token text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Authenticated user is required'
      USING ERRCODE = '42501';
  END IF;

  IF p_token IS NULL OR length(p_token) < 20 OR length(p_token) > 4096 THEN
    RAISE EXCEPTION 'Invalid FCM token'
      USING ERRCODE = '22023';
  END IF;

  SELECT p.id
    INTO actor_profile_id
    FROM public.profiles AS p
   WHERE p.auth_user_id = auth.uid();

  IF actor_profile_id IS NULL THEN
    RAISE EXCEPTION 'Profile not found'
      USING ERRCODE = '42501';
  END IF;

  INSERT INTO public.fcm_tokens (profile_id, token, updated_at)
  VALUES (actor_profile_id, p_token, statement_timestamp())
  ON CONFLICT (token) DO UPDATE
    SET profile_id = EXCLUDED.profile_id,
        updated_at = statement_timestamp();
END;
$function$;

REVOKE ALL ON FUNCTION public.api_register_fcm_token(text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_register_fcm_token(text)
  TO authenticated;

COMMIT;
