#!/usr/bin/env python3
"""Guard role-aware five-destination navigation and keep GPS Map first-class."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text(encoding="utf-8")

for destination in ('PRIMARY("Home")', 'SECONDARY("Requests")', 'MAP("GPS Map")', 'ALERTS("Alerts")', 'PROFILE("Profile")'):
    assert destination in source, f"missing canonical destination: {destination}"

assert 'Role.DISPATCHER -> listOf("Queue", "Units", "Alerts", "Profile")' in source
assert 'Role.RESPONDER, Role.DRIVER -> listOf(if (compact) "Call" else "Current call", "History", "Alerts", "Profile")' in source
assert 'Role.REQUESTER -> listOf("Home", "Requests", "Alerts", "Profile")' in source
assert "val compact = maxWidth < 400.dp" in source
assert "NavigationBar(" in source, "use a consistent Material 3 navigation surface"
assert source.count("NavigationItem(destinations[") == 4, "all four role destinations must remain"
assert "GpsMapNavigationItem(selected, onDestinationSelected)" in source, "GPS must remain a top-level destination"
assert 'contentDescription = "GPS Map"' in source, "GPS destination must have a stable accessible label"
assert 'text = "GPS Map"' in source, "preserve the full GPS Map label on compact widths"
assert "NavigationDestinationItem(" in source, "standard destinations should share one visual and interaction treatment"
assert "contentDescription = label" in source, "destination names must be accessible"
assert "selected = active" in source, "selected destination must be exposed to accessibility services"
assert "role = SemanticsRole.Tab" in source, "destinations must expose a consistent semantic role"
assert "Modifier.size(width = 48.dp, height = 30.dp)" in source, "standard icon container must retain its intended size"
assert ".heightIn(min = 48.dp)" in source, "navigation items need a minimum touch height"
assert "if (selected != AppDestination.MAP)" not in source, "navigation must remain visible while the map is open"
assert "onClick = { onDestinationSelected(destination) }" in source
assert "onDestinationSelected(AppDestination.MAP)" in source
assert "RoundedCornerShape(16.dp)" in source, "standard destinations should use consistent rounded selection treatment"
assert "Modifier.size(width = 54.dp, height = 36.dp)" in source, "center GPS action must be visibly larger than adjacent icons"
assert "MaterialTheme.colorScheme.primaryContainer" in source, "GPS action must remain emphasized when unselected"
assert "MaterialTheme.colorScheme.onPrimary" in source, "selected GPS action needs contrasting icon color"

print("Role-aware five-destination navigation and emphasized GPS Map contract passed.")
