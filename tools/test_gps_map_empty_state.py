from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
required = (
    "var locationStatus by remember { mutableStateOf<String?>(null) }",
    "val defaultMapStatus = when {",
    "One-time location preview is active on this device only; it is not uploaded.",
    "androidx.compose.runtime.LaunchedEffect(visibleIncidents)",
    "locationStatus = null",
    "Field units: $freshResponderCount fresh, $agingResponderCount aging, $staleResponderCount stale.",
    "Stale pins are last-known locations, not live.",
    "Active incidents exist, but none has valid coordinates. They cannot appear as map pins.",
    "No active incidents with valid coordinates are available for this role yet.",
    'else -> "Waiting for incident locations"',
    'visibleIncidents.isNotEmpty() -> "Incident locations unavailable"',
    'role == Role.DISPATCHER -> "Waiting for incident locations and field units"',
    "locationStatus ?: defaultMapStatus",
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS map empty-state contract failed: " + ", ".join(missing))
print(f"GPS map empty-state contract passed: {len(required)} checks.")
