#!/usr/bin/env python3
"""Guard MapLibre/OpenFreeMap attribution and its provider link."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
BUILD = (ROOT / "app/build.gradle.kts").read_text()

assert 'OPENFREEMAP_LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"' in SOURCE
assert 'text = "© OpenMapTiles · © OpenStreetMap contributors"' in SOURCE
assert 'https://openfreemap.org/' in SOURCE
assert 'org.maplibre.gl:android-sdk-opengl:13.7.1' in BUILD
assert "org.osmdroid:" not in BUILD
# Keep provider/data attribution visible in both map information surfaces.
attributions = [index for index in range(len(SOURCE)) if SOURCE.startswith('text = "© OpenMapTiles · © OpenStreetMap contributors"', index)]
assert len(attributions) >= 2, "attribution must remain visible when the sheet is collapsed and expanded"
for attribution in attributions:
    assert '.heightIn(min = 48.dp)' in SOURCE[attribution - 100:attribution + 500], 'attribution link must retain a 48dp touch target'
assert "BottomSheetScaffold(" in SOURCE
assert "sheetPeekHeight = 120.dp" in SOURCE
assert "BottomSheetDefaults.DragHandle()" in SOURCE
assert "MapLibre.getInstance(context)" in SOURCE
assert "map.setStyle(OPENFREEMAP_LIBERTY_STYLE)" in SOURCE

print("PASS: GPS map uses MapLibre/OpenFreeMap Liberty and preserves visible map-data attribution.")
