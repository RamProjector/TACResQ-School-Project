#!/usr/bin/env python3
"""Keep the five-destination navigation available on the GPS map."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
shell = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()
map_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

bottom_bar = shell.split("bottomBar = {", 1)[1].split("},\n    )", 1)[0]
assert "if (selected != AppDestination.MAP)" not in bottom_bar, (
    "the five-item bottom navigation must remain visible while the GPS map is selected"
)
assert "TacruBottomNavigation(" in bottom_bar, (
    "the shared five-destination navigation must remain mounted on the map screen"
)
assert "BackHandler { onBack() }" in map_screen, (
    "Android system Back must close the full-screen map and return to the prior screen"
)
assert "modifier = Modifier.fillMaxSize()" in map_screen, (
    "the map surface must continue filling its available viewport"
)
print("GPS map persistent-navigation contract passed.")

# Floating controls stay in the upper-right map area so a fixed bottom offset
# cannot push them outside the viewport on short screens.
assert ".align(Alignment.TopEnd)" in map_screen, (
    "map action buttons must stay in the upper-right viewport"
)
assert "val mapActionsTopPadding = when {" in map_screen, (
    "map action controls need a responsive top offset"
)
assert "hasValidatedInternet == false -> if (detailsExpanded) 184.dp else 156.dp" in map_screen, (
    "map action buttons must clear the offline banner when connectivity is unavailable"
)
assert ".padding(top = mapActionsTopPadding, end = 12.dp)" in map_screen, (
    "map action buttons must use the responsive offset and remain reachable"
)
assert "if (detailsExpanded) 330.dp else 74.dp" not in map_screen, (
    "fixed bottom offset can push controls off-screen on short devices"
)
print("GPS map responsive control placement contract passed.")

# Keep spatial actions compact so they do not form a tall stack over the map.
assert 'Text("Incidents", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)' in map_screen
assert 'Text("Field units", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)' in map_screen
assert 'contentDescription = if (deviceFix == null) "Locate me" else "Refresh location"' in map_screen, (
    "the compact recenter control must remain accessible to screen readers"
)
assert "Modifier.widthIn(min = 100.dp).heightIn(min = 48.dp)" in map_screen
assert "Modifier.widthIn(min = 112.dp).heightIn(min = 48.dp)" in map_screen
assert "Modifier.size(48.dp)" in map_screen, "recenter target must remain at least 48dp"
assert "enabled = !isLocationRequestInProgress" in map_screen, (
    "Locate me must be disabled while a location request is in flight"
)
assert "if (isLocationRequestInProgress) return" in map_screen, (
    "rapid taps must not launch overlapping device-location requests"
)
assert "isLocationRequestInProgress = false" in map_screen, (
    "location action state must reset in finally after success or failure"
)
assert "horizontalArrangement = Arrangement.spacedBy(8.dp)" in map_screen
print("GPS map compact action-row and touch-target contract passed.")

# The chevron must describe the action available in the current state.
assert """imageVector = if (detailsExpanded)
                                androidx.compose.material.icons.Icons.Default.ExpandLess
                            else androidx.compose.material.icons.Icons.Default.ExpandMore,""" in map_screen, (
    "expanded details should show collapse; collapsed details should show expand"
)
print("GPS map details affordance contract passed.")

# A location preview is in-memory only and must be cleared if the user revokes
# location permission while the map is backgrounded in Android Settings.
assert "val latestDeviceFix by rememberUpdatedState(deviceFix)" in map_screen
assert "Lifecycle.Event.ON_RESUME ->" in map_screen
resume_branch = map_screen.split("Lifecycle.Event.ON_RESUME ->", 1)[1].split(
    "Lifecycle.Event.ON_PAUSE ->", 1
)[0]
assert "!locationProvider.hasPermission() && latestDeviceFix != null" in resume_branch
assert "deviceFix = null" in resume_branch
assert "The previous location preview was cleared." in resume_branch
print("GPS map permission-revocation lifecycle contract passed.")
