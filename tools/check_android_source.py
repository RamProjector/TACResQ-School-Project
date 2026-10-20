#!/usr/bin/env python3
"""Validate the Android source pack when no Gradle project is checked in.

The repository currently documents that app/ must be merged into a fresh
Android project. This check prevents the source pack from silently losing
required files, while the CI workflow runs assembleDebug/assembleRelease
automatically as soon as a Gradle project is added.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "app/src/main/java/com/tacresq/app/MainActivity.kt",
    "app/src/main/java/com/tacresq/app/ui/AppRoot.kt",
    "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt",
    "app/src/debug/AndroidManifest.xml",
)

missing = [path for path in REQUIRED if not (ROOT / path).is_file()]
if missing:
    raise SystemExit("Android source-pack check failed; missing:\n" + "\n".join(missing))

kotlin_files = list((ROOT / "app/src/main").rglob("*.kt"))
if not kotlin_files:
    raise SystemExit("Android source-pack check failed; no Kotlin source files found")

bad_packages = []
for path in kotlin_files:
    text = path.read_text(encoding="utf-8")
    if "package com.tacresq.app" not in text:
        bad_packages.append(str(path.relative_to(ROOT)))
if bad_packages:
    raise SystemExit("Unexpected Kotlin package declaration in:\n" + "\n".join(bad_packages))

print(f"Android source pack OK: {len(kotlin_files)} Kotlin files and required debug manifest present.")
print("Gradle project not checked in; CI will run assembleDebug and assembleRelease when gradlew is added.")
