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
