"""Guard readable, high-contrast GPS cluster count labels."""
from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
required = (
    "PropertyFactory.textSize(14f)",
    "PropertyFactory.textColor(Color.WHITE)",
    "PropertyFactory.textHaloColor(Color.BLACK)",
    "PropertyFactory.textHaloWidth(1.25f)",
    "PropertyFactory.textIgnorePlacement(true)",
    "PropertyFactory.textAllowOverlap(true)",
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS cluster count-label contract failed: " + ", ".join(missing))
print(f"GPS cluster count-label contract passed: {len(required)} checks.")
