from pathlib import Path

source = Path("backend/web/src/pages/AdminConsole.tsx").read_text()
styles = Path("backend/web/src/pages/AdminConsole.css").read_text()

assert 'const [helpQuery, setHelpQuery] = useState("")' in source
assert 'id="admin-help-search"' in source
assert 'aria-live="polite"' in source[source.index('id="admin-help-search"'):]
assert "function matchesAdminHelp(query: string, terms: string[])" in source
assert "split(/\\s+/)" in source
assert source.count("hidden={!matchesAdminHelp(normalizedHelpQuery") == 4
assert "No guidance matched that search." in source
assert ".admin-help-card[hidden] { display:none; }" in styles
assert ".admin-help-search input:focus-visible" in styles

print("Admin Help Centre search contract: passed")
