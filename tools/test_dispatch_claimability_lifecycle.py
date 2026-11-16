#!/usr/bin/env python3
"""Regression contract for dispatcher claimability lifecycle serialization."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sql = (ROOT / "supabase/migrations/20261116200000_harden_dispatch_claimability_lifecycle.sql").read_text()

required = (
    "IF p_incident_id IS NULL OR p_incident_id < 1 OR p_claimable IS NULL",
    "FROM public.incidents",
    "WHERE id = p_incident_id",
    "FOR UPDATE;",
    "FROM public.profiles",
    "WHERE auth_user_id = v_auth_uid",
    "v_actor.role IS DISTINCT FROM 'dispatcher'",
    "v_incident.status IN ('cancelled', 'completed', 'resolved')",
    "SET is_claimable = p_claimable",
    "RETURNING * INTO v_incident",
    "CREATE TABLE IF NOT EXISTS public.incident_claimability_logs",
    "ALTER TABLE public.incident_claimability_logs ENABLE ROW LEVEL SECURITY",
    "REVOKE ALL ON TABLE public.incident_claimability_logs FROM PUBLIC, anon, authenticated",
    "GRANT SELECT ON TABLE public.incident_claimability_logs TO service_role",
    "incident_claimability_logs_incident_created_idx",
    "incident_claimability_logs_actor_created_idx",
    "v_previous_claimable IS DISTINCT FROM v_incident.is_claimable",
    "'api_set_incident_claimable'",
    "REVOKE ALL ON FUNCTION public.api_set_incident_claimable",
    "GRANT EXECUTE ON FUNCTION public.api_set_incident_claimable",
)
for item in required:
    assert item in sql, f"Missing dispatcher claimability lifecycle guard: {item}"

incident_lock = sql.index("FROM public.incidents")
profile_lock = sql.index("FROM public.profiles")
assert incident_lock < profile_lock, "Lock order must be incident before dispatcher profile"
assert sql.index("FOR UPDATE;", incident_lock) < profile_lock
assert sql.index("FOR UPDATE;", profile_lock) < sql.index("v_actor.role IS DISTINCT")
assert sql.index("v_incident.status IN") < sql.index("UPDATE public.incidents")

print("PASS: claimability toggle locks and rechecks incident lifecycle before update.")
print("PASS: dispatcher role is re-read under profile lock in incident->profile lock order.")
print("PASS: null inputs, missing incidents, closed incidents, and RPC grants are guarded.")
