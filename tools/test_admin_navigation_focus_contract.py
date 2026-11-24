from pathlib import Path

source = Path("backend/web/src/pages/AdminConsole.tsx").read_text()

assert "const adminHeadingRef = useRef<HTMLHeadingElement>(null);" in source
assert "const previousAdminLocation = useRef({ section: activeSection, settingsPage });" in source
assert "previous.section !== activeSection" in source
assert '(activeSection === "settings" && previous.settingsPage !== settingsPage)' in source
assert "if (locationChanged) adminHeadingRef.current?.focus();" in source
assert '<h1 ref={adminHeadingRef} tabIndex={-1}>' in source
assert "previousAdminLocation.current = { section: activeSection, settingsPage };" in source

print("Admin workspace navigation focus contract: passed")
