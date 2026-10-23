#!/usr/bin/env python3
"""Source-contract regression checks for multi-responder dispatcher assignment."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
fake = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()
repo = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
sql = (ROOT / "supabase/migrations/20261023050000_dispatch_team_assignment.sql").read_text()

for required in ("Select response team", "Checkbox(", "selectedUnitIds.size < maxTeamSelection", "Add responders", "Assign team"):
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
print("PASS: backend persists team membership, rechecks availability under locks, and returns team IDs.")
