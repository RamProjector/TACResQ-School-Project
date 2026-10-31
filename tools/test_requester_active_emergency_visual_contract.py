#!/usr/bin/env python3
"""Guard the requester active-emergency hierarchy and semantic action states."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
components = (ROOT / "app/src/main/java/com/tacresq/app/ui/common/Components.kt").read_text()

start = screen.index('if (activeIncident != null) item {')
end = screen.index('} else if (!showRequestForm && !state.pendingSosAvailable &&', start)
card = screen[start:end]

assert "surfaceContainerLow" in card
assert "StatusPill(activeIncident.status)" in card
assert "activeIncident.status.pillColor()" not in card
assert "ButtonDefaults.buttonColors(" in card
assert "containerColor = MaterialTheme.colorScheme.primary" in card
assert "contentColor = MaterialTheme.colorScheme.onPrimary" in card
assert "BorderStroke(1.dp, MaterialTheme.colorScheme.primary)" in card
assert "BorderStroke(1.dp, MaterialTheme.colorScheme.error)" in card
assert "contentColor = MaterialTheme.colorScheme.primary" in card
assert "contentColor = MaterialTheme.colorScheme.error" in card
assert card.count("heightIn(min = 52.dp)") == 3
assert "defaultElevation = 2.dp" in card

status_start = components.index("fun StatusPill(status: IncidentStatus)")
status_end = components.index("\n@Composable", status_start)
status_pill = components[status_start:status_end]
for token in (
    "IncidentStatus.PENDING -> colors.tertiaryContainer to colors.onTertiaryContainer",
    "IncidentStatus.ASSIGNED, IncidentStatus.EN_ROUTE -> colors.primaryContainer to colors.onPrimaryContainer",
    "colors.secondaryContainer to colors.onSecondaryContainer",
    "colors.surfaceVariant to colors.onSurfaceVariant",
):
    assert token in status_pill, f"Missing semantic status color mapping: {token}"

assert "Banner(message, isError = false, onDismiss = vm::dismissMessages, isSuccess = true)" in screen
print("PASS: active emergency uses clear semantic status colors and visibly enabled actions.")
