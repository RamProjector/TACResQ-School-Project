#!/usr/bin/env python3
"""Source contract for the server-enforced requester edit/cooldown workflow."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261023034639_requester_edit_workflow.sql"
SQL = MIGRATION.read_text().lower()
SCREEN = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
API = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
CANCEL_SQL = (ROOT / "supabase/migrations/20261023031810_requester_single_active_and_cancel.sql").read_text().lower()
FINAL_EDIT_SQL = (ROOT / "supabase/migrations/20261122050815_validate_requester_location_edits.sql").read_text().lower()
MODEL = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()

for required in (
    "requester_edit_count",
    "requester_last_edited_at",
    "incident_request_edit_logs",
    "for update",
    "incident_row.status <> 'pending'",
    "interval '60 seconds'",
    "p_confirm_review",
    "before_values",
    "after_values",
    "revoke all on function public.api_update_my_incident",
    "grant execute on function public.api_update_my_incident",
):
    assert required in SQL, f"Missing server-side requester edit safeguard: {required}"

assert "public.incident_assignments a" in FINAL_EDIT_SQL
assert "where a.incident_id = incident_row.id" in FINAL_EDIT_SQL
assert "or exists (" in FINAL_EDIT_SQL
assert 'rest/v1/rpc/api_update_my_incident' in API
assert 'requester_edit_count' in MODEL
assert 'requester_last_edited_at' in MODEL
assert 'Correct map pin' in SCREEN
assert 'Review changes' in SCREEN
assert 'Confirm and save' in SCREEN
assert 'editCooldownSeconds == 0L' in SCREEN
assert 'requesterEditResponseMatches(' in SCREEN
assert 'coordinateMatches(updated.latitude, latitude)' in SCREEN
assert '<= 0.000001' in SCREEN
assert 'requesterSequenceNumber(incidentId: Long, incidents: List<Incident>)' in SCREEN
assert 'Your request #$sequence · reference #$incidentId' in SCREEN
assert 'requesterRequestLabel(incident.id, state.incidents)' in SCREEN
assert 'MapLoadStatusOverlay(isLoading = !mapDataLoaded && !mapLoadError)' in SCREEN
assert 'rest/v1/rpc/api_cancel_my_incident' in API
assert 'cancelmyincident' in API.lower()
for required in (
    "profile_row.role <> 'requester'",
    "where id = p_id",
    "and requester_id = profile_row.id",
    "for update",
    "if incident_row.status <> 'pending'",
    "set status = 'cancelled'",
    "revoke all on function public.api_cancel_my_incident",
    "grant execute on function public.api_cancel_my_incident",
):
    assert required in CANCEL_SQL, f"Missing requester cancellation safeguard: {required}"

# The requester screen exposes edit/cancel only in the pending state; server RPCs
# remain authoritative if the state changes after the screen was rendered.
assert 'if (activeIncident.status == com.tacresq.app.data.model.IncidentStatus.PENDING)' in SCREEN
assert 'incidentToEdit = activeIncident' in SCREEN
assert 'incidentToCancel = activeIncident.id' in SCREEN
assert 'fun cancelPendingIncident(id: Long)' in SCREEN
assert 'canUseCurrentRequesterActions' in SCREEN
assert 'contact dispatch if a unit may be responding' in SCREEN
assert 'contact dispatch for urgent corrections' in SQL

print("PASS: requester edits are ownership-checked, pending-only, assignment-membership-aware, cooldown-enforced, reviewed, and audited.")
