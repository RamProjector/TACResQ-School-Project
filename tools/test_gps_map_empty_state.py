"""Guard concise map empty states and location-preview disclosure."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

required = (
    "var locationStatus by remember { mutableStateOf<String?>(null) }",
    "val defaultMapStatus = when {",
    "One-time preview · stays on this device, not uploaded.",
    "androidx.compose.runtime.LaunchedEffect(visibleIncidents)",
    "locationStatus = null",
    "Units: $freshResponderCount fresh · $agingResponderCount aging · $staleResponderCount stale",
    '"Field-unit locations"',
    '"last known, not live"',
    "Stale markers are last-known positions, not live.",
    "Active incidents have no valid map pins.",
    "No incident pins yet.",
    "locationStatus ?: defaultMapStatus",
    ".semantics {",
    "LiveRegionMode.Polite",
    "if (locationStatus != null) liveRegion = LiveRegionMode.Polite",
    "role == Role.DISPATCHER && validResponderLocations.isNotEmpty()",
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS map empty-state contract failed: " + ", ".join(missing))
print(f"GPS map concise empty-state contract passed: {len(required)} checks.")
