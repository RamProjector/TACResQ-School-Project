from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()

# Keep the retry action identifiable while the original saved SOS is being retried.
start = source.index('Text("An earlier SOS needs confirmation",')
end = source.index("\n                }\n            }\n        }", start)
card = source[start:end]
assert "TACResQ saved the original request." in card
assert "do not create a replacement SOS" in card
assert "Button(onClick = vm::retryPendingSos, enabled = !state.sending" in card
assert 'Text("Retry saved SOS")' in card
assert 'Text("Retrying saved SOS…")' in card
assert "if (state.sending)" in card
assert card.index("CircularProgressIndicator") < card.index('Text("Retrying saved SOS…")')
assert 'else {\n                            Text("Retry saved SOS")' in card

print("Pending SOS retry action-label contract passed.")
