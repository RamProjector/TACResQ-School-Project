from pathlib import Path

SOURCE = Path(
    "app/src/main/java/com/tacresq/app/ui/AppRoot.kt"
).read_text(encoding="utf-8")

start = SOURCE.index("internal fun RoleSectionScreen(")
end = SOURCE.find("\n@Composable", start + 1)
assert end != -1, "RoleSectionScreen boundary not found"
screen = SOURCE[start:end]

assert "val sectionScrollState = rememberScrollState()" in screen
assert "if (destination == AppDestination.CHAT)" in screen
assert ".then(sectionScrollModifier)" in screen
assert "Modifier.verticalScroll(sectionScrollState)" in screen

chat_start = screen.index("destination == AppDestination.CHAT -> {")
chat_end = screen.find("\n            destination ==", chat_start + 1)
assert chat_end != -1, "Messages destination boundary not found"
chat = screen[chat_start:chat_end]

assert "LazyColumn(" in chat, "Messages must use a lazy conversation list"
assert "modifier = Modifier.weight(1f).fillMaxWidth()" in chat, (
    "Messages list must receive bounded remaining height from its Column"
)
assert "Modifier.fillMaxSize()" not in chat, (
    "Messages LazyColumn must not request the full parent height below its header"
)
assert "verticalScroll(" not in chat, (
    "Messages must not introduce a nested vertical scroll container"
)

print("Requester Messages tab uses a single bounded lazy scroll container.")
