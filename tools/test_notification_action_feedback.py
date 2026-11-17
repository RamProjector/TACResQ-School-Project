from pathlib import Path

source = Path(
    "app/src/main/java/com/tacresq/app/ui/notifications/NotificationsScreen.kt"
).read_text(encoding="utf-8")

start = source.index("state.actionError?.let")
end = source.index("state.error?.let", start)
action_error = source[start:end]

assert "Banner(" in action_error, "Transient notification action errors should use the shared feedback banner."
assert "Card(" not in action_error, "Transient action feedback should not be presented as persistent content."
assert "isError = true" in action_error, "Action failure must retain error styling."
assert "vm::dismissActionError" in action_error, "Action feedback must remain dismissible."

# A failed inbox load is persistent screen state. Its retry action lives in the
# shared inbox controls, so the error card does not need a duplicate retry button.
load_error = source[end:source.index("if (state.loading", end)]
assert "Card(" in load_error, "Persistent notification loading failure should remain a content state."
assert source.count("OutlinedButton(onClick = vm::refresh") == 1, (
    "The inbox must retain exactly one shared refresh action for persistent load failures."
)

print("Notification action feedback hierarchy contract passed.")
