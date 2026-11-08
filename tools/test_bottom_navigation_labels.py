#!/usr/bin/env python3
"""Guard the five-position role-aware navigation and GPS Map emphasis."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text(encoding="utf-8")

for destination in ('PRIMARY("Home")', 'SECONDARY("Requests")', 'MAP("GPS Map")', 'CHAT("Messages")', 'ALERTS("Notifications")', 'PROFILE("Profile")'):
    assert destination in source, f"missing canonical destination: {destination}"

assert 'Role.DISPATCHER -> listOf("Queue", "Units", "Messages", "Profile")' in source
assert 'Role.RESPONDER, Role.DRIVER -> listOf("Call", "History", "Messages", "Profile")' in source
assert 'Role.REQUESTER -> listOf("Home", "Requests", "Messages", "Profile")' in source
assert 'if (role == Role.ADMIN) return' in source
assert 'Role.ADMIN -> return emptyList()' in source
assert '"Admin"' not in source and '"Audit"' not in source
assert "NavigationBar(" in source
assert source.count("NavigationItem(destinations[") == 4
assert "GpsMapNavigationItem(selected, onDestinationSelected)" in source
assert "emphasized = true" in source.split("private fun RowScope.GpsMapNavigationItem", 1)[1].split("@Composable", 1)[0]
assert 'label = "GPS Map"' in source
assert "AppDestination.CHAT -> Icons.Filled.Email" in source
assert "NavigationDestinationItem(" in source
assert "NavigationBarItem(" in source
assert "alwaysShowLabel = true" in source
assert "indicatorColor = if (emphasized)" in source
assert "if (selected == AppDestination.MAP) mapContent() else content()" in source
assert "Scaffold(" in source and "TacruBottomNavigation(" in source
assert "onDestinationSelected(AppDestination.MAP)" in source
print("Five-position navigation, Messages destination, and unified GPS Map styling passed.")
assert "maxLines = 2" in source and "softWrap = true" in source
assert "textAlign = TextAlign.Center" in source
