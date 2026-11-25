#!/usr/bin/env python3
"""Regression contract for dispatcher claimability lifecycle serialization."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sql = (ROOT / "supabase/migrations/20261122050818_harden_dispatch_claimability_lifecycle.sql").read_text()

required = (
    "IF p_incident_id IS NULL OR p_incident_id < 1 OR p_claimable IS NULL",
    "FROM public.incidents",
    "WHERE id = p_incident_id",
    "FOR UPDATE;",
    "SELECT role INTO v_actor_role",
    "FROM public.profiles",
    "WHERE auth_user_id = v_auth_uid",
    "v_actor.role IS DISTINCT FROM 'dispatcher'",
    "p_claimable IS TRUE AND v_incident.status NOT IN ('pending', 'assigned')",
    "SET is_claimable = p_claimable",
    "RETURNING * INTO v_incident",
    "CREATE TABLE IF NOT EXISTS public.incident_claimability_logs",
    "incident_id bigint NOT NULL",
    "actor_profile_id bigint",
    "ALTER TABLE public.incident_claimability_logs ENABLE ROW LEVEL SECURITY",
    "REVOKE ALL ON TABLE public.incident_claimability_logs FROM PUBLIC, anon, authenticated",
    "GRANT SELECT ON TABLE public.incident_claimability_logs TO service_role",
    "REVOKE INSERT, UPDATE, DELETE ON TABLE public.incidents FROM PUBLIC, anon, authenticated",
    "DO $revoke_incident_column_grants$",
    "REVOKE INSERT (%1$s), UPDATE (%1$s) ON TABLE public.incidents FROM PUBLIC, anon, authenticated",
    "CREATE OR REPLACE FUNCTION public.tacresq_clear_claimability_on_status_progress()",
    "NEW.status IN ('en_route', 'on_scene', 'transporting', 'resolved', 'cancelled', 'completed')",
    "zzz_tacresq_clear_claimability_on_status_progress",
    "CREATE OR REPLACE FUNCTION public.tacresq_log_claimability_status_transition()",
    "'incident_status_transition'",
    "Only pending or assigned incidents can be made claimable.",
    "'migration_backfill'",
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
rpc_start = sql.index("CREATE OR REPLACE FUNCTION public.api_set_incident_claimable(")
claimable_guard = sql.index("p_claimable IS TRUE AND v_incident.status NOT IN", rpc_start)
rpc_update = sql.index("UPDATE public.incidents", rpc_start)
assert claimable_guard < rpc_update, "Lifecycle allowlist must be checked before the RPC updates the incident"

print("PASS: claimability toggle locks and rechecks incident lifecycle before update.")
print("PASS: dispatcher role is re-read under profile lock in incident->profile lock order.")
print("PASS: null inputs, missing incidents, claimable lifecycle allowlist, and RPC grants are guarded.")

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


# Verify the migration replay executes the privilege boundary as authenticated:
# direct table UPDATE must fail, while the requester cancellation RPC succeeds.
integration = (ROOT / "tools/test_authenticated_incident_write_boundary.sql").read_text()
replay = (ROOT / "tools/test_supabase_migration_replay.sh").read_text()
for required in (
    "SET ROLE authenticated",
    "UPDATE public.incidents",
    "EXCEPTION WHEN insufficient_privilege",
    "public.api_cancel_my_incident(incident_id)",
    "public.api_update_incident_status(incident_id, 'en_route')",
    "Authenticated responder status RPC boundary fixture",
    "Authenticated dispatcher direct incident UPDATE was not denied",
    "final_claimable",
    "incident_claimability_logs",
    "source_rpc = 'incident_status_transition'",
    "matching_events",
    "persisted_status",
):
    assert required.lower() in integration.lower(), (
        f"Missing authenticated incident write-boundary assertion: {required}"
    )
assert "tools/test_authenticated_incident_write_boundary.sql" in replay, (
    "Authenticated incident write-boundary test must run in migration replay"
)

assert "public.api_update_incident_status" in integration
assert "SET ROLE authenticated" in integration
assert "UPDATE public.incidents SET status = 'cancelled'" in integration


# Exercise the dispatcher/requester race against two independent authenticated
# PostgreSQL connections after a clean migration replay.
race_test = ROOT / "tools/test_claimability_cancellation_concurrency.sh"
race_sql = race_test.read_text()
for required in (
    "public.api_set_incident_claimable($incident_id, true)",
    "public.api_cancel_my_incident($incident_id)",
    "pg_sleep(30)",
    "pg_stat_activity",
    "pg_blocking_pids",
    "wait_for_two_lock_waiters",
    "application_name",
    "FOR UPDATE",
    "final_state",
    "cancelled:false",
    "SET ROLE authenticated",
):
    assert required.lower() in race_sql.lower(), f"Missing claimability/cancellation race assertion: {required}"
