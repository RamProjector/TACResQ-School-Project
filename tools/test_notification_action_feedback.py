from pathlib import Path

source = Path(
    "app/src/main/java/com/tacresq/app/ui/notifications/NotificationsScreen.kt"
).read_text(encoding="utf-8")

start = source.index("state.actionError?.let")
end = source.index("\n        state.error?.let", start)
action_error = source[start:end]

assert "Banner(" in action_error, "Transient notification action errors should use the shared feedback banner."
assert "Card(" not in action_error, "Transient action feedback should not be presented as persistent content."
assert "isError = true" in action_error, "Action failure must retain error styling."
assert "vm::dismissActionError" in action_error, "Action feedback must remain dismissible."

# A failed inbox load is persistent screen state and must retain its retry path.
load_error = source[end:source.index("\n        if (state.loading", end)]
assert "Card(" in load_error, "Persistent notification loading failure should remain a content state."
assert "vm::refresh" in load_error, "Persistent notification loading failure must retain retry."

print("Notification action feedback hierarchy contract passed.")
