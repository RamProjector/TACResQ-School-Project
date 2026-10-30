from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text(encoding="utf-8")

assert "private fun GeoFix.mapPreviewZoom(): Double" in source
assert "accuracyM == null ->" in source
assert "accuracyM <= 50f -> 16.0" in source
assert "accuracyM <= 150f -> 15.0" in source
assert "accuracyM <= 500f -> 14.0" in source
assert "else -> 12.0" in source
assert "private fun GeoFix.accuracyStatus(): String" in source
assert "Accuracy unavailable — confirm the map position before relying on it." in source
assert "Low location accuracy" in source
assert "fix.mapPreviewZoom()" in source
assert "fix.accuracyStatus()" in source
assert "street-level precision" in source

print("GPS accuracy-aware preview contract passed.")
