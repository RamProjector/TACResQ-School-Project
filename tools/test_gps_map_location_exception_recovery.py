from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text(encoding="utf-8")

locate_start = source.index("fun locateOnce()")
locate_end = source.index("androidx.compose.runtime.LaunchedEffect(retryLocationAfterSettings)", locate_start)
locate_once = source[locate_start:locate_end]

assert "try {" in locate_once, "Location request must guard provider/settings calls."
assert "catch (cancelled: kotlinx.coroutines.CancellationException)" in locate_once, (
    "Location request must preserve coroutine cancellation."
)
assert "throw cancelled" in locate_once, "Cancellation must be rethrown."
assert "catch (failure: Exception)" in locate_once, (
    "Unexpected location-provider exceptions must be converted to recoverable UI state."
)
assert "Incident pins remain available; try again." in locate_once, (
    "Location failure must explain that the map remains usable and how to recover."
)
assert "finally {" in locate_once and "isLocationRequestInProgress = false" in locate_once, (
    "The locate action must always clear its in-progress guard."
)

print("GPS location exception recovery contract passed.")
