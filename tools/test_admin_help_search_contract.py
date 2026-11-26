from pathlib import Path

source = Path("backend/web/src/pages/AdminConsole.tsx").read_text()
styles = Path("backend/web/src/pages/AdminConsole.css").read_text()

assert 'const [helpQuery, setHelpQuery] = useState("")' in source
assert 'id="admin-help-search"' in source
assert 'aria-live="polite"' in source[source.index('id="admin-help-search"'):]
assert "function matchesAdminHelp(query: string, terms: string[])" in source
assert "split(/\\s+/)" in source
assert source.count("hidden={!matchesAdminHelp(normalizedHelpQuery") >= 6, "Help Centre searchable-card coverage unexpectedly regressed"
assert "No guidance matched that search." in source
assert ".admin-help-card[hidden] { display:none; }" in styles
assert ".admin-help-search input:focus-visible" in styles
assert '["notifications", "push", "fcm", "delivery", "alert", "inbox"]' in source[source.index('normalizedHelpQuery && !['):], "Help Centre empty state must recognize notification search terms"
assert "setActiveSection(\"settings\");" in source[source.index("function navigateToSettingsPage"):source.index("function navigateToContinuityPage")], "Help Centre settings links must activate the Settings workspace"
assert 'url.searchParams.get("adminPage") !== "settings" || url.searchParams.get("adminSettings") !== page' in source[source.index("function navigateToSettingsPage"):source.index("function navigateToContinuityPage")], "Settings navigation must synchronize both route parameters"


settings_navigation = source[source.index("function navigateToSettingsPage"):source.index("function navigateToContinuityPage")]
assert 'setActiveSection("settings");' in settings_navigation, "Help Centre settings links must activate the Settings workspace"
assert 'url.searchParams.get("adminPage") !== "settings" || url.searchParams.get("adminSettings") !== page' in settings_navigation, "Settings navigation must synchronize both route parameters"
assert 'url.searchParams.delete("auditFrom");' in settings_navigation and 'url.searchParams.delete("auditTo");' in settings_navigation, "Leaving the Activity Log for Settings must clear its date-only shortcut filters"

continuity_navigation = source[source.index("function navigateToContinuityPage"):source.index("function navigateToSection")]
assert 'url.searchParams.delete("auditFrom");' in continuity_navigation and 'url.searchParams.delete("auditTo");' in continuity_navigation, "Leaving the Activity Log for continuity pages must clear its date-only shortcut filters"

section_navigation = source[source.index("function navigateToSection"):source.index("function navigateToTodayActivityLog")]
assert 'if (section !== "audit")' in section_navigation, "Non-audit workspace navigation must clear stale Activity Log date filters"
assert 'url.searchParams.delete("auditFrom");' in section_navigation and 'url.searchParams.delete("auditTo");' in section_navigation, "Non-audit workspace navigation must remove both Activity Log date filters"

print("Admin Help Centre search contract: passed")
