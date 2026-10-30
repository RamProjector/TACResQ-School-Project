from pathlib import Path

screen = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text(encoding="utf-8")
presentation = Path("app/src/main/java/com/tacresq/app/ui/GpsMapPresentation.kt").read_text(encoding="utf-8")
unit_tests = Path("app/src/test/java/com/tacresq/app/ui/GpsMapPresentationTest.kt").read_text(encoding="utf-8")

assert "gpsMapPreviewZoom(fix.accuracyM)" in screen
assert "gpsAccuracyStatus(fix.accuracyM)" in screen
assert "internal fun gpsMapPreviewZoom(accuracyM: Float?)" in presentation
assert "internal fun gpsAccuracyStatus(accuracyM: Float?)" in presentation
assert "accuracyM <= 0f" in presentation
assert "!accuracyM.isFinite()" in presentation
assert "accuracyM <= 50f -> 16.0" in presentation
assert "accuracyM <= 150f -> 15.0" in presentation
assert "accuracyM <= 500f -> 14.0" in presentation
assert "else -> 12.0" in presentation
assert "Accuracy unavailable — confirm the map position before relying on it." in presentation
assert "Low location accuracy" in presentation
assert "previewZoomUsesConservativeBandsAtAccuracyBoundaries" in unit_tests
assert "unavailableOrInvalidAccuracyNeverGetsStreetLevelZoom" in unit_tests
assert "statusRequiresConfirmationOnlyWhenAccuracyIsWiderThanOneHundredMetres" in unit_tests
assert "veryLargeFiniteAccuracyRemainsConservative" in unit_tests

print("GPS accuracy-aware preview source and unit-test contract passed.")
