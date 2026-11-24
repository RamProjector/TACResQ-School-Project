from pathlib import Path

css = Path("backend/web/src/pages/AdminConsole.css").read_text()

required_selectors = [
    ".admin-shell--high-contrast .audit-page__coverage",
    ".admin-shell--high-contrast .audit-page__filters",
    ".admin-shell--high-contrast .audit-event-card",
    ".admin-shell--high-contrast .audit-event-card__details",
    ".admin-shell--high-contrast .audit-page__date-presets button[aria-pressed=\"true\"]",
    ".admin-shell--high-contrast .audit-page__pagination button:focus-visible",
    ".admin-shell--high-contrast .audit-page__lifecycle-pagination button:focus-visible",
    ".admin-shell--high-contrast .audit-event-card__references button:focus-visible",
]

missing = [selector for selector in required_selectors if selector not in css]
assert not missing, f"Missing audit high-contrast coverage: {missing}"
assert "outline:3px solid #8a321c" in css
assert "background:#e4f0e5" in css

print("Admin audit high-contrast contract: passed")
