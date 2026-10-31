from pathlib import Path

root = Path(__file__).resolve().parents[1]
gradle = (root / "app/build.gradle.kts").read_text()
source = (root / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
navigation = (root / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()

for artifact in (
    "androidx.compose.material3.adaptive:adaptive:1.3.0",
    "androidx.compose.material3.adaptive:adaptive-layout:1.3.0",
    "androidx.compose.material3.adaptive:adaptive-navigation:1.3.0",
):
    assert artifact in gradle, f"Missing Material 3 adaptive dependency: {artifact}"

assert "IncidentHistoryAdaptiveScaffold(" in source
assert "rememberListDetailPaneScaffoldNavigator<Long>(" in source
assert "NavigableListDetailPaneScaffold(" in source
assert "ListDetailPaneScaffoldRole.Detail" in source
assert "AnimatedPane" in source
assert "BackNavigationBehavior.PopUntilContentChange" in source
assert "LaunchedEffect(selectedIncidentId)" in source
assert "navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedIncidentId)" in source
assert "navigator.navigateTo(ListDetailPaneScaffoldRole.List, null)" in source
assert "modifier = Modifier.weight(1f)" in source
assert "detailId == incident.id" in source
components = (root / "app/src/main/java/com/tacresq/app/ui/common/Components.kt").read_text()
assert "BorderStroke(" in components
assert "if (selected) 2.dp else 1.dp" in components
assert "if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant" in components
assert '"Open conversation"' in components
assert "Incident not in the current list" in source


assert "currentWindowAdaptiveInfoV2()" in source
assert "currentWindowAdaptiveInfo(" not in source
assert "calculatePaneScaffoldDirective(adaptiveInfo)" in source
assert "minWidthDp >= 600" in source
assert "minWidthDp >= 840" in source
assert "minWidthDp >= 1200" in source
assert "minWidthDp >= 1600" in source
assert "minHeightDp < 480" in source
assert "minWidthDp >= 840 && !compactHeight" in source
assert "baseDirective.copy(maxHorizontalPartitions = 1)" in source
assert ".widthIn(max = 560.dp)" in source
assert ".widthIn(max = 680.dp)" in source
assert "horizontalGutter" in source
assert "verticalGutter" in source

assert "selected: Boolean = false" in (root / "app/src/main/java/com/tacresq/app/ui/common/Components.kt").read_text()
assert "MaterialTheme.colorScheme.primaryContainer" in (root / "app/src/main/java/com/tacresq/app/ui/common/Components.kt").read_text()
assert "AdaptivePaneHeader(" in source
assert 'eyebrow = "INCIDENT HISTORY"' in source
assert 'eyebrow = "SELECTED INCIDENT"' in source
assert "surfaceContainerLow" in source
assert "selected = detailId == incident.id" in source
assert "IncidentHistoryListItem(" in source
assert "LazyColumn(" in source
assert "items(sortedIncidents, key = { it.id })" in source
assert "sortedIncidents.forEach" not in source
assert "IncidentHistoryDetail(" in source
assert "fun IncidentHistoryListItem(" in components
assert "selectable(selected = selected, role = Role.Button, onClick = onClick)" in components
assert "fun IncidentHistoryDetail(" in components
assert '"CURRENT STATUS"' in components
assert '"EMERGENCY DETAILS"' in components
assert '"LOCATION"' in components

# Top-level navigation adapts with the actual app window instead of keeping
# five destinations compressed into a bottom bar on expanded screens.
assert "currentWindowAdaptiveInfoV2()" in navigation
assert "@OptIn(ExperimentalMaterial3AdaptiveApi::class)" in navigation
assert "minWidthDp >= 840" in navigation
assert "minHeightDp >= 480" in navigation
assert "TacruNavigationRail(" in navigation
assert "NavigationRail(" in navigation
assert "roleNavigationDestinations(role).take(2)" in navigation
assert "roleNavigationDestinations(role).drop(2)" in navigation
assert "NavigationRailItem(" in navigation
assert "TacruBottomNavigation(" in navigation
assert "Scaffold(" in navigation
assert "if (useNavigationRail)" in navigation
assert ".padding(padding)" in navigation
assert "AppDestination.MAP" in navigation
assert 'label = "GPS Map"' in navigation
assert "emphasized = true" in navigation
assert "selected == AppDestination.MAP" in navigation
assert "if (selected == AppDestination.MAP)" in navigation
assert "mapContent()" in navigation

print("Adaptive incident list-detail and role navigation scaffold contracts passed.")
