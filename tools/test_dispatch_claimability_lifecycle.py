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
    "SELECT role INTO v_actor_role",
    "FROM public.profiles",
    "WHERE auth_user_id = v_auth_uid",
    "v_actor.role IS DISTINCT FROM 'dispatcher'",
    "v_incident.status IN ('cancelled', 'completed', 'resolved')",
    "SET is_claimable = p_claimable",
    "RETURNING * INTO v_incident",
    "CREATE TABLE IF NOT EXISTS public.incident_claimability_logs",
    "incident_id bigint NOT NULL",
    "actor_profile_id bigint",
    "ALTER TABLE public.incident_claimability_logs ENABLE ROW LEVEL SECURITY",
    "REVOKE ALL ON TABLE public.incident_claimability_logs FROM PUBLIC, anon, authenticated",
    "GRANT SELECT ON TABLE public.incident_claimability_logs TO service_role",
    "REVOKE INSERT, UPDATE, DELETE ON TABLE public.incidents FROM PUBLIC, anon, authenticated",
    "CREATE OR REPLACE FUNCTION public.tacresq_clear_claimability_on_status_progress()",
    "NEW.status IN ('en_route', 'on_scene', 'transporting', 'resolved', 'cancelled', 'completed')",
    "zzz_tacresq_clear_claimability_on_status_progress",
    "CREATE OR REPLACE FUNCTION public.tacresq_log_claimability_status_transition()",
    "'incident_status_transition'",
    "Only pending or assigned incidents can be made claimable.",
    "incident_claimability_logs_incident_created_idx",
    "incident_claimability_logs_actor_created_idx",
    "v_previous_claimable IS NOT DISTINCT FROM p_claimable",
    "A no-op request should not touch updated_at or create audit noise.",
    "v_previous_claimable IS DISTINCT FROM v_incident.is_claimable",
    "'api_set_incident_claimable'",
    "REVOKE ALL ON FUNCTION public.api_set_incident_claimable",
    "GRANT EXECUTE ON FUNCTION public.api_set_incident_claimable",
)
for item in required:
    assert item in sql, f"Missing dispatcher claimability lifecycle guard: {item}"

assert "REFERENCES public.incidents" not in sql
assert "REFERENCES public.profiles" not in sql

incident_lock = sql.index("FROM public.incidents")
profile_lock = sql.index("FROM public.profiles\n  WHERE auth_user_id = v_auth_uid\n  FOR UPDATE")
assert sql.index("SELECT role INTO v_actor_role") < incident_lock < profile_lock, "Precheck must precede incident lock; locked profile must follow incident lock"
assert sql.index("FOR UPDATE;", incident_lock) < profile_lock
assert sql.index("FOR UPDATE;", profile_lock) < sql.index("v_actor.role IS DISTINCT")
assert sql.index("v_incident.status IN") < sql.index("UPDATE public.incidents")

print("PASS: claimability toggle locks and rechecks incident lifecycle before update.")
print("PASS: dispatcher role is re-read under profile lock in incident->profile lock order.")
print("PASS: null inputs, missing incidents, closed incidents, and RPC grants are guarded.")

# The shipped Android and web clients use RPCs for incident writes; they do not
# call the PostgREST incidents table endpoint directly.
android_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
web_api = (ROOT / "backend/web/src/lib/supabase.ts").read_text()
assert 'rest/v1/incidents' not in android_api
assert '@POST("rest/v1/rpc/api_create_incident_v4")' in android_api
assert '@POST("rest/v1/rpc/api_update_my_incident")' in android_api
assert '@POST("rest/v1/rpc/api_set_incident_claimable")' in android_api
assert 'getSupabase().rpc(' in web_api
assert '.from("incidents")' not in web_api

print("PASS: supported Android and web clients use RPCs, so authenticated table DML can be revoked without breaking their write paths.")
