#!/usr/bin/env python3
"""Regression contract for external turn-by-turn map intent recovery."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text(encoding="utf-8")
start = source.index("private fun openExternalMaps(")
body = source[start:]

assert 'google.navigation:q=$latitude,$longitude&mode=d' in body
assert 'geo:$latitude,$longitude?q=$latitude,$longitude' in body
assert body.count("catch (_: ActivityNotFoundException)") >= 2
assert body.count("catch (_: SecurityException)") >= 2
assert "if (opened) return" in body
assert "if (!fallbackOpened)" in body
assert "Unable to open a maps app on this phone." in body
assert body.index("context.startActivity(navigation)") < body.index("context.startActivity(fallback)")

print("External map navigation recovery contract passed.")
