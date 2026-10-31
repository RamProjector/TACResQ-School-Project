from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()

# A server-accepted SOS with an unverified location must retain the explicit
# do-not-resend warning, while the screen presents only one primary banner.
assert 'error = if (locationConfirmed) null else "Do not resend this SOS.' in source
assert 'notice = status,' in source
assert 'message?.startsWith("SOS request was accepted") == true' in source

start = source.index("// Show one primary operation message.")
end = source.index('item { SectionHeading("My requests") }', start)
feedback = source[start:end]
assert "val feedbackError = state.error" in feedback
assert "if (feedbackError != null)" in feedback
assert "Banner(feedbackError, isError = true" in feedback
assert "state.notice?.let" in feedback
assert "Banner(message, isError = false" in feedback
assert "isSuccess = true" in feedback
assert feedback.index("if (feedbackError != null)") < feedback.index("state.notice?.let")
assert feedback.count("Banner(") == 2  # Two mutually exclusive branches, never two visible banners.

print("Requester primary feedback hierarchy contract passed.")
