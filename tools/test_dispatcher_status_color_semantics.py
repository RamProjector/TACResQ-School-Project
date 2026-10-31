#!/usr/bin/env python3
"""Keep dispatcher incident status colors aligned with the shared Material 3 semantic palette."""
from pathlib import Path

dispatcher = Path(
    "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt"
).read_text(encoding="utf-8")
components = Path(
    "app/src/main/java/com/tacresq/app/ui/common/Components.kt"
).read_text(encoding="utf-8")

# Incident and per-responder statuses must use the typed component, not one generic
# secondary tint that makes every operational state look identical.
assert "StatusPill(incident.status)" in dispatcher
assert "StatusPill(memberStatus.status)" in dispatcher
assert "StatusPill(incident.status.label, MaterialTheme.colorScheme.secondary)" not in dispatcher
assert "StatusPill(memberStatus.status.label, MaterialTheme.colorScheme.secondary)" not in dispatcher

# The shared typed pill maps each status to a paired Material 3 container/on-container
# role, which adapts with the active light/dark color scheme.
for token in (
    "colors.tertiaryContainer to colors.onTertiaryContainer",
    "colors.primaryContainer to colors.onPrimaryContainer",
    "colors.secondaryContainer to colors.onSecondaryContainer",
    "colors.surfaceVariant to colors.onSurfaceVariant",
):
    assert token in components, f"Missing semantic Material 3 status color pair: {token}"

print("Dispatcher status color semantics passed.")
