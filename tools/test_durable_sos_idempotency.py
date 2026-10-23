#!/usr/bin/env python3
"""Static regression checks for durable SOS submission idempotency."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261023080000_durable_sos_idempotency.sql"
SQL = MIGRATION.read_text().lower()
STORE = (ROOT / "app/src/main/java/com/tacresq/app/data/local/SessionStore.kt").read_text()
REPO = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
API = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()

checks = {
    "request keys are unique per requester": "primary key (requester_id, client_request_id)" in SQL,
    "payload mismatch cannot silently reuse a key": "saved_payload is distinct from current_payload" in SQL,
    "concurrent retries serialize on the idempotency row": "for update" in SQL and "on conflict (requester_id, client_request_id) do nothing" in SQL,
    "successful response is saved and returned for retries": "set response_payload = saved_response" in SQL and "return saved_response" in SQL,
    "RPC requires authenticated requester": "auth.uid() is null" in SQL and "profile_row.role <> 'requester'" in SQL,
    "idempotency table is not directly exposed to clients": "revoke all on public.incident_submission_keys from public, anon, authenticated" in SQL,
    "request key is persisted across process restarts": "pending_incident_request_id" in STORE and "pendingIncidentRequestId(fingerprint: String)" in STORE,
    "key is cleared only when it matches the completed request": "clearPendingIncidentRequestId(requestId: String)" in STORE and "== requestId" in STORE,
    "repository fingerprints the full request and reuses persisted key": "pendingIncidentRequestId(fingerprint)" in REPO and "clientRequestId = requestId" in REPO,
    "Android calls versioned idempotent RPC": "api_create_incident_v4" in API and "body.clientRequestId" in API,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} idempotency check(s) failed")
print(f"Durable SOS idempotency source contract OK: {len(checks)} checks.")
