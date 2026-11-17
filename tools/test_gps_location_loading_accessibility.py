"""Guard accessible announcement for the GPS location loading control."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
required = (
    "if (role != Role.REQUESTER && isLocationRequestInProgress)",
    "enabled = false,",
    'contentDescription = "Getting your location"',
    "androidx.compose.material3.CircularProgressIndicator(",
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS location loading accessibility contract failed: " + ", ".join(missing))
button_start = source.index("if (role != Role.REQUESTER && isLocationRequestInProgress)")
button_end = source.index("} else if (role != Role.REQUESTER && locationProvider.hasPrecisePermission())", button_start)
button_block = source[button_start:button_end]
if 'modifier = Modifier.size(48.dp).semantics {' not in button_block:
    raise SystemExit("GPS loading button must expose its own accessible label.")
print(f"GPS location loading accessibility contract passed: {len(required) + 1} checks.")
