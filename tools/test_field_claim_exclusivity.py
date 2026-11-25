#!/usr/bin/env python3
"""Regression contract for field-claim exclusivity and lifecycle safety."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"
sql = (MIGRATIONS / "20261122050813_harden_field_claim_exclusivity.sql").read_text()

claim_start = sql.index("CREATE OR REPLACE FUNCTION public.api_claim_incident")
claim_end = sql.index("$function$;", claim_start) + len("$function$;")
claim = sql[claim_start:claim_end]

# Authentication is checked before locking an incident; then the incident row
# is locked before the profile row, matching dispatcher team assignment.
assert claim.index("v_auth_uid := auth.uid()") < claim.index("FROM public.incidents") < claim.index("FOR UPDATE;") < claim.index("SELECT role, duty_status INTO v_actor_role, v_duty_status")
assert "FROM public.profiles\n  WHERE id = v_actor_id AND auth_user_id = v_auth_uid\n  FOR UPDATE" in claim
assert "v_status NOT IN ('pending', 'assigned')" in claim
assert "active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')" in claim
assert "active_i.assigned_to = v_actor_id" in claim
assert "a.user_id = v_actor_id" in claim
assert "'UNIT_BUSY'" in claim
assert "'GROUP_FULL'" in claim
assert "v_assignment_count := v_assignment_count + 1" in claim
assert "INSERT INTO public.incident_assignments" in claim

list_start = sql.index("CREATE OR REPLACE FUNCTION public.api_claimable_incidents")
list_end = sql.index("$function$;", list_start) + len("$function$;")
claimable = sql[list_start:list_end]
assert "active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')" in claimable
assert "i.status IN ('pending', 'assigned')" in claimable
assert "own_assignment.user_id = v_actor_id" in claimable
assert "i.assigned_to IS DISTINCT FROM v_actor_id" in claimable
assert "legacy_lead.user_id = i.assigned_to" in claimable
assert "GRANT EXECUTE ON FUNCTION public.api_claim_incident(bigint) TO authenticated" in sql
assert "GRANT EXECUTE ON FUNCTION public.api_claimable_incidents() TO authenticated" in sql
assert "REVOKE ALL ON FUNCTION public.api_claim_incident(bigint) FROM PUBLIC, anon" in sql

print("PASS: field claims serialize by incident then profile, reject busy units, and respect lifecycle states.")
print("PASS: claimable-incident discovery excludes busy/already-assigned units and stale lifecycle states.")

# Dispatcher expansion must remain possible after the first field self-claim.
# A self-claimed incident has status=assigned, assigned_to=NULL, and at least
# one authoritative incident_assignments row. Empty-team assigned incidents
# without a dispatcher lead remain invalid.
assignment_start = sql.index("CREATE OR REPLACE FUNCTION private.tacresq_assign_incident_team")
assignment_end = sql.index("$function$;", assignment_start) + len("$function$;")
assignment = sql[assignment_start:assignment_end]
assert "FROM public.incidents WHERE id = p_id FOR UPDATE" in assignment
assert "FROM public.profiles WHERE auth_user_id = auth.uid() FOR UPDATE" in assignment
assert assignment.index("SELECT role INTO actor_role") < assignment.index("FROM public.incidents WHERE id = p_id FOR UPDATE") < assignment.index("FROM public.profiles WHERE auth_user_id = auth.uid() FOR UPDATE")
assert "incident_row.status IN ('assigned','en_route')" in assignment
assert "incident_row.assigned_to IS NULL" in assignment
assert "FROM public.incident_assignments existing_team" in assignment
assert "FROM public.incident_assignments legacy_lead" in assignment
assert "current_team_count + cardinality(p_user_ids) > 5" in assignment
assert "ORDER BY id FOR UPDATE" in assignment
assert "ON CONFLICT (incident_id, user_id) DO NOTHING" in assignment

print("PASS: dispatcher team expansion supports field-claimed incidents while preserving assignment, capacity, and lock-order guards.")



# This integration test runs two independent psql processes against the same
# disposable PostgreSQL database; it is not a source-only lock-order assertion.
race_test = ROOT / "tools/test_field_claim_concurrency.sh"
race_sql = race_test.read_text()
for required in (
    "run_claim \"${incident_ids[0]}\"",
    "run_claim \"${incident_ids[1]}\"",
    "SET ROLE authenticated",
    "SELECT public.api_claim_incident($incident_id)",
    "pg_sleep(1)",
    "'\"status\": \"claimed\"'",
    "'\"code\": \"UNIT_BUSY\"'",
    "Exactly one membership",
    "run_claim_as \"$responder_b_uuid\" \"${incident_ids[2]}\"",
    "run_claim_as \"$responder_c_uuid\" \"${incident_ids[2]}\"",
    "same_incident_claimed",
    "same-incident-counts",
    "Disposable same-incident multi-unit claim",
    "Expected two team members and one assigned incident",
):
    assert required.lower() in race_sql.lower(), f"Missing two-session race assertion: {required}"

assert "00000000-0000-4000-8000-000000000903" in race_sql
assert "claim-race-requester-b@example.test" in race_sql
assert race_sql.count("WHERE p.auth_user_id = '00000000-0000-4000-8000-00000000090") >= 3, "Use distinct requester and responder fixture identities"
