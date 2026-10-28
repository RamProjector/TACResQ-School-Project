#!/usr/bin/env python3
"""Contract checks for independent per-unit response progress."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sql = (ROOT / "supabase/migrations/20261023103737_per_responder_assignment_status.sql").read_text()
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
repo = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
responder = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
dispatcher = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
fake = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()

for required in (
    "ADD COLUMN IF NOT EXISTS responder_status text",
    "status_updated_at timestamptz",
    "CREATE OR REPLACE FUNCTION private.tacresq_update_my_assignment_status",
    "WHERE a.incident_id = p_incident_id AND a.user_id = actor_id",
    "Invalid response status transition for this unit",
    "bool_and(a.responder_status = 'resolved')",
    "bool_and(a.responder_status IN ('on_scene','transporting','resolved'))",
    "assigned_user_statuses",
    "assignment_status <> 'en_route'",
    "a.responder_status = 'en_route'",
    "SECURITY INVOKER",
    "REVOKE ALL ON FUNCTION public.api_update_my_assignment_status(bigint, text) FROM PUBLIC, anon",
    "GRANT EXECUTE ON FUNCTION public.api_update_my_assignment_status(bigint, text) TO authenticated",
):
    assert required in sql, f"Missing per-responder status safety contract: {required}"


# Responder UI safety and HCI contract: uncertain server state must never be
# presented as a verified empty queue or an actionable duty status.
for required in (
    'title = "Responder workspace"',
    'subtitle = "Your assignments, duty status, and location sharing"',
    '"Checking status…"',
    '"Status unverified"',
    'canChange = active.isEmpty() && !state.loading && !state.refreshing && state.syncError == null',
    '"Assignments could not be verified"',
    '"Do not assume there are no active calls.',
    '"Active assignments"',
    '"Recent history"',
    'onClick = vm::refresh',
    'enabled = !state.refreshing && !state.loading',
    'if (runQueuedRefresh) refresh()',
    'Result.failure<Nothing>(failure)',
    'state.incidents.filter { it.statusFor(user.id).isActive }',
    'state.incidents.filterNot { it.statusFor(user.id).isActive }',
    'it.message ?: "Could not verify assignments. Refresh and try again."',
    'val isAssignedToMe = currentUserId != null && (',
    'incident?.assignedUserStatuses?.any { it.userId == currentUserId } == true',
    'incident == null || !isAssignedToMe -> "This incident is not verified as assigned to your responder account. Location sharing remains off."',
    'incident?.status?.takeIf { isAssignedToMe }',
):
    assert required in responder, f"Responder UI safety/usability contract missing: {required}"

assert "UPDATE public.profiles" not in sql, "This migration must not bypass profile duty-status safeguards."
assert "public.api_update_incident_status(p_id bigint, p_status text)" in sql, (
    "Legacy status RPC must route through the per-assignment status checks."
)
for source, requirements in (
    (models, ('val assignedUserStatuses: List<ResponderAssignmentStatus>', 'fun Incident.statusFor(userId: Long)')),
    (api, ('api_update_my_assignment_status', 'override suspend fun updateMyAssignmentStatus')),
    (repo, ('api.updateMyAssignmentStatus(id, UpdateStatusRequest(status))',)),
    (responder, ('incident.statusFor(user.id).nextSteps()', 'Your unit:', 'it.statusFor(currentUserId) == IncidentStatus.EN_ROUTE')),
    (dispatcher, ('memberStatus.status.label',)),
    (fake, ('override suspend fun updateMyAssignmentStatus', 'ResponderAssignmentStatus')),
):
    for required in requirements:
        assert required in source, f"Per-responder status integration missing: {required}"
print("PASS: individual response status is stored per assignment with server-side transition checks.")
print("PASS: incident summary advances conservatively and retains a per-unit status payload.")
print("PASS: Android, dispatcher, and fake-mode clients consume individual unit progress.")
