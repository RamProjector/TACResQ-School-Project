from pathlib import Path

source = Path("backend/web/src/pages/AdminConsole.tsx").read_text()

assert "const adminHeadingRef = useRef<HTMLHeadingElement>(null);" in source
assert "const previousAdminLocation = useRef({ section: activeSection, settingsPage, continuityPage });" in source
assert '(activeSection === "recovery" && previous.continuityPage !== continuityPage)' in source
assert "previousAdminLocation.current = { section: activeSection, settingsPage, continuityPage };" in source
assert "}, [activeSection, settingsPage, continuityPage]);" in source
assert "previous.section !== activeSection" in source
assert '(activeSection === "settings" && previous.settingsPage !== settingsPage)' in source
assert "if (locationChanged) adminHeadingRef.current?.focus();" in source
assert '<h1 ref={adminHeadingRef} tabIndex={-1}>' in source

assert 'if (url.searchParams.get("adminPage") !== route) url.searchParams.set("adminPage", route);' in source, "Top-level navigation must normalize the route even when the selected section is unchanged"
assert 'if (section !== "settings") {\n      url.searchParams.delete("adminSettings");\n    } else if (currentSettingsPage === "security") {' in source, "Top-level navigation must clear stale settings state independently of route changes"
assert 'if (url.toString() !== window.location.href) {' in source, "Top-level navigation must avoid redundant history entries"

print("Admin workspace navigation focus contract: passed")
