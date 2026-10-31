#!/usr/bin/env python3
"""Guard visible OpenStreetMap attribution and its official copyright link."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

assert '"© OpenStreetMap contributors"' in SOURCE
assert 'https://www.openstreetmap.org/copyright' in SOURCE
assert 'TileSourceFactory.MAPNIK' in SOURCE
# Attribution remains visible in the persistent sheet, whether collapsed or expanded.
attribution = SOURCE.index('"© OpenStreetMap contributors"')
sheet = SOURCE.index('sheetContent = {')
assert attribution > sheet
assert '.heightIn(min = 48.dp)' in SOURCE[attribution - 180:attribution + 300]
assert 'BottomSheetScaffold(' in SOURCE

print("PASS: persistent GPS sheet retains accessible OpenStreetMap attribution.")
