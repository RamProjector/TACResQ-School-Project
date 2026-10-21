#!/usr/bin/env python3
"""Guard the five-destination mobile navigation against hidden or mislabeled actions."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()

for destination in ('PRIMARY("Home")', 'SECONDARY("Requests")', 'MAP("GPS Map")', 'ALERTS("Safety")', 'PROFILE("Profile")'):
    assert destination in source, f"missing canonical destination: {destination}"

assert 'Role.DISPATCHER -> listOf("Queue", "Units", "Safety", "Profile")' in source, (
    "dispatcher navigation must label the account destination Profile, matching its content"
)
assert 'Role.RESPONDER, Role.DRIVER -> listOf("Call", "History", "Safety", "Profile")' in source
assert 'Role.REQUESTER -> listOf("Home", "Requests", "Safety", "Profile")' in source

navigation = source.split('private fun TacruBottomNavigation(', 1)[1].split('@Composable', 1)[0]
assert navigation.count('NavigationItem(destinations[') == 4, "four role-specific destinations must remain"
assert 'GpsMapNavigationItem(selected, onDestinationSelected, compact)' in navigation, (
    "the emphasized central map destination must remain present"
)
assert 'showLabel' not in source, "do not hide inactive destination labels on narrow screens"
item = source.split('private fun RowScope.NavigationItem(', 1)[1].split('@Composable', 1)[0]
assert 'text = label,' in item, "each destination must render its visible label"
assert '.weight(1f)' in item, "each destination must receive a predictable share of navigation width"
assert '.semantics { contentDescription = label }' in item, "each destination must retain an accessible label"
assert 'if (selected != AppDestination.MAP)' in source, "the full-screen map must remain free of the bottom bar"

print("Five-destination navigation label contract passed.")
