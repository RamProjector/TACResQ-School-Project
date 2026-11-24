BEGIN;

-- Narrow identity lookup used only by the self-delete cleanup policy. Unlike
-- the active profile resolver, it deliberately returns the caller's profile
-- while suspended; it exposes no profile fields and cannot mutate state.
CREATE OR REPLACE FUNCTION private.tacresq_auth_profile_id_for_token_cleanup()
RETURNS bigint
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
  SELECT p.id
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
$function$;

REVOKE ALL ON FUNCTION private.tacresq_auth_profile_id_for_token_cleanup()
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_auth_profile_id_for_token_cleanup()
  TO authenticated;

-- A suspended user may remove this installation's own token during sign-out,
-- but cannot register/rotate, enumerate, or mutate FCM registrations.
DROP POLICY IF EXISTS tacresq_active_account_required ON public.fcm_tokens;
DROP POLICY IF EXISTS fcm_tokens_delete_self ON public.fcm_tokens;

CREATE POLICY fcm_tokens_delete_self
  ON public.fcm_tokens FOR DELETE TO authenticated
  USING (profile_id = (SELECT private.tacresq_auth_profile_id_for_token_cleanup()));

CREATE POLICY tacresq_fcm_select_requires_active
  ON public.fcm_tokens AS RESTRICTIVE FOR SELECT TO authenticated
  USING ((SELECT private.tacresq_current_account_active()));

CREATE POLICY tacresq_fcm_insert_requires_active
  ON public.fcm_tokens AS RESTRICTIVE FOR INSERT TO authenticated
  WITH CHECK ((SELECT private.tacresq_current_account_active()));

CREATE POLICY tacresq_fcm_update_requires_active
  ON public.fcm_tokens AS RESTRICTIVE FOR UPDATE TO authenticated
  USING ((SELECT private.tacresq_current_account_active()))
  WITH CHECK ((SELECT private.tacresq_current_account_active()));

-- Deliberately no active-account predicate for DELETE: the existing permissive
-- fcm_tokens_delete_self policy still restricts deletion to the caller's own
-- profile. No SELECT/INSERT/UPDATE exception is granted to suspended accounts.
CREATE POLICY tacresq_fcm_delete_self_cleanup
  ON public.fcm_tokens AS RESTRICTIVE FOR DELETE TO authenticated
  USING (
    profile_id = (SELECT private.tacresq_auth_profile_id_for_token_cleanup())
  );

-- Keep unregister as an invoker operation so RLS still enforces the narrow
-- self-delete policy, while resolving identity without requiring active status.
CREATE OR REPLACE FUNCTION public.api_unregister_fcm_token(p_token text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
BEGIN
  IF auth.uid() IS NULL OR p_token IS NULL THEN
    RETURN;
  END IF;

  actor_profile_id := private.tacresq_auth_profile_id_for_token_cleanup();
  IF actor_profile_id IS NULL THEN
    RETURN;
  END IF;

  PERFORM pg_catalog.set_config('tacresq.allow_suspended_fcm_self_cleanup', 'on', true);

  DELETE FROM public.fcm_tokens
   WHERE profile_id = actor_profile_id
     AND token = p_token;

  -- The marker is transaction-local and must not remain enabled after this RPC.
  PERFORM pg_catalog.set_config('tacresq.allow_suspended_fcm_self_cleanup', NULL, true);
END;
$function$;

REVOKE ALL ON FUNCTION public.api_unregister_fcm_token(text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_unregister_fcm_token(text)
  TO authenticated;

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
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  IF actor_profile_id IS NULL THEN
    RAISE EXCEPTION 'Active account required for FCM registration'
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

COMMENT ON POLICY tacresq_fcm_delete_self_cleanup ON public.fcm_tokens IS
  'Suspended users may remove only their own FCM installation token for sign-out cleanup; all other direct FCM operations require an active account.';

COMMIT;
