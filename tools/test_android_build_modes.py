#!/usr/bin/env python3
"""Contract checks that CI publishes unmistakably separate demo and real-backend APKs."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / "app/build.gradle.kts").read_text()
workflow = (ROOT / ".github/workflows/ci.yml").read_text()
readme = (ROOT / "README.md").read_text()

checks = {
    "debug demo mode is the default unless Supabase is requested":
        'providers.gradleProperty("useSupabase").orNull == "true"' in gradle
        and 'buildConfigField("boolean", "USE_FAKE_API", (!useSupabaseInDebug).toString())' in gradle,
    "release always disables the fake backend":
        'buildConfigField("boolean", "USE_FAKE_API", "false")' in gradle,
    "CI builds demo debug APK":
        "Build demo-mode debug APK" in workflow and "./gradlew --no-daemon assembleDebug" in workflow,
    "CI separately builds Supabase debug APK":
        "Build Supabase-mode debug APK" in workflow and "./gradlew --no-daemon assembleDebug -PuseSupabase=true" in workflow,
    "CI publishes distinct artifact names":
        "tacresq-demo-debug.apk" in workflow and "tacresq-supabase-debug.apk" in workflow,
    "release artifact remains clearly labeled":
        "tacresq-release.apk" in workflow,
    "README explains how to build real-backend debug mode":
        "./gradlew --no-daemon assembleDebug -PuseSupabase=true" in readme,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Android build-mode contract check(s) failed")
print(f"Android build-mode contract OK: {len(checks)} checks.")
