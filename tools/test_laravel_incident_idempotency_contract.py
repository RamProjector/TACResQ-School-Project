#!/usr/bin/env python3
"""Static regression checks for Laravel incident-submission idempotency."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API = (ROOT / "backend/src/routes/api.php").read_text(encoding="utf-8")
START = API.index("Route::post('/incidents'")
END = API.index("\n    Route::patch('/incidents/{incident}/status'", START)
ROUTE = API[START:END]

checks = {
    "only authenticated requesters may create incidents": (
        "$onlyRoles($request, ['requester'])" in ROUTE
    ),
    "every submission requires a client-generated UUID": (
        "'client_request_id' => ['required', 'uuid']" in ROUTE
    ),
    "requester row lock serializes concurrent submissions": (
        "User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail()" in ROUTE
    ),
    "idempotency key is scoped to the authenticated requester": (
        "->where('requester_id', $request->user()->id)" in ROUTE
        and "->where('client_request_id', $requestId)" in ROUTE
    ),
    "same key with different payload is rejected": (
        "hash_equals((string) $existingKey->request_payload, $requestPayload)" in ROUTE
        and "abort(409, 'This request ID was already used for different request details.')" in ROUTE
    ),
    "completed duplicate returns the original response": (
        "$existingKey->response_payload !== null" in ROUTE
        and "json_decode($existingKey->response_payload, true, 512, JSON_THROW_ON_ERROR)" in ROUTE
        and "'replayed' => true" in ROUTE
    ),
    "in-flight duplicate is not treated as a new incident": (
        "This request is still being processed. Please retry shortly." in ROUTE
    ),
    "active incident guard runs before a new key is inserted": (
        ROUTE.index("$active = Incident::where('requester_id'") <
        ROUTE.index("DB::table('incident_submission_keys')->insert(")
    ),
    "new incident and response snapshot are persisted in one transaction": (
        "DB::transaction(function () use ($data, $request, $requestId, $requestPayload, $incidentJson)" in ROUTE
        and "Incident::create([" in ROUTE
        and "->update(['response_payload' => json_encode($payload, JSON_THROW_ON_ERROR)])" in ROUTE
    ),
    "replayed and first-time responses have distinct HTTP success codes": (
        "response()->json($result['payload'], $result['replayed'] ? 200 : 201)" in ROUTE
    ),
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Laravel incident idempotency check(s) failed")
print(f"Laravel incident-submission idempotency contract OK: {len(checks)} checks.")
