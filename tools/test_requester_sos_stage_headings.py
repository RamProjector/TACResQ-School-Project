from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text(encoding="utf-8")

# The three stages of the SOS flow are navigation landmarks for screen-reader users.
assert "import androidx.compose.ui.semantics.heading" in source
for title in ("Emergency type", "Confirm your location", "Review before sending"):
    expected = f'Text("{title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics {{ heading() }})'
    assert expected in source, f"Missing accessible heading semantics for: {title}"

print("Requester SOS stage heading accessibility contract passed.")
