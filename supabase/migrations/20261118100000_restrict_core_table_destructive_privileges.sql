BEGIN;

-- Preserve the existing authenticated INSERT/UPDATE paths for requester
-- submissions, responder duty status, and dispatcher-managed incident progress.
-- DELETE and TRUNCATE are not required by the current clients. TRUNCATE bypasses
-- RLS entirely, while DELETE can erase emergency/account records if a policy is
-- later widened accidentally.
REVOKE DELETE, TRUNCATE
  ON TABLE public.profiles, public.incidents
  FROM PUBLIC, anon, authenticated;

COMMENT ON TABLE public.profiles IS
  'Client reads and guarded updates remain RLS-controlled; direct client deletion and truncation are prohibited.';

COMMENT ON TABLE public.incidents IS
  'Client reads and guarded create/progress paths remain RLS-controlled; direct client deletion and truncation are prohibited.';

COMMIT;
