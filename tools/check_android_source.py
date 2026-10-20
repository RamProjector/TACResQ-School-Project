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
