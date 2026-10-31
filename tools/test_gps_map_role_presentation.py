#!/usr/bin/env python3
"""Guard role-aware GPS map controls, concise permission recovery, and compact map chrome."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text(encoding="utf-8")

for label in (
    'Role.REQUESTER -> if (deviceFix != null) "Your location" else "Your map"',
    'Role.RESPONDER, Role.DRIVER -> "Response map"',
    'Role.DISPATCHER -> "Dispatch overview"',
    'Role.ADMIN -> "Operations map"',
):
    assert label in SOURCE, f"Missing role-specific map title: {label}"

assert "collapsedMapSummary" in SOURCE
assert "val compactMapPane = maxWidth < 360.dp" in SOURCE
assert 'showAppSettingsAction -> "Location access is off · incident pins remain visible."' in SOURCE
assert '"Location access is off"' in SOURCE
assert 'Text("Open settings")' in SOURCE
assert "AssistChip(" in SOURCE
assert "hasIncidents = validIncidentPoints.isNotEmpty()" in SOURCE
assert "hasActiveUnits = validResponderLocations.isNotEmpty()" in SOURCE
assert "FilledTonalButton(" not in SOURCE[SOURCE.index("private fun GpsMapFilterButtons"):SOURCE.index("@OptIn(ExperimentalMaterial3Api::class)")]
assert "emptyMapMessage" in SOURCE
assert "The admin map is read-only." in SOURCE
assert "© OpenStreetMap contributors" in SOURCE
assert "AndroidView(" in SOURCE
print("PASS: GPS map presentation adapts by role and keeps permission recovery and map chrome compact.")
