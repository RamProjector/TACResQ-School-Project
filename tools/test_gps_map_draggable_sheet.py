#!/usr/bin/env python3
"""Guard the persistent, map-first GPS incident sheet contract."""
from pathlib import Path

SOURCE = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text(encoding="utf-8")

REQUIRED = {
    "persistent Material 3 sheet": "BottomSheetScaffold(",
    "starts partially expanded": "initialValue = androidx.compose.material3.SheetValue.PartiallyExpanded",
    "compact map-first peek": "sheetPeekHeight = 148.dp",
    "drag interaction enabled": "sheetSwipeEnabled = true",
    "visible drag handle": "BottomSheetDefaults.DragHandle()",
    "map remains full-bleed behind sheet": "Keep the map full-bleed behind the persistent sheet",
    "map view remains full size": "modifier = Modifier.fillMaxSize(),\n            update = { it.invalidate() }",
    "incident selection expands details": "mapSheetState.bottomSheetState.expand()",
    "selected incident is prioritized": "visibleIncidents.sortedByDescending { it.id == selectedIncidentId }",
    "location privacy disclosure retained": "One-time preview · stays on this device, not uploaded.",
    "responder freshness disclosure retained": "Stale markers are last-known positions, not live.",
    "OpenStreetMap attribution retained": "© OpenStreetMap contributors",
    "GPS provider flow retained": "locationProvider.currentFix()",
    "incident pin flow retained": "visibleIncidents.forEach { incident ->",
}
missing = [label for label, token in REQUIRED.items() if token not in SOURCE]
assert not missing, "GPS map sheet contract missing: " + ", ".join(missing)
assert "detailsExpanded" not in SOURCE, "Old static expand/collapse panel must not return"
assert "ModalBottomSheet(" not in SOURCE, "Map details must remain non-modal so the map stays usable"
print(f"GPS draggable incident sheet contract passed ({len(REQUIRED)} assertions).")
