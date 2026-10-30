#!/usr/bin/env python3
"""Static regression checks for durable SOS submission idempotency."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261023031801_durable_sos_idempotency.sql"
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
    "fake mode does not persist pending SOS envelopes to real DataStore": "if (!persistSessions)" in STORE and "inMemoryPendingIncident" in STORE and "return@withLock inMemoryPendingIncident" in STORE,
    "fake-mode logout clears in-memory pending SOS state": "pendingIncidentMutex.withLock { inMemoryPendingIncident = null }" in STORE,
    "request key is persisted across process restarts": "pending_incident_envelope_v1" in STORE and "getOrCreatePendingIncident(fingerprint: String, payloadJson: String)" in STORE,
    "key is cleared only when it matches the completed request": "clearPendingIncidentRequestId(requestId: String)" in STORE and "saved?.requestId == requestId" in STORE,
    "repository fingerprints the full request and reuses persisted key": "getOrCreatePendingIncident(fingerprint, payloadJson)" in REPO and "pending.payloadJson" in REPO and "clientRequestId = pending.requestId" in REPO,
    "pending payload is restored rather than rebuilt from a fresh GPS fix": "gson().fromjson(pending.payloadjson, createincidentrequest::class.java)" in REPO.lower(),
    "different submission cannot overwrite an ambiguous pending SOS": "PendingIncidentConflictException" in STORE and "saved.fingerprint != fingerprint" in STORE,
    "logout preserves unresolved SOS envelope": "prefs.remove(encryptedSessionKey)" in STORE and "pendingIncidentEnvelopeKey" in STORE,
    "pending location payload is encrypted at rest": "encrypt(gson.toJson(created))" in STORE and "gson.fromJson(decrypt(encoded), PendingIncidentSubmission::class.java)" in STORE,
    "corrupt pending envelope fails closed instead of generating a new key": "hasSavedEnvelope && saved == null" in STORE and "getOrElse { throw PendingIncidentConflictException() }" in STORE,
    "saved SOS can be retrieved without rebuilding from current UI state": "getPendingIncidentSubmission()" in (ROOT / "app/src/main/java/com/tacresq/app/data/local/SessionStore.kt").read_text(),
    "explicit retry reuses the saved payload and request id": "retryPendingIncident()" in REPO and "Gson().fromJson(pending.payloadJson, CreateIncidentRequest::class.java)" in REPO and "copy(clientRequestId = pending.requestId)" in REPO,
    "requester UI offers retry for an unresolved saved SOS": "Retry saved SOS" in (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text() and "pendingSosAvailable" in (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text(),
    "Android calls versioned idempotent RPC": "api_create_incident_v4" in API and "body.clientRequestId" in API,
    "recoverable authentication, timeout, conflict, and rate-limit responses preserve pending SOS": "httpcode !in setof(401, 403, 408, 409, 425, 429)" in REPO.lower() and "error?.httpCode?.let(::isDefinitiveIncidentRejection)" in REPO,
    "both new and retry SOS paths use the same definitive-rejection policy": REPO.count("error?.httpCode?.let(::isDefinitiveIncidentRejection)") == 2,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} idempotency check(s) failed")
print(f"Durable SOS idempotency source contract OK: {len(checks)} checks.")
