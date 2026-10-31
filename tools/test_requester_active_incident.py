#!/usr/bin/env python3
"""Regression contracts for requester active-request and cancellation rules."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = (ROOT / "supabase/migrations/20261023031810_requester_single_active_and_cancel.sql").read_text().lower()
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
    "cancellation RPC is not executable by anonymous users": "revoke all on function public.api_cancel_my_incident(bigint) from public, anon" in MIGRATION and "grant execute on function public.api_cancel_my_incident(bigint) to authenticated" in MIGRATION,
    "Home hides the form until the emergency request action is pressed": "showrequestform && activeincident == null" in SCREEN.lower() and 'text("request emergency help' in SCREEN.lower(),
    "Home presents the active request instead of a second submission form": 'text("active emergency"' in SCREEN.lower() and 'if (activeincident != null)' in SCREEN.lower(),
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
