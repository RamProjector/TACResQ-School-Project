BEGIN;

-- All client-facing Admin RPC wrappers already call this private helper.
-- Revalidate current account state from profiles and hold a share lock so
-- suspended Admin sessions with otherwise-valid AAL2/TOTP JWTs are rejected.
CREATE OR REPLACE FUNCTION private.tacresq_require_admin_mfa()
RETURNS void
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  IF auth.uid() IS NULL
     OR (auth.jwt() ->> 'aal') IS DISTINCT FROM 'aal2'
     OR NOT EXISTS (
       SELECT 1
       FROM jsonb_array_elements(coalesce(auth.jwt() -> 'amr', '[]'::jsonb)) AS method
       WHERE CASE jsonb_typeof(method)
         WHEN 'object' THEN method ->> 'method'
         WHEN 'string' THEN method #>> '{}'
         ELSE NULL
       END = 'totp'
     ) THEN
    RAISE EXCEPTION 'Administrator TOTP verification required'
      USING ERRCODE = '42501';
  END IF;

  PERFORM 1
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.role = 'admin'
     AND p.account_status = 'active'
   FOR SHARE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Active administrator access required'
      USING ERRCODE = '42501';
  END IF;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_require_admin_mfa()
  FROM PUBLIC, anon, authenticated;

COMMIT;
