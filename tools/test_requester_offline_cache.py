#!/usr/bin/env python3
"""Supplemental source-contract checks for requester last-confirmed status recovery."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text(encoding="utf-8")
STORE = (ROOT / "app/src/main/java/com/tacresq/app/data/local/SessionStore.kt").read_text(encoding="utf-8")
REPO = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text(encoding="utf-8")
SCREEN = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
retry_start = SCREEN.index("fun retryPendingSos()")
discard_start = SCREEN.index("fun discardRejectedPendingSos()", retry_start)
refresh_start = SCREEN.index("fun refresh()", discard_start)
retry_method = SCREEN[retry_start:discard_start]
discard_method = SCREEN[discard_start:refresh_start]

checks = {
    "offline snapshot has only identifier, type, and status": (
        "data class CachedRequesterIncidentStatus(" in MODELS
        and "val incidentId: Long" in MODELS
        and "val type: IncidentType" in MODELS
        and "val status: IncidentStatus" in MODELS
        and "val description:" not in MODELS[MODELS.index("data class CachedRequesterIncidentStatus("):MODELS.index("fun Incident.statusFor")]
    ),
    "repository maps full API records to the allowlisted DTO": (
        "incidents.map(Incident::toCachedRequesterIncidentStatus)" in REPO
        and "Gson().toJson(statusSnapshots)" in REPO
        and "Gson().toJson(incidents)" not in REPO
    ),
    "cache is encrypted and owner-scoped": (
        "encryptedRequesterIncidentsKey" in STORE
        and "encrypt(gson.toJson(cached))" in STORE
        and "requireIncidentOwner(cached, ownerUserId)" in STORE
    ),
    "cache is bounded to 24 hours and expired entries are deleted": (
        "REQUESTER_STATUS_CACHE_MAX_AGE_MILLIS" in STORE
        and "isRequesterStatusCacheFresh(cached.cachedAtEpochMillis, System.currentTimeMillis())" in STORE
        and "it.remove(encryptedRequesterIncidentsKey)" in STORE
    ),
    "logout clears cached requester status": "prefs.remove(encryptedRequesterIncidentsKey)" in STORE,
    "cache write is awaited before fresh state is published": (
        "repo.cacheMyIncidents(list)" in SCREEN
        and "viewModelScope.launch { repo.cacheMyIncidents(list) }" not in SCREEN
    ),
    "fallback uses a separate, read-only status list": (
        "cachedIncidentStatuses = cached.first" in SCREEN
        and "incidents = emptyList()" in SCREEN
        and "These may have changed. Creating, editing, cancelling, and opening incident messages require a live refresh." in SCREEN
    ),
    "create, edit, and cancel reject cache-only status": (
        SCREEN.count("canUseCurrentRequesterActions(_state.value.hasCurrentIncidentStatus, _state.value.refreshInProgress)") >= 2
        and "canUseCurrentRequesterActions(current.hasCurrentIncidentStatus, current.refreshInProgress)" in SCREEN
        and "showRequestForm && activeIncident == null && requesterActionsEnabled" in SCREEN
        and "Current incident status unavailable" in SCREEN
    ),
    "refresh-in-progress blocks status-dependent actions in UI and ViewModel": (
        "val refreshInProgress: Boolean = false" in SCREEN
        and "it.copy(refreshInProgress = true)" in SCREEN
        and SCREEN.count("refreshInProgress = false") >= 3
        and "requesterActionsEnabled = canUseCurrentRequesterActions" in SCREEN
        and "enabled = requesterActionsEnabled" in SCREEN
        and "enabled = !state.sending && requesterActionsEnabled" in SCREEN
        and "Incident actions and saved-SOS recovery are paused until this check finishes." in SCREEN
    ),
    "saved SOS recovery remains separate and uses the original idempotency key": (
        "A saved-SOS recovery, if present below, uses its original idempotency key." in SCREEN
        and "repo.retryPendingIncident()" in SCREEN
        and "repo.discardPendingIncident()" in SCREEN
        and "current.pendingSosCanBeDiscarded" in SCREEN
        and "current.sending || current.refreshInProgress" in retry_method
        and "hasCurrentIncidentStatus" not in retry_method
        and "current.pendingSosCanBeDiscarded" in discard_method
        and "hasCurrentIncidentStatus" not in discard_method
        and "enabled = !state.sending && !state.refreshInProgress" in SCREEN
    ),
    "cache static contract is wired into CI": "python3 tools/test_requester_offline_cache.py" in WORKFLOW,
    "no background SOS auto-send is introduced": "WorkManager" not in SCREEN,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} requester offline-cache check(s) failed")
print(f"Requester offline-cache source contract OK: {len(checks)} checks.")
