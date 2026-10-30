from pathlib import Path

source = Path(
    "app/src/main/java/com/tacresq/app/ui/common/Components.kt"
).read_text(encoding="utf-8")

start = source.index("fun Banner(")
end = source.index("\n@Composable", start)
banner = source[start:end]

assert "Surface(" in banner, "Persistent feedback should use a banner surface, not a content card."
assert "Card(" not in banner, "Feedback banner must not inherit card containment styling."
assert "LiveRegionMode.Polite" in banner, "Feedback should be exposed as a polite live region."
assert "fillMaxWidth()" in banner, "Feedback banner should retain its full-width layout."
assert "onDismiss" in banner, "Dismissible feedback must retain its explicit dismiss action."

print("Persistent feedback banner semantics contract passed.")
