from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/location/LocationProvider.kt").read_text()
start = source.index("suspend fun currentFix(): LocationCaptureResult")
end = source.index("/**", start + 1)
current_fix = source[start:end]

assert "if (!hasPermission()) return LocationCaptureResult.PermissionDenied" in current_fix
assert "if (!isLocationEnabled()) return LocationCaptureResult.ServicesDisabled" not in current_fix, (
    "currentFix must not contradict LocationSettingsClient with the OEM-sensitive "
    "LocationManager pre-check"
)
assert "client.getCurrentLocation(request, cancellation.token)" in current_fix
assert "withTimeoutOrNull(FIX_TIMEOUT_MS)" in current_fix
print("Current-fix location settings regression contract passed.")
