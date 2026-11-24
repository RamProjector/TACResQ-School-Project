from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
CSS = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")

assert 'preferences.highContrast ? " admin-shell--high-contrast" : ""' in SOURCE
assert ".admin-shell--high-contrast .admin-settings-card p" in CSS
assert ".admin-shell--high-contrast .admin-table td" in CSS
assert ".admin-shell--high-contrast input" in CSS
assert ".admin-shell--high-contrast select" in CSS
assert ".admin-shell--high-contrast textarea" in CSS
assert ".admin-shell--high-contrast button:focus-visible" in CSS
assert ".admin-shell--high-contrast a:focus-visible" in CSS
assert "outline:3px solid #8a321c" in CSS
assert ".admin-shell--high-contrast .admin-settings-nav__item--active" in CSS

print("Admin high-contrast preference source contract passed.")

assert "@media (prefers-reduced-motion: reduce)" in CSS
assert ".admin-shell *, .admin-shell *::before, .admin-shell *::after" in CSS
assert ".admin-shell--high-contrast .admin-settings-toggle input[type=\"checkbox\"]" in CSS
assert ".admin-shell--high-contrast .admin-settings-toggle input[type=\"checkbox\"]:focus-visible" in CSS
assert "Improve text, table, form-control, navigation, and keyboard-focus contrast." in SOURCE


# The default account-directory page size is a browser-local preference. Invalid
# or older stored values must fall back safely, and discarding/resetting edits
# must restore the selected default rather than leaving an unsaved preview.
for marker in (
    "type AdminPageSize = 10 | 25 | 50 | 100;",
    "directoryPageSize: 25,",
    "parsed.directoryPageSize === 10",
    "parsed.directoryPageSize === 25",
    "parsed.directoryPageSize === 50",
    "parsed.directoryPageSize === 100",
    "Default account rows per page",
    "setPageSize(next)",
    "savedPreferences.directoryPageSize",
    "setPageSize(DEFAULT_ADMIN_PREFERENCES.directoryPageSize)",
    "readAdminPreferences().directoryPageSize",
):
    assert marker in SOURCE, f"Missing account page-size preference contract: {marker}"
