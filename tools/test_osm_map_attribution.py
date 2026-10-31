#!/usr/bin/env python3
"""Guard visible OpenStreetMap attribution and its official copyright link."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

assert 'text = "© OpenStreetMap contributors"' in SOURCE
assert 'https://www.openstreetmap.org/copyright' in SOURCE
assert 'TileSourceFactory.MAPNIK' in SOURCE
# Keep attribution both on the map canvas and in the expanded sheet.
attributions = [index for index in range(len(SOURCE)) if SOURCE.startswith('text = "© OpenStreetMap contributors"', index)]
assert len(attributions) >= 2, "attribution must remain visible when the sheet is collapsed and expanded"
for attribution in attributions:
    assert '.heightIn(min = 48.dp)' in SOURCE[attribution - 100:attribution + 500], 'attribution link must retain a 48dp touch target'
assert "BottomSheetScaffold(" in SOURCE
assert "sheetPeekHeight = 112.dp" in SOURCE
assert "BottomSheetDefaults.DragHandle()" in SOURCE
assert "Keep OSM attribution visible on the map even while the sheet is collapsed." in SOURCE

print("PASS: GPS map displays persistent OpenStreetMap attribution and links to its copyright page.")
