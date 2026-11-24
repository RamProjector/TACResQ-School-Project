from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "backend/web/src/pages/AdminConsole.tsx"
CSS = ROOT / "backend/web/src/pages/AdminConsole.css"


def test_directory_page_size_is_user_configurable():
    source = SOURCE.read_text(encoding="utf-8")
    assert "useState<10 | 25 | 50 | 100>(25)" in source
    assert source.index("const directoryFilterPending =") < source.index("const filteredUsers =")
    assert 'id="admin-directory-page-size"' in source
    assert 'aria-label="Accounts per page"' in source
    assert "setPage(0); setPageSize(Number(event.target.value)" in source
    assert "getAdminUsersPage(pageSize, page * pageSize" in source
    assert "}, [activeSection, page, pageSize, debouncedQuery, roleFilter]);" in source
    assert "up to {pageSize} profiles" in source
    assert "Current directory page:" in source
    assert "up to 25 profiles" not in source


def test_directory_page_size_control_has_visible_keyboard_focus():
    css = CSS.read_text(encoding="utf-8")
    assert ".admin-page-size" in css
    assert ".admin-page-size select:focus-visible" in css


def test_overview_shortcuts_use_the_shared_section_navigation():
    source = SOURCE.read_text(encoding="utf-8")
    assert "function navigateToSection(section: AdminSection)" in source
    assert 'onClick={() => navigateToSection("users")}' in source
    assert 'onClick={() => navigateToSection("analytics")}' in source
    assert 'onClick={() => navigateToSection("audit")}' in source
    assert 'aria-labelledby="admin-overview-shortcuts-title"' in source
    assert "admin-overview-shortcuts__grid" in source

def test_mobile_directory_keeps_account_identity_visible_during_horizontal_scroll():
    css = CSS.read_text(encoding="utf-8")
    assert "@media (max-width:760px)" in css
    assert ".admin-table th:first-child" in css
    assert ".admin-table td:first-child" in css
    assert "position:sticky;" in css
    assert "left:0;" in css
    assert "background:#fffefa;" in css


def test_admin_settings_and_help_workspaces_are_routed_and_persist_personal_preferences():
    source = SOURCE.read_text(encoding="utf-8")
    assert 'type AdminSection = "overview" | "users" | "security" | "analytics" | "audit" | "settings" | "help";' in source
    assert '{ id: "settings", label: "Settings", icon: Settings }' in source
    assert '{ id: "help", label: "Help & about", icon: BookOpen }' in source
    assert 'ADMIN_PREFERENCES_KEY = "tacresq.admin.preferences.v1"' in source
    assert "window.localStorage.getItem(ADMIN_PREFERENCES_KEY)" in source
    assert "window.localStorage.setItem(ADMIN_PREFERENCES_KEY" in source
    assert "Your Admin display preferences were saved on this device." in source
    assert "admin-settings-panel" in source
    assert "admin-help-panel" in source
    assert "no verified preference-enforcement workflow" in source
    assert "Not available as an online workflow" in source


def test_admin_settings_preferences_have_real_presentation_effects_and_responsive_styles():
    css = CSS.read_text(encoding="utf-8")
    assert ".admin-shell--compact .admin-table th" in css
    assert ".admin-shell--high-contrast" in css
    assert ".admin-shell--reduced-motion" in css
    assert ".admin-shell--hide-context-help" in css
    assert ".admin-context-help summary:focus-visible" in css
    assert ".admin-settings-grid" in css
    assert ".admin-help-grid" in css
    assert "@media (max-width:520px)" in css
