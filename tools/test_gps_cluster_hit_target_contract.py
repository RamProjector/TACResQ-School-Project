"""Guard forgiving GPS cluster touch targets and nearest-hit resolution."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
required = (
    "val clusterHitRadius = 24f * mapView.resources.displayMetrics.density",
    "val clusterHitArea = RectF(",
    "map.queryRenderedFeatures(clusterHitArea, layerId)",
    ".filter { it.hasProperty(\"point_count\") }",
    ".minByOrNull { candidate ->",
    "map.projection.toScreenLocation(",
    "hypot(",
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS cluster hit-target contract failed: " + ", ".join(missing))
print(f"GPS cluster hit-target contract passed: {len(required)} checks.")
