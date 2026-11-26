BEGIN;

-- The invoker duty-status RPC needs only these two columns. A table-level
-- UPDATE grant also covers account_status, suspension metadata, role, identity,
-- and timestamps, so the earlier column-only REVOKE was not sufficient.
REVOKE UPDATE ON TABLE public.profiles FROM authenticated;
REVOKE UPDATE (duty_status, updated_at)
  ON TABLE public.profiles
  FROM authenticated;

-- Preserve the narrow SECURITY INVOKER api_set_duty_status(text) path.
-- RLS still limits the row to the caller's own responder/driver profile, and
-- the profile trigger enforces the allowed duty-state transitions.
GRANT UPDATE (duty_status, updated_at)
  ON TABLE public.profiles
  TO authenticated;

-- PostgreSQL 17 introduced the MAINTAIN table privilege. Keep the migration
-- compatible with the PG16 replay lane by parsing the PG17-only statement only
-- when the target server supports it. No client workflow requires maintenance
-- commands on profile or incident relations.
DO $revoke_client_maintain$
BEGIN
  IF current_setting('server_version_num')::integer >= 170000 THEN
    EXECUTE
      'REVOKE MAINTAIN ON TABLE public.profiles, public.incidents FROM PUBLIC, anon, authenticated, service_role';
  END IF;
END;
$revoke_client_maintain$;

COMMIT;
