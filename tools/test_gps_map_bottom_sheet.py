#!/usr/bin/env python3
"""Guard the interactive, non-modal incident details sheet on the GPS map."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text(encoding="utf-8")

assert "BottomSheetScaffold(" in SOURCE
assert "sheetPeekHeight = 128.dp" in SOURCE
assert "sheetMaxWidth = 680.dp" in SOURCE
assert "BottomSheetDefaults.DragHandle()" in SOURCE
assert "ModalBottomSheet(" not in SOURCE
assert "var selectedIncidentId by rememberSaveable" in SOURCE
assert "setOnMarkerClickListener { clickedMarker, map ->" in SOURCE
assert "selectedIncidentId = incident.id" in SOURCE
assert "mapSheetState.bottomSheetState.expand()" in SOURCE
assert "if (mapSheetState.bottomSheetState.currentValue == SheetValue.Expanded)" in SOURCE
assert "mapSheetState.bottomSheetState.partialExpand()" in SOURCE
assert 'selectedIncident?.let { "Incident #${it.id}' in SOURCE
assert "MapIncidentCard(" in SOURCE
assert "AndroidView(" in SOURCE
assert "modifier = Modifier.fillMaxSize()" in SOURCE
assert "GPS Map" in SOURCE
print("PASS: GPS map retains its full-bleed canvas and uses a draggable, non-modal incident detail sheet.")
