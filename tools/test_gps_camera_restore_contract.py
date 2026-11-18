"""Guard safe restoration of persisted GPS map camera state."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
required = (
    "it.isFinite() && it in 0.0..22.0",
    "val restoredZoom = savedCameraZoom",
    "((it % 360.0) + 360.0) % 360.0",
    "val restoredBearing = savedCameraBearing",
    "?.coerceIn(0.0, 60.0) ?: 0.0",
    "val restoredTilt = savedCameraTilt",
    "savedCameraLatitude != null && savedCameraLongitude != null",
    "savedCameraLatitude!!.isFinite() && savedCameraLongitude!!.isFinite()",
    "savedCameraLatitude!! in -90.0..90.0 && savedCameraLongitude!! in -180.0..180.0",
    "LatLng(TACLOBAN_LATITUDE, TACLOBAN_LONGITUDE)",
    "val restoredTarget = if (",
    ".target(restoredTarget)",
    ".zoom(restoredZoom)",
    ".bearing(restoredBearing)",
    ".tilt(restoredTilt)",
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS camera restoration contract failed: " + ", ".join(missing))
print(f"GPS camera restoration contract passed: {len(required)} checks.")
