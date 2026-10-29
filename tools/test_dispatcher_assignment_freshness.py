#!/usr/bin/env python3
"""Regression checks: dispatcher must not assign from stale queue/unit data."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()

checks = {
    "freshness state defaults closed": "val assignmentDataFresh: Boolean = false" in screen,
    "freshness is invalidated while refresh is in flight": "_state.update { it.copy(loading = true, assignmentDataFresh = false) }" in screen,
    "freshness requires successful incident and unit reads": "assignmentDataFresh = incidents.isSuccess && units.isSuccess" in screen,
    "view-model rejects assignment with stale data": "_state.value.busy || !_state.value.assignmentDataFresh || userIds.isEmpty()" in screen,
    "queue exposes an assignment-paused warning": '"Assignment paused"' in screen,
    "new response-team selection is disabled when data is stale": "enabled = !state.busy && state.assignmentDataFresh" in screen,
    "submit button requires fresh data": "enabled = !state.busy && state.assignmentDataFresh && selectedUnitIds.isNotEmpty()" in screen,
}
for label, passed in checks.items():
    assert passed, f"Dispatcher freshness safety regression: {label}"
print("PASS: stale incident or availability data cannot be used to assign a response team.")
