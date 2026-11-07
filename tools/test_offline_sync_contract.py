from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
worker = (ROOT / "app/src/main/java/com/tacresq/app/data/sync/RequesterIncidentReadSyncWorker.kt").read_text()
scheduler = (ROOT / "app/src/main/java/com/tacresq/app/data/sync/OfflineSyncScheduler.kt").read_text()
app = (ROOT / "app/src/main/java/com/tacresq/app/TacResqApp.kt").read_text()
policy = (ROOT / "app/src/main/java/com/tacresq/app/data/sync/OfflineSyncPolicy.kt").read_text()

def check(label, passed):
    print(f"{'PASS' if passed else 'FAIL'} {label}")
    return passed

checks = [
    check("worker uses WorkManager CoroutineWorker", "CoroutineWorker" in worker and "doWork" in worker),
    check("worker requires a logged-in requester", "SessionState.LoggedIn" in worker and "Role.REQUESTER" in worker),
    check("worker requires validated network", "NET_CAPABILITY_VALIDATED" in worker and "hasValidatedNetwork" in worker),
    check("worker refreshes and caches only requester reads", "repository.myIncidents()" in worker and "repository.cacheMyIncidents" in worker),
    check("worker has no emergency or mutation calls", all(token not in worker for token in (
        "createIncident(", "retryPendingIncident(", "cancelMyIncident(",
        "updateIncidentStatus(", "recordResponderLocation(", "sendIncidentMessage(",
        "markNotificationRead(",
    ))),
    check("worker handles auth failure without retry loop", "error.httpCode in setOf(401, 403)" in worker),
    check("worker bounds transient retries", "MAX_RETRY_ATTEMPTS" in worker and "runAttemptCount" in worker),
    check("scheduler uses unique constrained periodic work", all(token in scheduler for token in (
        "enqueueUniquePeriodicWork", "ExistingPeriodicWorkPolicy.KEEP",
        "NetworkType.CONNECTED", "BackoffPolicy.EXPONENTIAL",
    ))),
    check("scheduler starts from application lifecycle", "OfflineSyncScheduler.ensureScheduled(this)" in app),
    check("policy explicitly rejects mutations", "REJECT_MUTATION" in policy and "CREATE_SOS" in policy),
]
if not all(checks):
    raise SystemExit(1)
print(f"Offline sync source contract OK: {len(checks)} checks.")
