#!/usr/bin/env python3
"""Static safety checks for the durable requester SOS form draft."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STORE = (ROOT / "app/src/main/java/com/tacresq/app/data/local/SessionStore.kt").read_text(encoding="utf-8")
REPO = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text(encoding="utf-8")
SCREEN = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text(encoding="utf-8")

checks = {
    "draft has an explicit owner": "data class RequesterDraft(" in STORE and "val ownerUserId: Long" in STORE,
    "draft is encrypted at rest": "encryptedRequesterDraftKey" in STORE and "encrypt(gson.toJson(draft))" in STORE,
    "draft restores from persistent storage": "getRequesterDraft(ownerUserId: Long)" in STORE and "dataStore.data.first()" in STORE,
    "draft ownership is checked on restore": "requireDraftOwner(draft, ownerUserId)" in STORE,
    "draft ownership is checked before clear": "requireDraftOwner(draft, ownerUserId)" in STORE and "prefs.remove(encryptedRequesterDraftKey)" in STORE,
    "repository scopes draft to logged-in requester": "saveRequesterDraft(" in REPO and "sessionState.value as? SessionState.LoggedIn" in REPO,
    "requester restores draft on screen start": "vm.restoreRequesterDraft()" in SCREEN and "restoredDraft" in SCREEN,
    "requester debounces local draft writes": "delay(500)" in SCREEN and "vm.saveRequesterDraft(selectedType, description, locationNotes)" in SCREEN,
    "local copy is clearly not server-confirmed": "Draft saved on this phone" in SCREEN and "Nothing is sent to dispatch" in SCREEN,
    "draft clears after confirmed submission": "viewModelScope.launch { repo.clearRequesterDraft() }" in SCREEN,
    "draft clears only after explicit discard": "vm.clearRequesterDraft()" in SCREEN and "Discard this request draft?" in SCREEN,
    "no automatic background worker was introduced": "WorkManager" not in SCREEN and "enqueue" not in SCREEN,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} requester draft check(s) failed")
print(f"Requester draft persistence source contract OK: {len(checks)} checks.")
