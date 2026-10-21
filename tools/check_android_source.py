#!/usr/bin/env python3
"""Validate TACResQ Android sources and, when present, its Gradle build setup."""
from __future__ import annotations

import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_SOURCES = (
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/tacresq/app/MainActivity.kt",
    "app/src/main/java/com/tacresq/app/ui/AppRoot.kt",
    "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt",
    "app/src/debug/AndroidManifest.xml",
)

missing_sources = [path for path in REQUIRED_SOURCES if not (ROOT / path).is_file()]
if missing_sources:
    raise SystemExit(
        "Android source check failed; missing required files:\n"
        + "\n".join(missing_sources)
    )

kotlin_files = list((ROOT / "app/src/main").rglob("*.kt"))
if not kotlin_files:
    raise SystemExit("Android source check failed; no Kotlin source files found")

bad_packages = []
for path in kotlin_files:
    source = path.read_text(encoding="utf-8")
    if "package com.tacresq.app" not in source:
        bad_packages.append(str(path.relative_to(ROOT)))
if bad_packages:
    raise SystemExit("Unexpected Kotlin package declaration in:\n" + "\n".join(bad_packages))

map_contract = {
    "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt": (
        "private fun Incident.hasValidMapCoordinates(): Boolean",
        "lat.isFinite() && lng.isFinite()",
        "lat in -90.0..90.0 && lng in -180.0..180.0",
        "visibleIncidents.any { it.hasValidMapCoordinates() }",
        "incident.hasValidMapCoordinates() && latitude != null && longitude != null",
        "BoundingBox.fromGeoPoints(validIncidentPoints)",
        "mapView.zoomToBoundingBox(",
        "Text(\"Show incident pins\")",
        "There are no valid incident coordinates to center on yet.",
    ),
}
for relative_path, required_text in map_contract.items():
    source = (ROOT / relative_path).read_text(encoding="utf-8")
    missing = [snippet for snippet in required_text if snippet not in source]
    if missing:
        raise SystemExit(f"GPS map coordinate contract failed for {relative_path}: missing " + ", ".join(missing))
print("GPS map coordinate contract OK: marker rendering and pin status share geographic validation.")

demo_mode_contract = {
    "app/build.gradle.kts": (
        'buildConfig = true',
        'buildConfigField("boolean", "USE_FAKE_API", (!useSupabaseInDebug).toString())',
        'buildConfigField("boolean", "USE_FAKE_API", "false")',
        'providers.gradleProperty("useSupabase").orNull == "true"',
    ),
    "app/src/main/java/com/tacresq/app/AppConfig.kt": (
        "val USE_FAKE_API = BuildConfig.USE_FAKE_API",
        "val USE_SUPABASE_API = !USE_FAKE_API",
    ),
    "app/src/main/java/com/tacresq/app/ui/auth/AuthScreen.kt": (
        "BuildConfig.USE_FAKE_API && !registering",
        'email = "requester@demo.com"; password = "demo123"',
        'email = "responder@demo.com"; password = "demo123"',
        'email = "driver@demo.com"; password = "demo123"',
        'email = "dispatcher@demo.com"; password = "demo123"',
    ),
    "app/src/main/java/com/tacresq/app/AppContainer.kt": (
        "persistSessions = !AppConfig.USE_FAKE_API",
    ),
    "app/src/main/java/com/tacresq/app/data/local/SessionStore.kt": (
        "private val persistSessions: Boolean = true",
        "if (!persistSessions) {",
        "inMemorySession.value = session",
    ),
}
for relative_path, required_text in demo_mode_contract.items():
    source = (ROOT / relative_path).read_text(encoding="utf-8")
    missing = [snippet for snippet in required_text if snippet not in source]
    if missing:
        raise SystemExit(
            f"Android demo-mode contract failed for {relative_path}: missing "
            + ", ".join(missing)
        )
print("Android demo-mode contract OK: debug shortcuts use the fake API; release selects Supabase.")

