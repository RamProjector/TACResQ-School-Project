#!/usr/bin/env python3
"""Guard role-aware five-destination navigation and keep GPS Map first-class."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text(encoding="utf-8")

for destination in ('PRIMARY("Home")', 'SECONDARY("Requests")', 'MAP("GPS Map")', 'ALERTS("Safety")', 'PROFILE("Profile")'):
    assert destination in source, f"missing canonical destination: {destination}"

assert 'Role.DISPATCHER -> listOf("Queue", "Units", "Safety", "Profile")' in source
assert 'Role.RESPONDER, Role.DRIVER -> listOf(if (compact) "Call" else "Current call", "History", "Safety", "Profile")' in source
assert 'Role.REQUESTER -> listOf("Home", "Requests", "Safety", "Profile")' in source
assert "val compact = maxWidth < 400.dp" in source
assert "NavigationBar(" in source, "use a consistent Material 3 navigation surface"
assert source.count("NavigationItem(destinations[") == 4, "all four role destinations must remain"
assert "GpsMapNavigationItem(selected, onDestinationSelected, compact)" in source, "GPS must remain a top-level destination"
assert 'label = if (compact) "Map" else "GPS Map"' in source, "GPS label must fit narrow widths"
assert "NavigationDestinationItem(" in source, "all destinations must share one visual and interaction treatment"
assert "contentDescription = label" in source, "destination names must be accessible"
assert "selected = active" in source, "selected destination must be exposed to accessibility services"
assert "role = SemanticsRole.Tab" in source, "destinations must expose a consistent semantic role"
assert "Modifier.size(width = 48.dp, height = 30.dp)" in source, "selected indicator must not shrink the interactive row"
assert ".heightIn(min = 48.dp)" in source, "navigation items need a minimum touch height"
assert "if (selected != AppDestination.MAP)" not in source, "navigation must remain visible while the map is open"
assert "onClick = { onDestinationSelected(destination) }" in source
assert "onClick = { onDestinationSelected(AppDestination.MAP) }" in source
assert "RoundedCornerShape(16.dp)" in source, "all destinations should use the same rounded selection treatment"

print("Role-aware Material 3 five-destination navigation contract passed.")
