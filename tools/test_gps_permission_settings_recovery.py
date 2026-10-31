#!/usr/bin/env python3
"""Source contract for recovering from denied Android location permission."""

from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()

checks = {
    "explicit app-details settings intent": 'Settings.ACTION_APPLICATION_DETAILS_SETTINGS' in source,
    "settings target is this package": 'Uri.fromParts("package", context.packageName, null)' in source,
    "settings action is visible after permission denial": 'showAppSettingsAction = true' in source,
    "settings action is rendered conditionally": 'if (showAppSettingsAction)' in source,
    "settings action has an accessible label": 'Text("Settings")' in source,
    "permission grant clears recovery action": 'showAppSettingsAction = false' in source,
    "permission denial preserves incident map": 'Incident pins remain available' in source,
    "settings launch failure has manual recovery instructions": 'Open Android Settings, choose Apps, then TACResQ' in source,
}

for label, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {label}")

if not all(checks.values()):
    raise SystemExit(1)
