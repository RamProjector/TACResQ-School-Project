BEGIN;

-- Read-only session-presence predicate for restrictive SELECT policies.
-- PostgREST GET requests run in read-only transactions, so this helper must
-- never acquire a row lock.
CREATE OR REPLACE FUNCTION private.tacresq_current_auth_session_present_read()
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_auth_user_id uuid;
  v_session_id_text text;
  v_session_id uuid;
BEGIN
  v_auth_user_id := auth.uid();
  IF v_auth_user_id IS NULL THEN
    RETURN false;
  END IF;

  v_session_id_text := auth.jwt() ->> 'session_id';
  IF v_session_id_text IS NULL
     OR v_session_id_text !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
     OR lower(v_session_id_text) = '00000000-0000-0000-0000-000000000000' THEN
    RETURN false;
  END IF;

  v_session_id := v_session_id_text::uuid;
  RETURN EXISTS (
    SELECT 1
      FROM auth.sessions AS s
     WHERE s.id = v_session_id
       AND s.user_id = v_auth_user_id
  );
END;
$function$;

-- Write-capable helper for privileged RPCs. The row lock serializes an
-- in-flight sensitive operation with Auth's session-row deletion on logout.
CREATE OR REPLACE FUNCTION private.tacresq_current_auth_session_valid()
RETURNS boolean
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_auth_user_id uuid;
  v_session_id_text text;
  v_session_id uuid;
BEGIN
  v_auth_user_id := auth.uid();
  IF v_auth_user_id IS NULL THEN
    RETURN false;
  END IF;

  v_session_id_text := auth.jwt() ->> 'session_id';
  IF v_session_id_text IS NULL
     OR v_session_id_text !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
     OR lower(v_session_id_text) = '00000000-0000-0000-0000-000000000000' THEN
    RETURN false;
  END IF;

  v_session_id := v_session_id_text::uuid;
  PERFORM 1
    FROM auth.sessions AS s
   WHERE s.id = v_session_id
     AND s.user_id = v_auth_user_id
   FOR SHARE;

  RETURN FOUND;
END;
$function$;

-- The read-only predicate is safe to call from authenticated SELECT policies.
-- The row-locking helper remains internal to trusted RPC wrappers.
REVOKE ALL ON FUNCTION private.tacresq_current_auth_session_present_read()
  FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION private.tacresq_current_auth_session_present_read()
  TO authenticated;
REVOKE ALL ON FUNCTION private.tacresq_current_auth_session_valid()
  FROM PUBLIC, anon, authenticated, service_role;

-- Preserve the shared Admin role, active-account, AAL2, TOTP and profile-lock
-- checks, then require the caller's own still-present Auth session row.
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

  IF NOT private.tacresq_current_auth_session_valid() THEN
    RAISE EXCEPTION 'Active administrator session required'
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
  FROM PUBLIC, anon, authenticated, service_role;

-- Admin audit table has a permissive Admin-role policy plus this restrictive
-- assurance policy. Keep it restrictive and add current session presence.
DROP POLICY IF EXISTS admin_audit_logs_require_aal2
  ON public.admin_audit_logs;
CREATE POLICY admin_audit_logs_require_aal2
  ON public.admin_audit_logs
  AS RESTRICTIVE
  FOR SELECT
  TO authenticated
  USING (
    (SELECT auth.jwt() ->> 'aal') = 'aal2'
    AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(coalesce((SELECT auth.jwt()) -> 'amr', '[]'::jsonb)) AS method
      WHERE CASE jsonb_typeof(method)
        WHEN 'object' THEN method ->> 'method'
        WHEN 'string' THEN method #>> '{}'
        ELSE NULL
      END = 'totp'
    )
    AND (SELECT private.tacresq_current_auth_session_present_read())
  );

-- Keep the dispatcher read path unchanged; only the Admin branch requires
-- AAL2, TOTP and a present caller-owned Auth session.
DROP POLICY IF EXISTS incident_assignment_events_admin_require_aal2
  ON public.incident_assignment_events;
CREATE POLICY incident_assignment_events_admin_require_aal2
  ON public.incident_assignment_events
  AS RESTRICTIVE
  FOR SELECT TO authenticated
  USING (
    (SELECT private.tacresq_actor_role()) IS DISTINCT FROM 'admin'
    OR (
      (SELECT auth.jwt() ->> 'aal') = 'aal2'
      AND EXISTS (
        SELECT 1
        FROM jsonb_array_elements(
          coalesce((SELECT auth.jwt()) -> 'amr', '[]'::jsonb)
        ) AS method(value)
        WHERE method.value ->> 'method' = 'totp'
      )
      AND (SELECT private.tacresq_current_auth_session_present_read())
    )
  );

COMMENT ON FUNCTION private.tacresq_current_auth_session_present_read() IS
  'Read-only predicate for direct SELECT policies. Returns true only when the verified JWT session_id maps to an auth.sessions row owned by auth.uid(); does not lock the row.';
COMMENT ON FUNCTION private.tacresq_current_auth_session_valid() IS
  'Internal write-capable predicate for sensitive RPCs. Validates JWT session_id ownership and holds a FOR SHARE lock on the matching auth.sessions row until transaction end.';
COMMENT ON FUNCTION private.tacresq_require_admin_mfa() IS
  'Internal Admin authorization guard requiring AAL2, TOTP, an active Admin profile, and a caller-owned Auth session row present at the sensitive operation boundary.';

COMMIT;
