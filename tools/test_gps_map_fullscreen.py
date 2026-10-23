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
assert ".padding(top = if (detailsExpanded) 112.dp else 84.dp, end = 12.dp)" in map_screen, (
    "map action buttons must clear the top overlay and remain reachable when details expand"
)
assert "if (detailsExpanded) 330.dp else 74.dp" not in map_screen, (
    "fixed bottom offset can push controls off-screen on short devices"
)
print("GPS map responsive control placement contract passed.")
