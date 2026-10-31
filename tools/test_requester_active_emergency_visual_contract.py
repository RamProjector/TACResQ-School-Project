#!/usr/bin/env python3
"""Guard the requester active-emergency hierarchy and semantic action states."""
from pathlib import Path
import re

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

theme = (ROOT / "app/src/main/java/com/tacresq/app/ui/theme/Theme.kt").read_text()
def scheme_colors(scheme_name: str) -> dict[str, str]:
    start = theme.index(f"private val {scheme_name} =")
    end = theme.index("\n)", start)
    block = theme[start:end]
    colors = {}
    for role, value in re.findall(r"(\w+)\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)", block):
        colors[role] = value.upper()
    colors["onPrimary"] = "FFFFFF" if "onPrimary = Color.White" in block else colors.get("onPrimary", "")
    return colors

def luminance(value: str) -> float:
    channels = [int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    linear = [channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4 for channel in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]

def contrast(first: str, second: str) -> float:
    lighter, darker = sorted((luminance(first), luminance(second)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)

for scheme_name in ("TacruLightColors", "TacruDarkColors"):
    palette = scheme_colors(scheme_name)
    for foreground, background in (
        ("onPrimary", "primary"),
        ("onSecondaryContainer", "secondaryContainer"),
        ("onTertiaryContainer", "tertiaryContainer"),
    ):
        assert contrast(palette[foreground], palette[background]) >= 4.5, (
            f"{scheme_name}: {foreground} on {background} fails 4.5:1"
        )
    assert contrast(palette["primary"], palette["surface"]) >= 3.0, (
        f"{scheme_name}: active-card outline fails 3:1 non-text contrast"
    )
    assert contrast(palette["error"], palette["surface"]) >= 4.5, (
        f"{scheme_name}: destructive Cancel text fails 4.5:1"
    )
print("PASS: active emergency uses clear semantic status colors and visibly enabled actions.")
