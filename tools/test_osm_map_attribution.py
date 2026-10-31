#!/usr/bin/env python3
"""Guard visible OpenStreetMap attribution and its official copyright link."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

assert 'text = "© OpenStreetMap contributors"' in SOURCE
assert 'https://www.openstreetmap.org/copyright' in SOURCE
assert 'TileSourceFactory.MAPNIK' in SOURCE
# Attribution must be outside the expanded-only details block so it remains visible.
attribution = SOURCE.index('text = "© OpenStreetMap contributors"')
assert '.heightIn(min = 48.dp)' in SOURCE[attribution - 100:attribution + 400], 'attribution link must retain a 48dp touch target'
details = SOURCE.index('if (detailsExpanded) {', SOURCE.index('var detailsExpanded'))
assert attribution > details
assert 'modifier = Modifier\n                        .align(Alignment.End)' in SOURCE[attribution - 100:attribution + 400]

print("PASS: GPS map displays persistent OpenStreetMap attribution and links to its copyright page.")
