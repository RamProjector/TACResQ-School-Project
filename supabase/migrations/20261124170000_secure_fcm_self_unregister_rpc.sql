BEGIN;

-- The global suspended-account write trigger also runs for SECURITY DEFINER
-- functions. Permit one narrowly scoped exception: this authenticated RPC may
-- delete only the current caller's own FCM token while a suspended session is
-- signing out. The transaction-local marker is set only around this DELETE.
CREATE OR REPLACE FUNCTION private.tacresq_reject_suspended_account_write()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $guard$
BEGIN
  IF auth.uid() IS NOT NULL
     AND NOT private.tacresq_current_account_active() THEN
    -- OLD is a relation-specific record. Read profile_id only inside this
    -- nested branch; the trigger is installed on many tables with other shapes.
    IF TG_OP = 'DELETE' THEN
      IF TG_TABLE_SCHEMA = 'public' THEN
        IF TG_TABLE_NAME = 'fcm_tokens' THEN
          IF current_setting('tacresq.allow_suspended_fcm_self_cleanup', true) = 'on' THEN
            IF OLD.profile_id = private.tacresq_auth_profile_id_for_token_cleanup() THEN
              RETURN OLD;
            END IF;
          END IF;
        END IF;
      END IF;
    END IF;

    RAISE EXCEPTION 'Suspended accounts cannot perform protected writes'
      USING ERRCODE = '42501';
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$guard$;

REVOKE ALL ON FUNCTION private.tacresq_reject_suspended_account_write()
  FROM PUBLIC, anon, authenticated, service_role;

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

  PERFORM pg_catalog.set_config('tacresq.allow_suspended_fcm_self_cleanup', 'on', true);

  DELETE FROM public.fcm_tokens AS token_row
   WHERE token_row.profile_id = caller_profile_id
     AND token_row.token = p_token;

  -- Reset rather than restore a caller-supplied value; the marker is private
  -- control state for this RPC and must not remain enabled after it returns.
  PERFORM pg_catalog.set_config('tacresq.allow_suspended_fcm_self_cleanup', NULL, true);
END;
$function$;

REVOKE ALL ON FUNCTION public.api_unregister_fcm_token(text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_unregister_fcm_token(text)
  TO authenticated;

COMMIT;
