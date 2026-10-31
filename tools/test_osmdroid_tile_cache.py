from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
build = (ROOT / "app/build.gradle.kts").read_text()

# The retired raster renderer must not remain in the Android dependency graph.
assert 'org.maplibre.gl:android-sdk-opengl:13.7.1' in build
assert "org.osmdroid:" not in build
assert "org.osmdroid" not in source

# OpenFreeMap Liberty is a public vector style and requires no embedded provider key.
assert 'OPENFREEMAP_LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"' in source
assert "MapLibre.getInstance(context)" in source
assert "map.setStyle(OPENFREEMAP_LIBERTY_STYLE)" in source
assert "apiKey" not in source and "accessToken" not in source

# The native map lifecycle is explicitly forwarded from the Compose destination.
for required in (
    "onCreate(null)",
    "mapView.onStart()",
    "mapView.onResume()",
    "mapView.onPause()",
    "mapView.onStop()",
    "mapView.onDestroy()",
):
    assert required in source, f"MapLibre lifecycle contract missing: {required}"

# Incident, responder, stale, and local-only device pins retain app-owned visuals.
for required in (
    "ic_map_incident_pin",
    "ic_map_responder_pin",
    "ic_map_my_location_pin",
    "alpha = 110",
    "map.addMarker(",
    "map.clear()",
    "setOnMarkerClickListener",
    "mapSheetState.bottomSheetState.expand()",
):
    assert required in source, f"MapLibre map behavior missing: {required}"

# Map provider failure must not hide operational incident details.
assert "addOnDidFailLoadingMapListener" in source
assert "Incident details remain available." in source
assert "No requester identity or incident narrative" not in source

print("MapLibre/OpenFreeMap provider, lifecycle, pin, and fallback contract passed.")
