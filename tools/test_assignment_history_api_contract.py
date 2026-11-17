#!/usr/bin/env python3
"""Guard the Android read-only assignment-history transport contract."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/TacApi.kt").read_text()
supabase = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
repository = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
dispatcher = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()

assert "interface TacAssignmentHistoryApi" in api
for field in (
    'SerializedName("incident_id")',
    'SerializedName("user_id")',
    'SerializedName("event_type")',
    'SerializedName("actor_user_id")',
    'SerializedName("assignment_status")',
    'SerializedName("incident_status")',
    'SerializedName("occurred_at")',
):
    assert field in api, f"Missing event field mapping: {field}"

assert "TacAssignmentHistoryApi" in supabase.split("{", 1)[0]
assert '@GET("rest/v1/incident_assignment_events")' in supabase
assert '@Query("incident_id") incidentIdFilter: String' in supabase
assert '@Query("order") order: String' in supabase
assert '@Query("limit") limit: Int' in supabase
assert 'incidentIdFilter = "eq.$incidentId"' in supabase
assert 'order = "occurred_at.desc,id.desc"' in supabase
assert 'columns = "id,incident_id,user_id,event_type,actor_user_id,source,assignment_status,incident_status,occurred_at"' in supabase
assert 'require(incidentId > 0)' in supabase
assert 'require(limit in 1..100)' in supabase

# Do not silently expose this endpoint through the optional bridge transport.
bridge = supabase.split("private interface SupabaseBridgeRpcWire", 1)[1].split(
    "private interface SupabaseRpcWire", 1
)[0]
assert "incidentAssignmentHistory" not in bridge

print("PASS: assignment history uses a bounded, incident-scoped, read-only PostgREST query.")
print("PASS: the optional bridge does not expose an unreviewed history route.")

# The repository keeps the capability optional and validates the request bounds.
assert "api as? TacAssignmentHistoryApi" in repository
assert "repo.incidentAssignmentHistory(incidentId, 50)" in dispatcher
assert "assignmentHistoryLoading" in dispatcher
assert "assignmentHistoryError" in dispatcher
assert '"assigned" -> "Field unit assigned"' in dispatcher
assert '"released" -> "Field unit released"' in dispatcher
assert '"incident_closed" -> "Incident closed"' in dispatcher
assert 'formatTime(event.occurredAt)' in dispatcher
assert "Retry history" in dispatcher
assert 'SectionHeading("Closed incidents"' in dispatcher
assert '"Latest 5 by request date · resolved or cancelled · read-only history"' in dispatcher
assert "IncidentStatus.RESOLVED || it.status == IncidentStatus.CANCELLED" in dispatcher
assert ".take(5)" in dispatcher
assert 'key = { "closed-${it.id}" }' in dispatcher
assert "vm.loadAssignmentHistory(incident.id)" in dispatcher
assert "var expandedHistoryIncidentId by remember { mutableStateOf<Long?>(null) }" in dispatcher
assert "var showHistory by remember(incident.id)" not in dispatcher
assert "var showClosedHistory by remember(incident.id)" not in dispatcher
assert "expandedHistoryIncidentId = if (showHistory) null else incident.id" in dispatcher
assert "expandedHistoryIncidentId = if (showClosedHistory) null else incident.id" in dispatcher

print("PASS: the dispatcher UI consumes the read-only event stream with loading, error, and retry states.")
print("PASS: recent resolved/cancelled incidents retain bounded, read-only assignment-history access.")

# The API returns newest-first; render the retained event window chronologically.
assert dispatcher.count("state.assignmentHistory.asReversed().forEach { event ->") == 2
assert "state.assignmentHistory.forEach { event ->" not in dispatcher

print("PASS: both active and closed incident timelines render retained events oldest-first.")
