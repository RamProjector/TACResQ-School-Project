#!/usr/bin/env python3
"""Regression contract for field-claim exclusivity and lifecycle safety."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"
sql = (MIGRATIONS / "20261116180000_harden_field_claim_exclusivity.sql").read_text()

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
assert "GRANT EXECUTE ON FUNCTION public.api_claim_incident(bigint) TO authenticated" in sql
assert "GRANT EXECUTE ON FUNCTION public.api_claimable_incidents() TO authenticated" in sql
assert "REVOKE ALL ON FUNCTION public.api_claim_incident(bigint) FROM PUBLIC, anon" in sql

print("PASS: field claims serialize by incident then profile, reject busy units, and respect lifecycle states.")
print("PASS: claimable-incident discovery excludes busy/already-assigned units and stale lifecycle states.")
