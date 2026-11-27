BEGIN;

-- The Laravel API-operation writer already emits the verified Supabase Auth
-- session UUID when /auth/v1/user validates the caller's bearer token.
-- Preserve that immutable identifier as audit metadata; deliberately do not
-- FK it to auth.sessions because sign-out deletes session rows while history
-- must remain searchable after revocation.
ALTER TABLE public.api_operation_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;

CREATE INDEX IF NOT EXISTS api_operation_logs_auth_session_created_idx
  ON public.api_operation_logs (auth_session_id, created_at DESC, id DESC)
  WHERE auth_session_id IS NOT NULL;

COMMENT ON COLUMN public.api_operation_logs.auth_session_id IS
  'Verified Supabase Auth session UUID captured by the Laravel bridge after Auth /user validation; retained as historical metadata after session revocation.';

COMMIT;
