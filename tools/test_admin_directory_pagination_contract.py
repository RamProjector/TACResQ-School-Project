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


def test_settings_subpages_are_deep_linkable_and_separate_policy_boundaries():
    source = SOURCE.read_text(encoding="utf-8")
    assert 'type AdminSettingsPage = "general" | "accessibility" | "notifications" | "security" | "privacy";' in source
    assert "function resolveSettingsPage(search: string)" in source
    assert 'url.searchParams.set("adminSettings", page)' in source
    assert "setSettingsPage(resolveSettingsPage(window.location.search))" in source
    assert 'aria-label="Settings pages"' in source
    for page in ("general", "accessibility", "notifications", "security", "privacy"):
        assert f'settingsPage === "{page}"' in source
    assert "no verified settings service" in source
    assert "No deletion, export-of-personal-data, or retention controls are provided here." in source


def test_core_admin_workspaces_expose_contextual_help_for_metric_and_permission_boundaries():
    source = SOURCE.read_text(encoding="utf-8")
    assert "function AdminHelpTip({ label, message }" in source
    for label in (
        "About dependency reachability",
        "About user management",
        "About request performance",
        "About security coverage",
    ):
        assert label in source
    assert 'className="admin-context-help"' in source
    css = CSS.read_text(encoding="utf-8")
    assert ".admin-panel__title-with-help" in css
    assert ".admin-context-help summary:focus-visible" in css


def test_directory_account_details_are_expandable_read_only_and_source_bounded():
    source = SOURCE.read_text(encoding="utf-8")
    assert "const [expandedAccountId, setExpandedAccountId] = useState<number | null>(null);" in source
    assert 'aria-expanded={expandedAccountId === user.id}' in source
    assert 'aria-controls={`admin-account-details-${user.id}`}' in source
    assert 'aria-label={`Details for ${user.name}`}' in source
    assert "Profile identifier" in source
    assert "Operational duty status" in source
    assert "These are the account fields currently returned by the protected Admin directory." in source
    assert "does not include authentication history or a complete account activity timeline" in source
    assert "user.duty_status ? user.duty_status.replaceAll(\"_\", \" \") : \"Not applicable\"" in source
    assert "admin-account-details-panel" in CSS.read_text(encoding="utf-8")
    assert "admin-account-details:focus-visible" in CSS.read_text(encoding="utf-8")
    assert "getAdminInvestigationEventsPage(5, 0, \"\", \"all\", \"all\", null, null, user.id, null, null)" in source
    assert "getAdminInvestigationEventsPage(5, 0, \"\", \"all\", \"all\", null, null, null, user.id, null)" in source
    assert "Up to 10 recent events where this profile was the actor or target." in source
    assert "accountActivityRequestVersion.current" in source
    assert "admin-account-activity__results" in CSS.read_text(encoding="utf-8")


def test_directory_refresh_invalidates_old_filter_timestamp_and_expanded_row():
    source = SOURCE.read_text(encoding="utf-8")
    refresh_start = source.split("const refresh = useCallback", 1)[1].split("if (activeSection !== \"overview\" && activeSection !== \"users\")", 1)[0]
    assert "setDirectoryLoadedAt(null);" in refresh_start
    assert "setUsers([]);" in refresh_start
    assert "setTotalUsers(0);" in refresh_start
    assert refresh_start.index("setUsers([]);") < refresh_start.index("getAdminUsersPage(pageSize")
    assert "setExpandedAccountId(null);" in refresh_start
    assert refresh_start.index("setDirectoryLoadedAt(null);") < refresh_start.index("getAdminUsersPage(pageSize")
    assert "Last successful retrieval:" in source



def test_directory_rows_are_scoped_to_the_active_server_query_before_effects_run():
    source = SOURCE.read_text(encoding="utf-8")
    assert 'const [directoryResultsKey, setDirectoryResultsKey] = useState<string | null>(null);' in source
    assert 'const directoryRequestKey = JSON.stringify([page, pageSize, debouncedQuery, roleFilter]);' in source
    assert 'directoryResultsKey !== directoryRequestKey' in source
    assert 'const filteredUsers = directoryFilterPending ? [] : users;' in source
    refresh = source.split("const refresh = useCallback", 1)[1].split("}, [activeSection, page, pageSize, debouncedQuery, roleFilter]);", 1)[0]
    assert "setDirectoryResultsKey(null);" in refresh
    assert 'setDirectoryResultsKey(JSON.stringify([page, pageSize, debouncedQuery, roleFilter]));' in refresh
    assert refresh.index("setDirectoryResultsKey(null);") < refresh.index("getAdminUsersPage(pageSize")
    assert refresh.index("setDirectoryLoadedAt(new Date().toISOString());") < refresh.index("setDirectoryResultsKey(JSON.stringify")
    assert 'directoryFilterPending ? "Updating account results…"' in source



def test_directory_error_state_offers_an_inline_retry():
    source = SOURCE.read_text(encoding="utf-8")
    assert 'directoryError && <button className="admin-filter-reset"' in source
    assert 'onClick={() => { void refresh(); }} disabled={refreshing}>Retry account directory</button>' in source
    assert 'role="region" aria-label="Account results; horizontally scrollable" tabIndex={0} aria-busy={loading || refreshing || directoryFilterPending}' in source
    assert 'aria-describedby="admin-directory-status"' in source
