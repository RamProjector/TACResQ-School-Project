#!/usr/bin/env python3
"""Regression checks: terminal incidents must not appear in the active queue."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()

active_section = screen.split('item { SectionHeading("Active incidents") }', 1)[1].split(
    'item { SectionHeading("Field units"', 1
)[0]

assert "items(state.incidents.filter { it.status.isActive }, key = { it.id })" in active_section, (
    "Active incidents must use the same lifecycle predicate as the active summary count."
)
assert "it.status != IncidentStatus.PENDING" not in active_section, (
    "Filtering only pending incidents incorrectly includes resolved/cancelled terminal incidents."
)
assert "val active = state.incidents.count { it.status.isActive }" in screen, (
    "The overview active count must use the shared active lifecycle predicate."
)

print("PASS: resolved and cancelled incidents are excluded from the active dispatcher list.")
