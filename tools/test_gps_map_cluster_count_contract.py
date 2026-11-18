"""Guard accurate totals for mixed and paged GPS map clusters."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

required = (
    "totalCount: Long? = null",
    "clusterChoiceTotalCount = totalCount?.takeIf { it > 0L }",
    "pageCursors.sumOf { it.totalCount }.takeIf { it > 0L }",
    "val totalCount = clusterHits.sumOf { hit ->",
    'hit.second.getNumberProperty("point_count")?.toLong() ?: 0L',
    "showClusterChoices(choices, true, pageCursorsForHits(clusterHits), totalCount)",
    'feature.getNumberProperty("point_count")?.toLong(),',
    "clusterPageCursors = pageCursors",
)

missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS map cluster-count contract failed: " + ", ".join(missing))
print(f"GPS map cluster-count contract passed: {len(required)} checks.")
