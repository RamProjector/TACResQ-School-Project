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
