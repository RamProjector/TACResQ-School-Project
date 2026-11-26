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
-- compatible with the PG16 replay lane by parsing PG17-only statements only
-- when the target server supports them. No application client or service-role
-- workflow requires maintenance commands on public application relations.
DO $revoke_client_maintain$
BEGIN
  IF current_setting('server_version_num')::integer >= 170000 THEN
    -- Remove the capability from every existing application relation in public.
    EXECUTE
      'REVOKE MAINTAIN ON ALL TABLES IN SCHEMA public FROM PUBLIC, anon, authenticated, service_role';
    -- The postgres/public default ACL otherwise reintroduces MAINTAIN on future
    -- tables created by the migration owner. Do not alter platform-owner defaults.
    EXECUTE
      'ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE MAINTAIN ON TABLES FROM PUBLIC, anon, authenticated, service_role';
  END IF;
END;
$revoke_client_maintain$;

COMMIT;