gps_map_location_contract = {
    "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt": (
        "ActivityResultContracts.RequestPermission()",
        "Manifest.permission.ACCESS_COARSE_LOCATION",
        "locationProvider.currentFix()",
        "mapView.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))",
        "Your location · this device only",
        "Not uploaded or continuously tracked",
        "LocationCaptureResult.ServicesDisabled",
        "LocationCaptureResult.TimedOut",
        "LocationCaptureResult.InvalidFix",
        "deviceFix == null",
        "Refresh location",
    ),
    "app/src/main/java/com/tacresq/app/ui/AppRoot.kt": (
        "locationProvider = locationProvider",
        "container.locationProvider",
    ),
}
for relative_path, required_text in gps_map_location_contract.items():
    source = (ROOT / relative_path).read_text(encoding="utf-8")
    missing = [snippet for snippet in required_text if snippet not in source]
    if missing:
        raise SystemExit(
            f"GPS map local-location contract failed for {relative_path}: missing "
            + ", ".join(missing)
        )
app_root_source = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text(encoding="utf-8")
if app_root_source.count("RoleApp(user.role, vmState.incidents, user, container.locationProvider, logout)") != 3:
    raise SystemExit("GPS map local-location contract failed: all three role maps must receive the location provider.")

map_source = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text(encoding="utf-8")
if "recordResponderLocation" in map_source or "createIncident(" in map_source:
    raise SystemExit("GPS map local-location contract failed: map preview must not upload location or create an incident")
print("GPS map local-location contract OK: one-time, permission-gated, device-only preview.")

sync_contract = {
    "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt": (
        "SyncStatus(state.lastSyncedAtEpochMs, state.syncError)",
        "lastSyncedAtEpochMs = System.currentTimeMillis()",
        "syncError = e.message",
    ),
    "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt": (
        "SyncStatus(state.lastSyncedAtEpochMs, state.syncError)",
        "lastSyncedAtEpochMs = if (refreshError == null) System.currentTimeMillis()",
        "syncError = refreshError?.message",
    ),
    "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt": (
        "SyncStatus(state.lastSyncedAtEpochMs, state.syncError)",
        "lastSyncedAtEpochMs = if (refreshError == null) System.currentTimeMillis()",
        "syncError = refreshError?.message",
    ),
}
for relative_path, required_text in sync_contract.items():
    source = (ROOT / relative_path).read_text(encoding="utf-8")
    missing = [snippet for snippet in required_text if snippet not in source]
    if missing:
        raise SystemExit(f"Sync health contract failed for {relative_path}: missing " + ", ".join(missing))
print("Incident sync-health contract OK: requester, responder, and dispatcher status is observable.")

if (ROOT / "gradlew").is_file():
    required_build_files = (
        "gradlew",
        "gradlew.bat",
        "settings.gradle.kts",
        "build.gradle.kts",
        "gradle.properties",
        "app/build.gradle.kts",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
        "gradle/wrapper/gradle-wrapper.jar.sha256",
    )
    missing_build_files = [
        path for path in required_build_files if not (ROOT / path).is_file()
    ]
    if missing_build_files:
        raise SystemExit(
            "Android Gradle setup is incomplete; missing:\n"
            + "\n".join(missing_build_files)
        )

    checksum_path = ROOT / "gradle/wrapper/gradle-wrapper.jar.sha256"
    expected_checksum = checksum_path.read_text(encoding="utf-8").split()[0]
    actual_checksum = hashlib.sha256(
        (ROOT / "gradle/wrapper/gradle-wrapper.jar").read_bytes()
    ).hexdigest()
    if actual_checksum != expected_checksum:
        raise SystemExit(
            "Gradle wrapper JAR checksum mismatch: "
            f"expected {expected_checksum}, got {actual_checksum}"
        )

    properties = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(
        encoding="utf-8"
    )
    if "gradle-9.3.1-bin.zip" not in properties:
        raise SystemExit("Unexpected Gradle distribution; review wrapper version and checksum.")
    print(
        f"Android Gradle project OK: {len(kotlin_files)} Kotlin files; "
        "wrapper files present and JAR checksum matches."
    )
else:
    print(
        f"Android source pack OK: {len(kotlin_files)} Kotlin files and required manifests present. "
        "No Gradle wrapper found, so compilation was not checked."
    )
