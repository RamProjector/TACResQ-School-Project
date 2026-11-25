#!/usr/bin/env python3
"""Characterize the actual dispatcher queue and historical-alert lookup paths."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / "supabase/migrations/20261023081706_dispatch_team_assignment.sql").read_text()
RLS = (ROOT / "supabase/migrations/20261122050809_optimize_rls_policy_initplans.sql").read_text()
ANDROID = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
BRIDGE_CONFIG = (ROOT / "backend/src/config/tacru.php").read_text()
BRIDGE = (ROOT / "backend/src/app/SupabaseBridge.php").read_text()
DISPATCHER = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
LARAVEL = (ROOT / "backend/src/routes/api.php").read_text()

start = SQL.index("CREATE OR REPLACE FUNCTION public.api_all_incidents()")
end = SQL.index("CREATE OR REPLACE FUNCTION public.api_units()", start)
QUEUE_RPC = SQL[start:end]

assert "SECURITY INVOKER" in QUEUE_RPC
assert "role = 'dispatcher'" in QUEUE_RPC
assert "jsonb_agg(" in QUEUE_RPC
assert "LIMIT" not in QUEUE_RPC.upper(), "Document the currently unbounded aggregate; do not mistake Laravel's limit for an RPC bound."
assert "incidents_read_participant_or_dispatcher" in RLS
assert "private.tacresq_actor_role()) = 'dispatcher'" in RLS

assert "override suspend fun allIncidents()" in ANDROID
assert "bridgeRpc.allIncidents(EmptyRpcBody()) else rpc.allIncidents(EmptyRpcBody())" in ANDROID
assert '@POST("bridge/rpc/api_all_incidents")' in ANDROID
assert '@POST("rest/v1/rpc/api_all_incidents")' in ANDROID
assert "'api_all_incidents'" in BRIDGE_CONFIG
assert "callRpc(string $accessToken, string $function, array $arguments)" in BRIDGE
assert "Authorization' => 'Bearer '.$accessToken" in BRIDGE

assert "focusedIncidentId?.let { targetId -> state.incidents.firstOrNull { it.id == targetId } }" in DISPATCHER
assert "This incident is not in the current authorized dispatch queue." in DISPATCHER
assert "Route::get('/incidents'" in LARAVEL
assert "'max:100'" in LARAVEL
assert "Route::get('/incidents/{id}'" not in LARAVEL

print("PASS: Supabase queue RPC is dispatcher-checked and invoker/RLS-backed.")
print("PASS: current Supabase queue RPC aggregates without a SQL row bound.")
print("PASS: Android supports direct Supabase and opt-in Render RPC queue transports.")
print("PASS: Render bridge uses its allowlist and forwards the caller's bearer JWT.")
print("PASS: dispatcher alert focus currently searches only the loaded queue snapshot.")
print("PASS: separate Laravel queue endpoint is capped at 100; no ID-specific route exists.")
