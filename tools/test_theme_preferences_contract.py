#!/usr/bin/env python3
"""Guard persistent theme preferences and Profile-to-root state propagation."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
main_activity = (ROOT / "app/src/main/java/com/tacresq/app/MainActivity.kt").read_text()
app_root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
preferences = (ROOT / "app/src/main/java/com/tacresq/app/ui/theme/ThemePreferences.kt").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()

# Every authenticated role reaches the same Profile section with the real root state.
assert app_root.count("themeMode = themeMode,") == 3
assert app_root.count("onThemeModeChanged = onThemeModeChanged,") == 3
assert "selected = themeMode == mode" in app_root
assert "onClick = { onThemeModeChanged(mode) }" in app_root

# The selected mode is loaded from and written to app-local Preferences DataStore.
assert "androidx.datastore:datastore-preferences:1.3.0" in gradle
assert 'preferencesDataStore(name = "tacresq_settings")' in preferences
assert 'stringPreferencesKey("theme_mode")' in preferences
assert "fun Context.observeThemeMode(): Flow<ThemeMode>" in preferences
assert "ThemeMode.entries.firstOrNull { it.name == saved }" in preferences
assert "?: ThemeMode.SYSTEM" in preferences
assert "settingsDataStore.edit" in preferences
assert "preferences[themeModeKey] = mode.name" in preferences

# MainActivity applies the stored value before rendering and persists profile changes.
assert "observeThemeMode()" in main_activity
assert "themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)" in main_activity
assert "TACResQTheme(themeMode = themeMode)" in main_activity
assert "lifecycleScope.launch { persistThemeMode(mode) }" in main_activity

print("Persistent theme preference and all-role Profile propagation contract passed.")
