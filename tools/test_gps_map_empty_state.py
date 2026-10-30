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
    'last known, not live',
    "Stale markers are last-known positions, not live.",
    "Active incidents have no valid map pins.",
    "No incident pins yet.",
    "locationStatus ?: defaultMapStatus",
    ".semantics {",
    "LiveRegionMode.Polite",
    "if (locationStatus != null) liveRegion = LiveRegionMode.Polite",
    "role == Role.DISPATCHER && validResponderLocations.isNotEmpty()",
    'contentDescription = "Center map on active incidents"',
    'contentDescription = "Center map on responder field units"',
    "fun focusIncidentOnMap(incident: Incident)",
    'locationStatus = "Centered on incident #${incident.id}. Check the recorded location time before navigating."',
    "onFocusOnMap = ::focusIncidentOnMap",
    'Text("Show on map", maxLines = 1)',
    'Text("Navigate", maxLines = 1)',
    "modifier = Modifier.weight(1f).heightIn(min = 48.dp)",
    'contentDescription = "Show incident #${incident.id} on map"',
    'contentDescription = "Navigate to incident #${incident.id}"',
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS map empty-state contract failed: " + ", ".join(missing))
print(f"GPS map concise empty-state contract passed: {len(required)} checks.")
