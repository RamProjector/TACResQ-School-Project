from pathlib import Path

root = Path(__file__).resolve().parents[1]
gradle = (root / "app/build.gradle.kts").read_text()
source = (root / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()

for artifact in (
    "androidx.compose.material3.adaptive:adaptive:1.3.0",
    "androidx.compose.material3.adaptive:adaptive-layout:1.3.0",
    "androidx.compose.material3.adaptive:adaptive-navigation:1.3.0",
):
    assert artifact in gradle, f"Missing Material 3 adaptive dependency: {artifact}"

assert "IncidentHistoryAdaptiveScaffold(" in source
assert "rememberListDetailPaneScaffoldNavigator<Long>()" in source
assert "NavigableListDetailPaneScaffold(" in source
assert "ListDetailPaneScaffoldRole.Detail" in source
assert "AnimatedPane" in source
assert "BackNavigationBehavior.PopUntilContentChange" in source
assert "LaunchedEffect(selectedIncidentId)" in source
assert "navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedIncidentId)" in source
assert "navigator.navigateTo(ListDetailPaneScaffoldRole.List, null)" in source
assert "modifier = Modifier.weight(1f)" in source
assert 'Text("View details")' in source
assert "detailId == incident.id" in source
assert "Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)" in source
assert 'Text("Open conversation")' in source
assert "Incident not in the current list" in source

print("Adaptive incident list-detail scaffold contracts passed.")
