#!/usr/bin/env python3
"""Source contracts for the dedicated full-window GPS map and its safe controls."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
shell = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()
map_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

map_branch = shell.split("if (selected == AppDestination.MAP)", 1)[1].split("} else {", 1)[0]
assert "mapContent()" in map_branch
assert "TacruBottomNavigation(" not in map_branch
assert "Scaffold(" in shell and "TacruBottomNavigation(" in shell
assert "BackHandler { onBack() }" in map_screen
assert "Text(\"Back\", style = MaterialTheme.typography.labelLarge)" in map_screen
assert "onClick = onBack" in map_screen
assert ".statusBarsPadding()" in map_screen and ".navigationBarsPadding()" in map_screen
assert "modifier = Modifier.fillMaxSize()" in map_screen
# osmdroid custom controller is the supported API for hiding its legacy zoom widgets.
assert "setBuiltInZoomControls(false)" not in map_screen
assert "CustomZoomButtonsController.Visibility.NEVER" in map_screen
assert "controller.zoomIn()" not in map_screen and "controller.zoomOut()" not in map_screen

# The map uses one compact toolbar, a small set of map filters, and a collapsible
# information sheet rather than several competing floating control stacks.
assert "GpsMapFilterButtons(" in map_screen
assert 'Text("Incidents", style = MaterialTheme.typography.labelLarge, maxLines = 1)' in map_screen
assert 'Text("Field units", style = MaterialTheme.typography.labelLarge, maxLines = 1)' in map_screen
assert 'contentDescription = if (deviceFix == null) "Locate me" else "Refresh location"' in map_screen
assert "enabled = !isLocationRequestInProgress" in map_screen
assert "if (isLocationRequestInProgress) return" in map_screen
assert "isLocationRequestInProgress = false" in map_screen
assert "locationStatus ?: defaultMapStatus" in map_screen
assert "if (detailsExpanded)" in map_screen

# Stale last-known responder positions remain visible but cannot dominate initial framing.
assert "val responderPointsForInitialFrame = validResponderLocations" in map_screen
assert ".filter { it.mapFreshness(mapNow) != MapLocationFreshness.STALE }" in map_screen
assert "validIncidentPoints + responderPointsForInitialFrame" in map_screen
assert "val shouldFrameIncidentPoints = !hasAutoFramedIncidentPoints && validIncidentPoints.isNotEmpty()" in map_screen
assert "onShowActiveUnits = ::showActiveUnits" in map_screen
assert "mapView.overlays.removeAll { it is Marker }" in map_screen

# Location preview is cleared if permission is revoked while the map is backgrounded.
assert "val latestDeviceFix by rememberUpdatedState(deviceFix)" in map_screen
resume_branch = map_screen.split("Lifecycle.Event.ON_RESUME ->", 1)[1].split("Lifecycle.Event.ON_PAUSE ->", 1)[0]
assert "!locationProvider.hasPermission() && latestDeviceFix != null" in resume_branch
assert "deviceFix = null" in resume_branch
assert "The previous location preview was cleared." in resume_branch

# Future timestamps cannot be treated as fresh locations.
assert "if (recorded.isAfter(now)) return null" in map_screen
assert "if (recordedAt.isAfter(now))" in map_screen

print("GPS map full-window, Back, map controls, and location-safety contracts passed.")
