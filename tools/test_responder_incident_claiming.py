#!/usr/bin/env python3
"""Source-contract regression checks for responder incident claiming."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
responder_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
dispatcher_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
fake = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()
repo = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
sql = (ROOT / "supabase/migrations/20261115120000_responder_incident_claiming.sql").read_text()

# Check Android UI
for required in ("Available for field claiming", "Claim Incident", "claimIncident", "dismissClaimMessage"):
    assert required in responder_screen, f"Responder UI missing field claiming element: {required}"

for required in ("Allow field claiming", "Disable field claiming", "setIncidentClaimable"):
    assert required in dispatcher_screen, f"Dispatcher UI missing field claiming toggle: {required}"

# Check Models
for required in ("@SerializedName(\"is_claimable\") val isClaimable: Boolean = false", "ClaimIncidentResult"):
    assert required in models, f"Models missing claimable property or result class: {required}"

# Check API & Repository
for required in ('@POST("rest/v1/rpc/api_claim_incident")', '@POST("rest/v1/rpc/api_set_incident_claimable")', '@POST("rest/v1/rpc/api_claimable_incidents")'):
    assert required in api, f"Supabase RPC client missing claiming endpoint: {required}"

for required in ("override suspend fun claimIncident", "override suspend fun claimableIncidents", "override suspend fun setIncidentClaimable"):
    assert required in fake, f"FakeTacApi missing claiming implementation: {required}"

for required in ("suspend fun claimIncident", "suspend fun getClaimableIncidents", "suspend fun setIncidentClaimable"):
    assert required in repo, f"TacRepository missing claiming methods: {required}"

# Check SQL Migration
for required in (
    "is_claimable",
    "CREATE OR REPLACE FUNCTION public.api_set_incident_claimable",
    "CREATE OR REPLACE FUNCTION public.api_claim_incident",
    "CREATE OR REPLACE FUNCTION public.api_claimable_incidents",
    "FOR UPDATE",
    "REVOKE ALL ON FUNCTION public.api_claim_incident",
    "CREATE OR REPLACE FUNCTION public.tacresq_guard_incident_update",
    "OLD.is_claimable IS TRUE",
    "a.user_id = actor_id",
    "p.duty_status = 'available'",
    "SET status = 'assigned'",
):

    assert required in sql, f"SQL migration missing claiming security/schema rule: {required}"

assert "SET assigned_to = v_actor_id" not in sql, "Field claims must not bypass dispatcher-managed assigned_to"
print("PASS: responder incident claiming contract verified across Android UI, API, repository, fake mode, and SQL migration.")
