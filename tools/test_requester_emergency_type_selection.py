from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text(encoding="utf-8")

# Treat each emergency type as one radio choice. The entire card is the
# selectable target; the nested RadioButton must not expose a second action.
assert "import androidx.compose.foundation.selection.selectable" in source
assert "import androidx.compose.foundation.selection.selectableGroup" in source
assert "import androidx.compose.ui.semantics.Role" in source

start = source.index('Text("Emergency type"')
end = source.index('OutlinedTextField(value = description', start)
options = source[start:end]

assert "Modifier.fillMaxWidth().selectableGroup()" in options
assert "selected = type == selectedType" in options
assert "role = Role.RadioButton" in options
assert "onClick = { selectedType = type }" in options
assert "RadioButton(\n                                                selected = type == selectedType,\n                                                onClick = null," in options
assert "onClick = { selectedType = type },\n                                    )" not in options

print("Requester emergency-type single-selection accessibility contract passed.")
