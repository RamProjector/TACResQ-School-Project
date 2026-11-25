#!/usr/bin/env python3
"""Contract checks for dispatcher-owned incident progress and mirrored team state."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
legacy_schema = (ROOT / "supabase/migrations/20261023103737_per_responder_assignment_status.sql").read_text()
dispatcher_sql = (ROOT / "supabase/migrations/20261122050825_dispatch_managed_incident_progress.sql").read_text()
profile_guard = (ROOT / "supabase/migrations/20261122050827_allow_dispatcher_progress_duty_sync.sql").read_text()
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
api_interface = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/TacApi.kt").read_text()
repo = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
responder = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
dispatcher = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
requester = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
components = (ROOT / "app/src/main/java/com/tacresq/app/ui/common/Components.kt").read_text()
fake = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()
laravel = (ROOT / "backend/src/routes/api.php").read_text()
bridge = (ROOT / "backend/src/config/tacru.php").read_text()

# Retain the assignment-status columns as compatibility data, but no longer
# expose an independently writable per-person status workflow to app clients.
for required in (
    "ADD COLUMN IF NOT EXISTS responder_status text",
    "status_updated_at timestamptz",
    "assigned_user_statuses",
):
    assert required in legacy_schema, f"Missing compatibility field: {required}"

for required in (
    "CREATE OR REPLACE FUNCTION private.tacresq_dispatch_update_incident_status",
    "Only dispatchers can advance incident progress",
    "UPDATE public.incident_assignments",
    "responder_status = p_status",
    "status_updated_at = now()",
    "CREATE OR REPLACE FUNCTION public.api_update_incident_status",
    "private.tacresq_dispatch_update_incident_status",
    "REVOKE ALL ON FUNCTION public.api_update_my_assignment_status(bigint,text) FROM PUBLIC, anon, authenticated",
    "REVOKE ALL ON FUNCTION private.tacresq_update_my_assignment_status(bigint,text) FROM PUBLIC, anon, authenticated",
):
    assert required in dispatcher_sql, f"Missing dispatcher-owned status contract: {required}"

assert "actor_is_assigned" not in dispatcher_sql, "Legacy responder status branch must be removed from incident guard."
assert "dispatcher_progress_sync := coalesce(" in profile_guard
assert "i.status = NEW.duty_status" in profile_guard
assert "REVOKE ALL ON FUNCTION public.tacresq_guard_profile_update()" in profile_guard

# No Android client, Supabase bridge, or fake backend can call the retired RPC.
for source in (api, api_interface, repo, bridge):
    assert "api_update_my_assignment_status" not in source
    assert "updateMyAssignmentStatus" not in source
assert "api.updateIncidentStatus(id, UpdateStatusRequest(status))" in repo

# The demo backend applies one shared incident milestone to all assigned members.
assert "if (me.role != Role.DISPATCHER)" in fake
assert "incident.status.nextSteps()" in fake
assert "ResponderAssignmentStatus(unitId, body.status, timestamp)" in fake

# Laravel prototype endpoint enforces the same dispatcher ownership.
status_route_start = laravel.index("Route::patch('/incidents/{incident}/status'")
status_route_end = laravel.find("\n    Route::", status_route_start + len("Route::patch('/incidents/{incident}/status'"))
status_route = laravel[status_route_start:status_route_end if status_route_end >= 0 else len(laravel)]
assert "$onlyRoles($request, ['dispatcher'])" in status_route
assert "User::whereKey($locked->assigned_to)" in status_route

# Responders can manage optional GPS sharing, but cannot advance incident status.
for retired in ("responseToStart", "startResponse(", "fun advance(", "repo.updateIncidentStatus"):
    assert retired not in responder, f"Responder-owned status action remains: {retired}"
assert "val ownStatus = incident.statusFor(user.id)" in responder
assert "ownStatus != IncidentStatus.EN_ROUTE" in responder
assert "GPS sharing reports your location; it does not confirm arrival" in responder

# Dispatcher and requester surfaces use the shared incident milestone.
assert "StatusPill(incident.status)" in dispatcher
assert "memberStatus" not in dispatcher
assert "Dispatch records one shared progress milestone for the assigned team." in dispatcher
assert "val requesterLabel: String" in models
assert 'PENDING -> "Request received"' in models
assert 'ASSIGNED -> "Team assigned"' in models
assert 'EN_ROUTE -> "Team deployed"' in models
assert 'ON_SCENE -> "Team arrived"' in models
assert 'TRANSPORTING -> "Transport in progress"' in models
assert 'RESOLVED -> "Response completed"' in models
assert 'activeIncident.status.requesterLabel' in requester
assert 'activeIncident.status.requesterDescription' in requester
assert "fun StatusPill(status: IncidentStatus)" in components

print("PASS: assignment rows remain available as compatibility mirrors, not independent responder progress.")
print("PASS: Supabase, Laravel, and fake mode enforce dispatcher-owned incident milestones.")
print("PASS: responders retain optional GPS sharing without per-responder status actions.")
print("PASS: requester and dispatcher screens display one shared incident progress state.")
