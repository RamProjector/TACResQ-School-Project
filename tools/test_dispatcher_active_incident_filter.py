#!/usr/bin/env python3
"""Regression checks: terminal incidents must never be shown as active."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()

assert "val activeIncidents = state.incidents.filter { it.status.isActive }" in screen, (
    "The active incident collection must use the shared lifecycle predicate."
)
assert "items(activeIncidents, key = { it.id })" in screen, (
    "The Active incidents section must render the filtered active collection."
)
assert "val recentClosedIncidents = state.incidents" in screen, (
    "Resolved/cancelled incidents should be handled separately from active incidents."
)
assert ".filter { it.status == IncidentStatus.RESOLVED || it.status == IncidentStatus.CANCELLED }" in screen, (
    "The closed-history collection must explicitly contain only terminal statuses."
)
assert "val active = activeIncidents.size" in screen, (
    "The overview active count must derive from the same active collection as the list."
)

print("PASS: terminal incidents are excluded from the active dispatcher list and handled as closed history.")
