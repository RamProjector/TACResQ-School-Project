BEGIN;

-- A dedicated database login is the trust boundary between the Edge verifier
-- and the private registry. It is created NOLOGIN here; operators provision a
-- password out-of-band and inject a role-scoped connection string into the
-- Edge Function secret store. Never use service_role for this connection.
DO $role$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'tacresq_device_verifier') THEN
    CREATE ROLE tacresq_device_verifier
      NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
END;
$role$;

ALTER ROLE tacresq_device_verifier
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

GRANT USAGE ON SCHEMA private TO tacresq_device_verifier;

-- The legacy service_role grants predate the dedicated verifier boundary.
-- Remove them: registry mutation must occur only inside the private,
-- transactionally guarded SECURITY DEFINER routines below.
REVOKE ALL PRIVILEGES ON TABLE
  public.user_devices,
  public.device_sessions,
  public.device_challenges,
  public.device_security_events,
  public.device_integrity_checks
FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL PRIVILEGES ON SEQUENCE
  public.device_security_events_id_seq,
  public.device_integrity_checks_id_seq
FROM PUBLIC, anon, authenticated, service_role;

CREATE INDEX IF NOT EXISTS device_challenges_profile_session_created_idx
  ON public.device_challenges (profile_id, auth_session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS device_challenges_profile_created_idx
  ON public.device_challenges (profile_id, created_at DESC);

CREATE OR REPLACE FUNCTION private.device_verifier_issue_challenge(
  p_auth_user_id uuid,
  p_auth_session_id uuid,
  p_purpose text,
  p_device_id uuid DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_profile_id bigint;
  v_device_uid uuid;
  v_raw_challenge bytea;
  v_challenge_id uuid;
  v_expires_at timestamptz;
  v_session_recent_count integer;
  v_profile_recent_count integer;
BEGIN
  IF session_user <> 'tacresq_device_verifier' THEN
    RAISE EXCEPTION 'Trusted device verifier connection required' USING ERRCODE = '42501';
  END IF;
  IF p_auth_user_id IS NULL OR p_auth_session_id IS NULL
     OR p_auth_user_id = '00000000-0000-0000-0000-000000000000'::uuid
     OR p_auth_session_id = '00000000-0000-0000-0000-000000000000'::uuid THEN
    RAISE EXCEPTION 'Verified Auth identity and session are required' USING ERRCODE = '22023';
  END IF;
  IF p_purpose IS NULL OR p_purpose NOT IN ('registration', 'session_proof') THEN
    RAISE EXCEPTION 'Unsupported device proof purpose' USING ERRCODE = '22023';
  END IF;

  -- Serialize challenge issuance for this profile/session before checking the
  -- persistent rate limit. The lock is transaction-scoped.
  SELECT p.id
    INTO v_profile_id
    FROM public.profiles AS p
   WHERE p.auth_user_id = p_auth_user_id
     AND p.account_status = 'active'
   FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Active TACResQ profile required' USING ERRCODE = '42501';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(v_profile_id::text, 0)
  );

  -- Hold a lock on the real Auth session row. This serializes issuance with
  -- sign-out/session deletion and rejects stale but not-yet-expired JWTs.
  PERFORM 1
    FROM auth.sessions AS s
   WHERE s.id = p_auth_session_id
     AND s.user_id = p_auth_user_id
     AND (s.not_after IS NULL OR s.not_after > clock_timestamp())
   FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Active Auth session required' USING ERRCODE = '42501';
  END IF;

  IF p_purpose = 'registration' THEN
    IF p_device_id IS NOT NULL THEN
      RAISE EXCEPTION 'Registration cannot specify an existing device' USING ERRCODE = '22023';
    END IF;
    IF EXISTS (
      SELECT 1 FROM public.device_sessions AS ds
       WHERE ds.auth_session_id = p_auth_session_id
         AND ds.profile_id = v_profile_id
         AND ds.revoked_at IS NULL
    ) THEN
      RAISE EXCEPTION 'This Auth session is already bound to a device' USING ERRCODE = '23505';
    END IF;
  ELSE
    IF p_device_id IS NULL THEN
      RAISE EXCEPTION 'Session proof requires a registered device' USING ERRCODE = '22023';
    END IF;
    SELECT d.device_uid
      INTO v_device_uid
      FROM public.user_devices AS d
     WHERE d.id = p_device_id
       AND d.profile_id = v_profile_id
       AND d.trust_status = 'active'
       AND d.revoked_at IS NULL
     FOR SHARE;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'Active owned device required' USING ERRCODE = '42501';
    END IF;
    IF EXISTS (
      SELECT 1 FROM public.device_sessions AS ds
       WHERE ds.auth_session_id = p_auth_session_id
         AND ds.revoked_at IS NULL
         AND ds.device_id <> p_device_id
    ) THEN
      RAISE EXCEPTION 'Auth session is already associated with another device' USING ERRCODE = '23505';
    END IF;
  END IF;

  SELECT
    count(*) FILTER (WHERE c.auth_session_id = p_auth_session_id)::integer,
    count(*)::integer
    INTO v_session_recent_count, v_profile_recent_count
    FROM public.device_challenges AS c
   WHERE c.profile_id = v_profile_id
     AND c.created_at >= clock_timestamp() - interval '15 minutes';
  IF v_session_recent_count >= 5 OR v_profile_recent_count >= 20 THEN
    RAISE EXCEPTION 'Device challenge rate limit exceeded' USING ERRCODE = '53300';
  END IF;

  v_raw_challenge := extensions.gen_random_bytes(32);
  v_expires_at := clock_timestamp() + interval '2 minutes';

  INSERT INTO public.device_challenges (
    profile_id, device_id, auth_session_id, purpose, challenge_hash, expires_at
  ) VALUES (
    v_profile_id,
    p_device_id,
    p_auth_session_id,
    p_purpose,
    pg_catalog.encode(extensions.digest(v_raw_challenge, 'sha256'), 'hex'),
    v_expires_at
  )
  RETURNING id INTO v_challenge_id;

  RETURN pg_catalog.jsonb_build_object(
    'challenge_id', v_challenge_id,
    'challenge', pg_catalog.translate(
      pg_catalog.rtrim(
        pg_catalog.replace(pg_catalog.encode(v_raw_challenge, 'base64'), E'\n', ''),
        '='
      ),
      '+/', '-_'
    ),
    'purpose', p_purpose,
    'profile_id', v_profile_id::text,
    'device_uid', coalesce(v_device_uid::text, '-'),
    'expires_at', v_expires_at
  );
END;
$function$;

CREATE OR REPLACE FUNCTION private.device_verifier_get_challenge(
  p_auth_user_id uuid,
  p_auth_session_id uuid,
  p_challenge_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_result jsonb;
BEGIN
  IF session_user <> 'tacresq_device_verifier' THEN
    RAISE EXCEPTION 'Trusted device verifier connection required' USING ERRCODE = '42501';
  END IF;
  IF p_auth_user_id IS NULL OR p_auth_session_id IS NULL OR p_challenge_id IS NULL THEN
    RAISE EXCEPTION 'Verified Auth context and challenge are required' USING ERRCODE = '22023';
  END IF;

  SELECT pg_catalog.jsonb_build_object(
    'challenge_id', c.id,
    'purpose', c.purpose,
    'profile_id', c.profile_id::text,
    'auth_session_id', c.auth_session_id,
    'device_id', c.device_id,
    'device_uid', coalesce(d.device_uid::text, '-'),
    'public_key', d.public_key,
    'key_algorithm', d.key_algorithm,
    'expires_at', c.expires_at
  )
    INTO v_result
    FROM public.device_challenges AS c
    JOIN public.profiles AS p
      ON p.id = c.profile_id
     AND p.auth_user_id = p_auth_user_id
     AND p.account_status = 'active'
    JOIN auth.sessions AS s
      ON s.id = p_auth_session_id
     AND s.user_id = p_auth_user_id
     AND (s.not_after IS NULL OR s.not_after > clock_timestamp())
    LEFT JOIN public.user_devices AS d
      ON d.id = c.device_id
     AND d.profile_id = c.profile_id
     AND d.trust_status = 'active'
     AND d.revoked_at IS NULL
   WHERE c.id = p_challenge_id
     AND c.auth_session_id = p_auth_session_id
     AND c.consumed_at IS NULL
     AND c.expires_at > clock_timestamp()
     AND (
       (c.purpose = 'registration' AND c.device_id IS NULL)
       OR (c.purpose = 'session_proof' AND d.id IS NOT NULL)
     );

  IF v_result IS NULL THEN
    RAISE EXCEPTION 'Challenge is invalid, expired, or unavailable' USING ERRCODE = '42501';
  END IF;
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION private.device_verifier_complete_challenge(
  p_auth_user_id uuid,
  p_auth_session_id uuid,
  p_challenge_id uuid,
  p_raw_challenge_base64url text,
  p_proposed_public_key_base64 text DEFAULT NULL,
  p_device_name text DEFAULT 'Android device',
  p_device_model text DEFAULT NULL,
  p_app_version text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_profile_id bigint;
  v_purpose text;
  v_device_id uuid;
  v_device_uid uuid;
  v_session_expiry timestamptz;
  v_raw_challenge bytea;
  v_expected_hash text;
  v_consumed_id uuid;
  v_event_type text;
BEGIN
  IF session_user <> 'tacresq_device_verifier' THEN
    RAISE EXCEPTION 'Trusted device verifier connection required' USING ERRCODE = '42501';
  END IF;
  IF p_auth_user_id IS NULL OR p_auth_session_id IS NULL OR p_challenge_id IS NULL THEN
    RAISE EXCEPTION 'Verified Auth context and challenge are required' USING ERRCODE = '22023';
  END IF;
  IF p_raw_challenge_base64url IS NULL
     OR p_raw_challenge_base64url !~ '^[A-Za-z0-9_-]{43}$' THEN
    RAISE EXCEPTION 'Invalid challenge encoding' USING ERRCODE = '22023';
  END IF;

  v_raw_challenge := pg_catalog.decode(
    pg_catalog.translate(p_raw_challenge_base64url, '-_', '+/') || '=',
    'base64'
  );
  IF pg_catalog.octet_length(v_raw_challenge) <> 32
     OR pg_catalog.translate(
       pg_catalog.rtrim(
         pg_catalog.replace(pg_catalog.encode(v_raw_challenge, 'base64'), E'\n', ''),
         '='
       ),
       '+/', '-_'
     ) <> p_raw_challenge_base64url THEN
    RAISE EXCEPTION 'Invalid or non-canonical challenge encoding' USING ERRCODE = '22023';
  END IF;
  v_expected_hash := pg_catalog.encode(extensions.digest(v_raw_challenge, 'sha256'), 'hex');

  SELECT p.id, c.purpose, c.device_id
    INTO v_profile_id, v_purpose, v_device_id
    FROM public.device_challenges AS c
    JOIN public.profiles AS p
      ON p.id = c.profile_id
     AND p.auth_user_id = p_auth_user_id
     AND p.account_status = 'active'
    JOIN auth.sessions AS s
      ON s.id = p_auth_session_id
     AND s.user_id = p_auth_user_id
   WHERE c.id = p_challenge_id
     AND c.auth_session_id = p_auth_session_id
     AND c.challenge_hash = v_expected_hash
     AND c.consumed_at IS NULL
     AND c.expires_at > clock_timestamp()
   FOR UPDATE OF c, p;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Challenge is invalid, expired, or unavailable' USING ERRCODE = '42501';
  END IF;

  IF v_purpose = 'registration' THEN
    IF p_proposed_public_key_base64 IS NULL
       OR pg_catalog.length(p_proposed_public_key_base64) < 1
       OR pg_catalog.length(p_proposed_public_key_base64) > 4096
       OR p_device_name IS NULL
       OR pg_catalog.length(pg_catalog.btrim(p_device_name)) NOT BETWEEN 1 AND 80
       OR (p_device_model IS NOT NULL AND pg_catalog.length(p_device_model) > 120)
       OR (p_app_version IS NOT NULL AND pg_catalog.length(p_app_version) > 80) THEN
      RAISE EXCEPTION 'Invalid device registration metadata' USING ERRCODE = '22023';
    END IF;
    IF EXISTS (
      SELECT 1 FROM public.device_sessions AS ds
       WHERE ds.auth_session_id = p_auth_session_id
         AND ds.revoked_at IS NULL
    ) THEN
      RAISE EXCEPTION 'This Auth session is already bound to a device' USING ERRCODE = '23505';
    END IF;
  ELSIF v_purpose = 'session_proof' THEN
    IF p_proposed_public_key_base64 IS NOT NULL THEN
      RAISE EXCEPTION 'Session proof cannot replace the registered public key' USING ERRCODE = '22023';
    END IF;
    PERFORM 1
      FROM public.user_devices AS d
     WHERE d.id = v_device_id
       AND d.profile_id = v_profile_id
       AND d.trust_status = 'active'
       AND d.revoked_at IS NULL
     FOR UPDATE;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'Active owned device required' USING ERRCODE = '42501';
    END IF;
  ELSE
    RAISE EXCEPTION 'Unsupported device proof purpose' USING ERRCODE = '22023';
  END IF;

  -- The conditional update is the single-use gate. A concurrent request can
  -- never win after another transaction has consumed this challenge.
  UPDATE public.device_challenges AS c
     SET consumed_at = clock_timestamp()
   WHERE c.id = p_challenge_id
     AND c.profile_id = v_profile_id
     AND c.auth_session_id = p_auth_session_id
     AND c.purpose = v_purpose
     AND c.challenge_hash = v_expected_hash
     AND c.consumed_at IS NULL
     AND c.expires_at > clock_timestamp()
  RETURNING c.id INTO v_consumed_id;
  IF v_consumed_id IS NULL THEN
    RAISE EXCEPTION 'Challenge is invalid, expired, or unavailable' USING ERRCODE = '42501';
  END IF;

  SELECT s.not_after
    INTO v_session_expiry
    FROM auth.sessions AS s
   WHERE s.id = p_auth_session_id
     AND s.user_id = p_auth_user_id
     AND (s.not_after IS NULL OR s.not_after > clock_timestamp())
   FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Active Auth session required' USING ERRCODE = '42501';
  END IF;

  IF v_purpose = 'registration' THEN
    INSERT INTO public.user_devices (
      profile_id, device_name, platform, device_model, app_version,
      public_key, key_algorithm, trust_status, last_seen_at, last_verified_at
    ) VALUES (
      v_profile_id,
      pg_catalog.btrim(p_device_name),
      'android',
      p_device_model,
      p_app_version,
      p_proposed_public_key_base64,
      'ES256',
      'active',
      clock_timestamp(),
      clock_timestamp()
    )
    RETURNING id, device_uid INTO v_device_id, v_device_uid;
    v_event_type := 'registration_verified';
  ELSE
    UPDATE public.user_devices AS d
       SET last_seen_at = clock_timestamp(),
           last_verified_at = clock_timestamp()
     WHERE d.id = v_device_id
       AND d.profile_id = v_profile_id
    RETURNING d.device_uid INTO v_device_uid;
    v_event_type := 'proof_verified';
  END IF;

  INSERT INTO public.device_sessions (
    device_id, profile_id, auth_session_id, last_seen_at, expires_at
  ) VALUES (
    v_device_id, v_profile_id, p_auth_session_id, clock_timestamp(), v_session_expiry
  )
  ON CONFLICT (auth_session_id) DO UPDATE
    SET last_seen_at = EXCLUDED.last_seen_at,
        expires_at = EXCLUDED.expires_at
    WHERE public.device_sessions.device_id = EXCLUDED.device_id
      AND public.device_sessions.profile_id = EXCLUDED.profile_id
      AND public.device_sessions.revoked_at IS NULL;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Auth session is already associated with another device' USING ERRCODE = '23505';
  END IF;

  INSERT INTO public.device_security_events (
    profile_id, device_id, auth_session_id, event_type, severity, details
  ) VALUES (
    v_profile_id,
    v_device_id,
    p_auth_session_id,
    v_event_type,
    'info',
    pg_catalog.jsonb_build_object('key_algorithm', 'ES256', 'platform', 'android', 'verifier', 'tacresq-device-verifier-v1')
  );

  RETURN pg_catalog.jsonb_build_object(
    'device_id', v_device_id,
    'device_uid', v_device_uid,
    'trust_status', 'active',
    'verified_at', clock_timestamp()
  );
END;
$function$;

-- These routines are not PostgREST endpoints: they live in the non-exposed
-- private schema and are executable only by the dedicated verifier login.
REVOKE ALL ON FUNCTION private.device_verifier_issue_challenge(uuid, uuid, text, uuid)
  FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.device_verifier_get_challenge(uuid, uuid, uuid)
  FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.device_verifier_complete_challenge(uuid, uuid, uuid, text, text, text, text, text)
  FROM PUBLIC, anon, authenticated, service_role;

GRANT EXECUTE ON FUNCTION private.device_verifier_issue_challenge(uuid, uuid, text, uuid)
  TO tacresq_device_verifier;
GRANT EXECUTE ON FUNCTION private.device_verifier_get_challenge(uuid, uuid, uuid)
  TO tacresq_device_verifier;
GRANT EXECUTE ON FUNCTION private.device_verifier_complete_challenge(uuid, uuid, uuid, text, text, text, text, text)
  TO tacresq_device_verifier;

COMMENT ON ROLE tacresq_device_verifier IS
  'Dedicated non-superuser database login for the TACResQ device-verifier Edge Function. Provision LOGIN/password out-of-band; grant no direct table access.';
COMMENT ON FUNCTION private.device_verifier_issue_challenge(uuid, uuid, text, uuid) IS
  'Trusted Edge-verifier-only challenge issuance. Derives profile ownership, validates live Auth session, persists only a SHA-256 hash, and enforces per-profile/session issuance limits.';
COMMENT ON FUNCTION private.device_verifier_get_challenge(uuid, uuid, uuid) IS
  'Trusted Edge-verifier-only challenge context lookup. Requires matching active profile/session and returns only the registered public key and signed-message context.';
COMMENT ON FUNCTION private.device_verifier_complete_challenge(uuid, uuid, uuid, text, text, text, text, text) IS
  'Trusted Edge-verifier-only atomic completion after P-256 signature verification. Consumes one challenge, binds one Auth session to one device, and appends an event in one transaction.';

COMMIT;
