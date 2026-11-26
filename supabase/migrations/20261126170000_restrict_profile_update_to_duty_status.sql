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

COMMIT;
