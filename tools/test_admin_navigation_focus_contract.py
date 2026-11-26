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


# The responsive drawer classes used by the Admin component must have matching
# CSS, remain hidden when closed, and expose the backdrop/close controls on
# tablet and phone widths.
styles = Path("backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")
for marker in (
    ".admin-sidebar.admin-sidebar--mobile-open",
    "transform:translateX(-105%);",
    "visibility:hidden;",
    "visibility:visible;",
    ".admin-mobile-sidebar-toggle",
    ".admin-mobile-sidebar-close",
    ".admin-sidebar-backdrop",
    "@media (max-width:900px)",
    "@media (prefers-reduced-motion:reduce)",
):
    assert marker in styles, f"Missing responsive Admin navigation styling: {marker}"
