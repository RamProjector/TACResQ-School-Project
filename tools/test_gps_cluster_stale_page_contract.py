"""Guard truthful handling of stale GPS cluster-page results."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
required = (
    "var unavailablePage = false",
    "leaves.isEmpty() && cursor.nextOffset < cursor.totalCount",
    "sourceChanged = true",
    "val addedBeforeCursor = added.size",
    "leaves.isNotEmpty() && added.size == addedBeforeCursor",
    "unavailablePage = true",
    "Some clustered records are no longer available. Displayed locations are current records only.",
    "The map refreshed while loading more locations. Tap the current cluster again.",
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS stale-cluster-page contract failed: " + ", ".join(missing))
print(f"GPS stale-cluster-page contract passed: {len(required)} checks.")
