#!/usr/bin/env python3
"""Guard role-aware Material 3 bottom navigation and keep GPS Map a top-level destination."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text(encoding="utf-8")

for destination in ('PRIMARY("Home")', 'SECONDARY("Requests")', 'MAP("GPS Map")', 'ALERTS("Safety")', 'PROFILE("Profile")'):
    assert destination in source, f"missing canonical destination: {destination}"

assert 'Role.DISPATCHER -> listOf("Queue", "Units", "Safety", "Profile")' in source
assert 'Role.RESPONDER, Role.DRIVER -> listOf(if (compact) "Call" else "Current call", "History", "Safety", "Profile")' in source
assert 'Role.REQUESTER -> listOf("Home", "Requests", "Safety", "Profile")' in source
assert "val compact = maxWidth < 400.dp" in source
assert "NavigationBar(" in source, "use the standard Material 3 navigation surface"
assert source.count("NavigationItem(destinations[") == 4, "all four role destinations must remain"
assert "GpsMapNavigationItem(selected, onDestinationSelected, compact)" in source, "central GPS destination must remain"
assert 'text = if (compact) "Map" else "GPS Map"' in source, "GPS label must remain explicit and fit compact widths"
assert source.count("alwaysShowLabel = true") == 2, "both standard and GPS items must keep labels visible"
assert source.count("NavigationBarItem(") == 2, "standard and GPS destinations must use Material 3 items"
assert "NavigationBarItemDefaults.colors(" in source, "selected and unselected states must have explicit theme colours"
assert "selected = selected == destination" in source and "selected = selected == AppDestination.MAP" in source
assert "if (selected != AppDestination.MAP)" not in source, "navigation must remain visible while the map is open"
assert "onClick = { onDestinationSelected(destination) }" in source
assert "onClick = { onDestinationSelected(AppDestination.MAP) }" in source
assert 'Role.RESPONDER, Role.DRIVER -> listOf(if (compact) "Call" else "Current call", "History", "Safety", "Profile")' in source

print("Role-aware Material 3 five-destination navigation contract passed.")
