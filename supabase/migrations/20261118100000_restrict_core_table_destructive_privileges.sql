BEGIN;

-- Neither core table is a direct client INSERT surface. Profile creation is
-- handled by the guarded auth-user trigger; incident creation uses the
-- authorization-checked incident RPC. Existing RLS-filtered reads and narrowly
-- scoped progress updates remain intact.
-- DELETE and TRUNCATE are not required by current clients. TRUNCATE bypasses
-- RLS entirely, while DELETE can erase emergency/account records if a policy is
-- later widened accidentally.
REVOKE DELETE, TRUNCATE
  ON TABLE public.profiles, public.incidents
  FROM PUBLIC, anon, authenticated;

COMMENT ON TABLE public.profiles IS
  'Client reads and guarded duty-status updates remain RLS-controlled; creation is trigger-managed and direct client deletion/truncation is prohibited.';

COMMENT ON TABLE public.incidents IS
  'Client reads and narrowly scoped progress updates remain RLS-controlled; creation and assignment use authorization-checked RPCs, and direct client insert/delete/truncation is prohibited.';

COMMIT;
