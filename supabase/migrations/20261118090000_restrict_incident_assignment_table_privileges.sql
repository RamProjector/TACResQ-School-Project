BEGIN;

-- incident_assignments is a read-only client surface. All assignment mutations
-- are performed by authorization-checked SECURITY DEFINER RPC implementations
-- (team assignment, field claiming, and dispatcher release/assignment flows).
-- The SELECT policy remains the row-level boundary for authenticated readers.
--
-- In particular, TRUNCATE is not governed by RLS, so it must never be granted
-- to either PostgREST client role.

REVOKE ALL PRIVILEGES
  ON TABLE public.incident_assignments
  FROM PUBLIC, anon, authenticated;

GRANT SELECT
  ON TABLE public.incident_assignments
  TO authenticated;

COMMENT ON TABLE public.incident_assignments IS
  'Client roles may read rows allowed by RLS; mutations are restricted to reviewed, authorization-checked RPCs.';

COMMIT;
