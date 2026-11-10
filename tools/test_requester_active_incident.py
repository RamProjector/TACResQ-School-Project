#!/usr/bin/env python3
"""Regression contracts for requester active-request and cancellation rules."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = (ROOT / "supabase/migrations/20261023031810_requester_single_active_and_cancel.sql").read_text().lower()
GUARD_FIX = (ROOT / "supabase/migrations/20261108120000_allow_requester_pending_cancellation.sql").read_text().lower()
SCREEN = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
REPO = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
API = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
FAKE = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()

checks = {
    "database trigger serializes all incident creation by requester": "before insert on public.incidents" in MIGRATION and "from public.profiles" in MIGRATION and "for update" in MIGRATION,
    "resolved and cancelled requests do not block a new request": "status not in ('resolved', 'cancelled')" in MIGRATION,
    "active duplicate request gets a clear rejection": "you already have active request #%" in MIGRATION,
    "idempotent RPC takes a row lock before insert": "from public.profiles" in MIGRATION and "for update;" in MIGRATION,
    "cancellation requires authenticated requester role": "auth.uid() is null" in MIGRATION and "profile_row.role <> 'requester'" in MIGRATION,
    "cancellation only applies to requests owned by requester": "requester_id = profile_row.id" in MIGRATION,
    "cancellation only succeeds while pending": "incident_row.status <> 'pending'" in MIGRATION and "contact dispatch" in MIGRATION,
    "requester cancellation is permitted by the update guard only for the owned pending unassigned request": all(token in GUARD_FIX for token in ("new.status = 'cancelled'", "old.status = 'pending'", "actor_role = 'requester'", "actor_id = old.requester_id", "old.assigned_to is null", "not exists (", "public.incident_assignments")),
    "requester cancellation exception preserves report fields and assigned-to metadata": all(token in GUARD_FIX for token in ("new.assigned_to is not distinct from old.assigned_to", "new.type is not distinct from old.type", "new.description is not distinct from old.description", "new.latitude is not distinct from old.latitude", "new.location_source is not distinct from old.location_source")),
    "field-unit status transition validation remains in force": "incident is not assigned to this field unit" in GUARD_FIX and "old.status = 'assigned' and new.status = 'en_route'" in GUARD_FIX and "actor_role not in ('responder','driver')" in GUARD_FIX,
    "cancellation RPC is not executable by anonymous users": "revoke all on function public.api_cancel_my_incident(bigint) from public, anon" in MIGRATION and "grant execute on function public.api_cancel_my_incident(bigint) to authenticated" in MIGRATION,
    "Home hides the form until the emergency request action is pressed": "showrequestform && activeincident == null" in SCREEN.lower() and 'text("request emergency help' in SCREEN.lower(),
    "Home presents the active request instead of a second submission form": 'text("active emergency"' in SCREEN.lower() and 'if (activeincident != null)' in SCREEN.lower(),
    "requester history uses stable account-local ordinal plus a distinct global reference": "internal fun requestersequencenumber" in SCREEN.lower() and 'your request #$sequence · reference #$incidentid' in SCREEN.lower() and "requesterrequestlabel(incident.id, state.incidents)" in SCREEN.lower(),
    "requester edit responses use a coordinate tolerance without weakening other reviewed-field checks": "requestereditresponsematches(" in SCREEN.lower() and "coordinateMatches(updated.latitude, latitude)" in SCREEN and "updated.locationSource == locationSource" in SCREEN,
    "backend incident ID remains the LazyColumn operation key": "items(state.incidents, key = { it.id })" in SCREEN,
    "requester UI offers cancellation confirmation": 'text("cancel emergency request?"' in SCREEN.lower() and 'text("confirm cancellation")' in SCREEN.lower(),
    "repository exposes cancellation through the API": "suspend fun cancelMyIncident(id: Long): Result<Incident>" in REPO and "api.cancelMyIncident(id)" in REPO,
    "Supabase client calls the cancellation RPC": "api_cancel_my_incident" in API and "CancelIncidentRpc(id)" in API,
    "fake backend enforces one active request and pending-only cancellation": "requesterof[it.id] == me.id && it.status.isactive" in FAKE.lower() and "override suspend fun cancelMyIncident" in FAKE and "IncidentStatus.PENDING" in FAKE,
    "definitive client rejection clears the pending SOS envelope": "isdefinitiveincidentrejection" in REPO.lower() and "store.clearPendingIncidentRequestId(pending.requestId, ownerUserId)" in REPO,
    "authentication, timeout, conflict, and rate-limit responses preserve the pending SOS envelope": "httpcode !in setof(400, 401, 403, 408, 409, 425, 429)" in REPO.lower(),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} requester active-incident check(s) failed")
print(f"Requester active-incident source contract OK: {len(checks)} checks.")
