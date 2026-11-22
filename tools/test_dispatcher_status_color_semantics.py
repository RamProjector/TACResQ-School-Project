#!/usr/bin/env python3
"""Keep incident status colors distinct, theme-aware, and WCAG AA readable."""
from pathlib import Path

dispatcher = Path(
    "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt"
).read_text(encoding="utf-8")
components = Path(
    "app/src/main/java/com/tacresq/app/ui/common/Components.kt"
).read_text(encoding="utf-8")

# The dispatcher continues to render the single shared incident milestone.
assert "StatusPill(incident.status)" in dispatcher
assert "StatusPill(memberStatus.status)" not in dispatcher
assert "StatusPill(incident.status.label, MaterialTheme.colorScheme.secondary)" not in dispatcher
assert "memberStatus" not in dispatcher

palette_start = components.index("private fun IncidentStatus.statusPalette(isDark: Boolean)")
palette_end = components.index("\n@Composable", palette_start)
palette = components[palette_start:palette_end]

expected = {
    "PENDING": ("FFF2DE", "6A3B00", "5B3A13", "FFE2B7"),
    "ASSIGNED": ("E3EEF8", "214F73", "173D57", "C2E3FA"),
    "EN_ROUTE": ("DDF2EF", "145D58", "174B47", "B7E8E0"),
    "ON_SCENE": ("EFE7F7", "5C3A7B", "422C59", "E2CFF4"),
    "TRANSPORTING": ("E3F1E9", "215B45", "1D4B3A", "C1E8D1"),
    "RESOLVED": ("E7F2E7", "2C5F35", "244B2A", "C9E7C9"),
    "CANCELLED": ("E9ECEE", "4F5B63", "37434B", "D0D9DE"),
}

def luminance(value: str) -> float:
    channels = [int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    linear = [channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4 for channel in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]

def contrast(first: str, second: str) -> float:
    lighter, darker = sorted((luminance(first), luminance(second)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)

for status, colors in expected.items():
    block_start = palette.index(f"IncidentStatus.{status} ->")
    next_status = palette.find("\n    IncidentStatus.", block_start + 1)
    block = palette[block_start:next_status if next_status >= 0 else len(palette)]
    for color in colors:
        assert color in block, f"{status} is missing expected light/dark color {color}"
    light_container, light_content, dark_container, dark_content = colors
    assert contrast(light_container, light_content) >= 4.5, f"{status} light pair fails WCAG AA"
    assert contrast(dark_container, dark_content) >= 4.5, f"{status} dark pair fails WCAG AA"

assert "background.luminance() < 0.5f" in components
assert "StatusPill(text: String, status: IncidentStatus)" in components
assert "StatusPill(text: String, color: Color)" in components
print("Incident status palette contrast and theme selection passed.")
