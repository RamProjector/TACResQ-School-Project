from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text(encoding="utf-8")

# The visible three-step SOS indicator must expose its current step and range
# to accessibility services instead of being color-only decoration.
assert "import androidx.compose.ui.semantics.ProgressBarRangeInfo" in source
assert "import androidx.compose.ui.semantics.progressBarRangeInfo" in source
start = source.index('Text(\n                        "Step $requestStep of 3')
end = source.index("\n                    when (requestStep)", start)
progress = source[start:end]
assert "progressBarRangeInfo = ProgressBarRangeInfo(" in progress
assert "current = requestStep.toFloat()" in progress
assert "range = 1f..3f" in progress
assert "steps = 2" in progress

print("Requester SOS step progress accessibility contract passed.")
