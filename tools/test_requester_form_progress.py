#!/usr/bin/env python3
"""Guard the request wizard's visual progress and accessible text status."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text(encoding="utf-8")

assert '"Step $requestStep of 3 · "' in source, "keep the textual step name for screen readers"
assert "repeat(3) { stepIndex ->" in source, "show one segment per request step"
assert "Modifier.weight(1f).height(4.dp)" in source, "progress segments should share available width"
assert "stepIndex < requestStep" in source, "completed/current steps must be visibly distinguished"
assert "MaterialTheme.colorScheme.primary" in source
assert "MaterialTheme.colorScheme.surfaceVariant" in source
assert "requestStep = 2" in source and "requestStep = 3" in source, "indicator must follow the existing wizard state"
assert "fun sendSos(type: IncidentType, description: String, locationNotes: String)" in source, "visual progress must not replace the existing submission path"

print("Requester emergency-wizard progress contract passed.")
