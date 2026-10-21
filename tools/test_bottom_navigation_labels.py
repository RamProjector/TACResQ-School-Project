#!/usr/bin/env python3
"""Guard compact bottom navigation against hidden or mislabeled actions."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text(encoding="utf-8")

for destination in ('PRIMARY("Home")', 'SECONDARY("Requests")', 'MAP("GPS Map")', 'ALERTS("Safety")', 'PROFILE("Profile")'):
    assert destination in source, f"missing canonical destination: {destination}"

assert 'Role.DISPATCHER -> listOf("Queue", "Units", "Safety", "Profile")' in source
assert 'Role.RESPONDER, Role.DRIVER -> listOf("Call", "History", "Safety", "Profile")' in source
assert 'Role.REQUESTER -> listOf("Home", "Requests", "Safety", "Profile")' in source
assert "BoxWithConstraints(modifier = Modifier.fillMaxWidth())" in source
assert "val compact = maxWidth < 400.dp" in source
assert source.count("NavigationItem(destinations[") == 4, "all four role destinations must remain"
assert "GpsMapNavigationItem(selected, onDestinationSelected, compact)" in source, "central map destination must remain"
assert 'if (compact) "Map" else "GPS Map"' in source, "compact map label must remain understandable"
assert 'showLabel' not in source, "do not hide inactive labels to make the bar fit"
assert '.semantics { contentDescription = label }' in source, "destination labels must remain accessible"
assert 'if (selected != AppDestination.MAP)' in source, "full-screen map must remain free of the bottom bar"

print("Five-destination compact navigation contract passed.")
