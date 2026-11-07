#!/usr/bin/env python3
"""Static safety checks for requester last-confirmed status fallback."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STORE = (ROOT / "app/src/main/java/com/tacresq/app/data/local/SessionStore.kt").read_text(encoding="utf-8")
REPO = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text(encoding="utf-8")
SCREEN = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text(encoding="utf-8")
COMPONENTS = (ROOT / "app/src/main/java/com/tacresq/app/ui/common/Components.kt").read_text(encoding="utf-8")

checks = {
    "cached status is owner-scoped": "data class CachedRequesterIncidents(" in STORE and "val ownerUserId: Long" in STORE,
    "cached status is encrypted": "encryptedRequesterIncidentsKey" in STORE and "encrypt(gson.toJson(cached))" in STORE,
    "cached status restores from disk": "getRequesterIncidents(ownerUserId: Long)" in STORE and "dataStore.data.first()" in STORE,
    "cached status validates ownership": "requireIncidentOwner(cached, ownerUserId)" in STORE,
    "repository caches only confirmed network reads": "cacheMyIncidents(incidents: List<Incident>)" in REPO and "store.saveRequesterIncidents" in REPO,
    "requester saves after successful refresh": "repo.cacheMyIncidents(list)" in SCREEN and "syncError = null" in SCREEN,
    "requester falls back only after refresh failure": "repo.cachedMyIncidents()" in SCREEN and ".onFailure { e ->" in SCREEN,
    "fallback is labeled as not current": "Showing the last confirmed status" in SCREEN,
    "sync banner explains cached timestamp": "Showing data last synced" in COMPONENTS,
    "no offline SOS auto-send": "WorkManager" not in SCREEN and "repo.createIncident" in SCREEN,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} requester offline-cache check(s) failed")
print(f"Requester offline-cache source contract OK: {len(checks)} checks.")
