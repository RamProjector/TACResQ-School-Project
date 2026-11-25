-- Runtime contract for the incident assignment table privilege boundary.
DO $assignment_privilege_boundary$
DECLARE
  assignment_table regclass := to_regclass('public.incident_assignments');
  assign_rpc regprocedure := to_regprocedure('public.api_assign_incident_team(bigint,bigint[])');
  claim_rpc regprocedure := to_regprocedure('public.api_claim_incident(bigint)');
  private_assign_rpc regprocedure := to_regprocedure('private.tacresq_assign_incident_team_guarded(bigint,bigint[])');
BEGIN
  IF assignment_table IS NULL OR assign_rpc IS NULL OR claim_rpc IS NULL OR private_assign_rpc IS NULL THEN
    RAISE EXCEPTION 'Expected incident assignment table and guarded assignment/claim RPCs';
  END IF;

  IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid = assignment_table) THEN
    RAISE EXCEPTION 'incident_assignments must retain row-level security';
  END IF;

  IF has_table_privilege('anon', assignment_table, 'SELECT')
     OR has_table_privilege('anon', assignment_table, 'INSERT')
     OR has_table_privilege('anon', assignment_table, 'UPDATE')
     OR has_table_privilege('anon', assignment_table, 'DELETE')
     OR has_table_privilege('anon', assignment_table, 'TRUNCATE') THEN
    RAISE EXCEPTION 'anon must have no direct privileges on incident_assignments';
  END IF;

  IF NOT has_table_privilege('authenticated', assignment_table, 'SELECT') THEN
    RAISE EXCEPTION 'authenticated must retain RLS-filtered assignment reads';
  END IF;

  IF has_table_privilege('authenticated', assignment_table, 'INSERT')
     OR has_table_privilege('authenticated', assignment_table, 'UPDATE')
     OR has_table_privilege('authenticated', assignment_table, 'DELETE')
     OR has_table_privilege('authenticated', assignment_table, 'TRUNCATE')
     OR has_table_privilege('authenticated', assignment_table, 'REFERENCES')
     OR has_table_privilege('authenticated', assignment_table, 'TRIGGER') THEN
    RAISE EXCEPTION 'authenticated must not mutate or alter incident_assignments directly';
  END IF;

  IF NOT has_function_privilege('authenticated', assign_rpc, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', claim_rpc, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', private_assign_rpc, 'EXECUTE') THEN
    RAISE EXCEPTION 'Guarded assignment and field-claim RPC execution must remain available';
  END IF;

  IF has_function_privilege('anon', assign_rpc, 'EXECUTE')
     OR has_function_privilege('anon', claim_rpc, 'EXECUTE')
     OR has_function_privilege('anon', private_assign_rpc, 'EXECUTE')
     OR has_function_privilege('authenticated', 'private.tacresq_assign_incident_team(bigint,bigint[])', 'EXECUTE')
     OR has_function_privilege('anon', 'private.tacresq_assign_incident_team(bigint,bigint[])', 'EXECUTE') THEN
    RAISE EXCEPTION 'anon must not execute assignment or claim RPCs';
  END IF;
END;
$assignment_privilege_boundary$;
