#!/usr/bin/env python3
"""Prevent the bottom navigation from reducing the dedicated GPS map viewport."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
shell = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()
map_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

bottom_bar = shell.split("bottomBar = {", 1)[1].split("},\n    )", 1)[0]
assert "if (selected != AppDestination.MAP)" in bottom_bar, (
    "the five-item bottom navigation must be hidden while the GPS map is selected"
)
assert bottom_bar.index("if (selected != AppDestination.MAP)") < bottom_bar.index("TacruBottomNavigation"), (
    "navigation must be conditionally rendered, not merely restyled"
)
assert "BackHandler { onBack() }" in map_screen, (\n    "Android system Back must close the full-screen map and return to the prior screen"\n)\nassert "modifier = Modifier.fillMaxSize()" in map_screen, (
    "the map surface must continue filling its available viewport"
)
print("GPS map full-viewport navigation contract passed.")
