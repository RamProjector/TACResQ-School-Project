from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
required = (
    "var locationStatus by remember { mutableStateOf<String?>(null) }",
    "val defaultMapStatus = when {",
    "No incident pins yet; showing fresh field-unit locations.",
    "Active incidents exist, but none has valid coordinates. They cannot appear as map pins.",
    "No active incidents with valid coordinates are available for this role yet.",
    "locationStatus ?: defaultMapStatus",
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("GPS map empty-state contract failed: " + ", ".join(missing))
print(f"GPS map empty-state contract passed: {len(required)} checks.")
