from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
build = (root / "build.gradle.kts").read_text()

kgp = re.search(
    r'classpath\("org\.jetbrains\.kotlin:kotlin-gradle-plugin:([^"]+)"\)',
    build,
)
compose = re.search(
    r'id\("org\.jetbrains\.kotlin\.plugin\.compose"\) version "([^"]+)"',
    build,
)

assert kgp, "AGP built-in Kotlin override must be explicit when upgrading Compose Compiler"
assert compose, "Compose Compiler Gradle plugin version is missing"
assert kgp.group(1) == compose.group(1), (
    f"Kotlin Gradle plugin {kgp.group(1)} must match Compose Compiler plugin {compose.group(1)}"
)

print(f"Kotlin Gradle plugin and Compose Compiler are aligned at {compose.group(1)}.")
