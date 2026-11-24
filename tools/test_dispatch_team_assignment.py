#!/usr/bin/env python3
"""Source-contract regression checks for multi-responder dispatcher assignment."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
fake = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()
repo = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
sql = (ROOT / "supabase/migrations/20261023081706_dispatch_team_assignment.sql").read_text()
hardening = (ROOT / "supabase/migrations/20261023082526_harden_dispatch_team_assignment.sql").read_text()
lifecycle_guard = (ROOT / "supabase/migrations/20261124080000_account_lifecycle_team_assignment_guard.sql").read_text()

for required in ("Select response team", "Checkbox(", "selectedUnitIds.size < maxTeamSelection", "Add responders", "Assign team", "This assigns responders to the incident as one response group.", "It does not appoint a team leader"):
    assert required in screen, f"Dispatcher team-selection UI missing: {required}"
for required in ('@SerializedName("assigned_user_ids")', "val assignedUserIds: List<Long>"):
    assert required in models, f"Incident team model missing: {required}"
for required in ('@POST("rest/v1/rpc/api_assign_incident_team")', "AssignIncidentTeamRpc"):
    assert required in api, f"Supabase team assignment client missing: {required}"
for required in ("override suspend fun assignIncidentTeam", "DutyStatus.ASSIGNED"):
    assert required in fake, f"Demo team assignment behavior missing: {required}"
assert "userIds.size <= 5" in repo, "Repository must enforce the five-unit selection limit."
for required in (
    "CREATE TABLE IF NOT EXISTS public.incident_assignments",
    "public.api_assign_incident_team(p_id bigint, p_user_ids bigint[])",
    "FOR UPDATE",
    "One or more selected units are no longer available",
    "assigned_user_ids",
    "Incident is not assigned to this field unit",
):
    assert required in sql, f"Backend team assignment safety rule missing: {required}"

print("PASS: dispatcher can select a bounded response team and submit one atomic assignment.")
for required in ("SET SCHEMA private", "SECURITY INVOKER", "REVOKE ALL ON FUNCTION public.api_assign_incident_team"):
    assert required in hardening, f"Original invoker-safe Data API boundary missing: {required}"

for required in (
    "SECURITY DEFINER",
    "account_status = 'active'",
    "FOR SHARE",
    "tacresq_assign_incident_team_internal",
    "REVOKE ALL ON FUNCTION private.tacresq_assign_incident_team_internal",
):
    assert required in lifecycle_guard, f"Guarded assignment boundary missing: {required}"

print("PASS: backend persists team membership, rechecks availability under locks, and returns team IDs.")

for required in (
    "private.tacresq_assign_incident_team_guarded",
    "p.account_status = 'active'",
    "FOR SHARE",
    "SECURITY DEFINER",
    "SECURITY INVOKER",
    "GRANT USAGE ON SCHEMA private TO authenticated",
    "REVOKE ALL ON FUNCTION private.tacresq_assign_incident_team(bigint, bigint[])",
    "private.tacresq_assign_incident_team(p_id, p_user_ids)",
):
    assert required in lifecycle_guard, f"Active-account team assignment guard missing: {required}"
assert lifecycle_guard.index("p.account_status = 'active'") < lifecycle_guard.index("FOR SHARE")
assert "ALTER FUNCTION public.api_assign_incident_team" not in lifecycle_guard
print("PASS: active-account assignment is serialized in a private definer helper behind a public invoker RPC.")
print("PASS: public assignment wrapper enforces active-dispatcher authorization; private implementation is not client-callable; legacy alias remains invoker.")
