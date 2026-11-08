#!/usr/bin/env python3
"""Contract checks for explicit, accessible GPS map camera controls."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

for required in (
    "private fun GpsMapCameraControls(",
    'contentDescription = "Zoom in on map"',
    'contentDescription = "Zoom out on map"',
    'contentDescription = "Fit all visible map locations"',
    "Icons.Default.LocationOn",
    "CameraUpdateFactory.zoomIn()",
    "CameraUpdateFactory.zoomOut()",
    "fun showAllVisiblePoints()",
    '"Showing all incident and field-unit locations. Stale unit pins are last known, not live."',
    'contentDescription = if (sheetExpanded)',
    '"Collapse map details"',
    '"Expand map details"',
    "mapSheetState.bottomSheetState.partialExpand()",
):
    assert required in source, f"Missing map control behavior: {required}"

assert "LocationButton" in source, "The one-time location flow must remain available."
assert "map.uiSettings.setCompassEnabled(true)" in source
assert "map.uiSettings.setCompassGravity(Gravity.TOP or Gravity.RIGHT)" in source
assert "map.uiSettings.setCompassMargins(" in source
assert "Reset map orientation to north" not in source, "Use the visible MapLibre compass, not an ambiguous duplicate button."
assert "BottomSheetScaffold(" in source, "Map details must remain non-modal."
assert "MapIncidentCard(" in source, "Incident details must remain available from the map sheet."
print("PASS: GPS map exposes explicit camera controls and a non-gesture sheet toggle.")
