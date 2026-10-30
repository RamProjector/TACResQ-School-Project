#!/usr/bin/env python3
"""Guard role-aware five-destination navigation and a consistent GPS destination."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text(encoding="utf-8")

for destination in ('PRIMARY("Home")', 'SECONDARY("Requests")', 'MAP("GPS Map")', 'ALERTS("Alerts")', 'PROFILE("Profile")'):
    assert destination in source, f"missing canonical destination: {destination}"

assert 'Role.DISPATCHER -> listOf("Queue", "Units", "Alerts", "Profile")' in source
assert 'Role.RESPONDER, Role.DRIVER -> listOf("Call", "History", "Alerts", "Profile")' in source
assert 'Role.REQUESTER -> listOf("Home", "Requests", "Alerts", "Profile")' in source
assert "NavigationBar(" in source
assert source.count("NavigationItem(destinations[") == 4
assert "GpsMapNavigationItem(selected, onDestinationSelected)" in source
assert 'label = "GPS Map"' in source
assert "icon = Icons.Filled.LocationOn" in source
assert "NavigationDestinationItem(" in source
assert "NavigationBarItem(" in source
assert "alwaysShowLabel = true" in source
assert "NavigationBarItemDefaults.colors(" in source
assert "indicatorColor = if (emphasized)" in source
assert "selected = active" in source
map_branch = source.split("if (selected == AppDestination.MAP)", 1)[1].split("} else {", 1)[0]
assert "mapContent()" in map_branch
assert "TacruBottomNavigation(" not in map_branch
assert "TacruBottomNavigation(" in source
assert "onDestinationSelected(AppDestination.MAP)" in source

print("Role-aware five-destination navigation and unified GPS Map styling passed.")
