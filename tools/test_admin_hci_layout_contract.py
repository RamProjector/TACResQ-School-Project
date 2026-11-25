from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CSS = (ROOT / "backend/web/src/pages/AdminResolution.css").read_text(encoding="utf-8")
ADMIN_CSS = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")
SOURCE = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
GUIDE = (ROOT / "docs/admin-portal-operator-guide.md").read_text(encoding="utf-8")

# Desktop sidebar has its own vertical scroll region; mobile keeps horizontal section navigation.
assert ".admin-shell > .admin-sidebar {" in CSS
sidebar = CSS.split(".admin-shell > .admin-sidebar {", 1)[1].split("}", 1)[0]
for required in ("height: 100vh", "height: 100dvh", "min-height: 0", "overflow-y: auto", "overscroll-behavior-y: contain", "-webkit-overflow-scrolling: touch"):
    assert required in sidebar, f"Desktop sidebar scrolling is missing {required}"
assert "@media (max-width: 900px)" in CSS
mobile_sidebar = CSS.split("@media (max-width: 900px)", 1)[1].split("/* Keep one primary panel surface", 1)[0]
assert "overflow: visible" in mobile_sidebar
assert ".admin-navigation" in ADMIN_CSS and "overflow-x: auto" in ADMIN_CSS
assert '<nav className="admin-navigation" aria-label="Admin sections">' in SOURCE

# Nested panels are visually flattened into subsections, not card-on-card surfaces.
assert ".admin-shell .admin-panel .admin-panel {" in CSS
nested_panel = CSS.split(".admin-shell .admin-panel .admin-panel {", 1)[1].split("}", 1)[0]
for required in ("border: 0", "border-top: 1px solid var(--line)", "background: transparent", "box-shadow: none"):
    assert required in nested_panel, f"Nested panel hierarchy is missing {required}"
for card in (".admin-health-item", ".admin-analytics-card", ".admin-settings-card", ".admin-help-card", ".admin-recovery-card", ".admin-attachment-card"):
    assert card in CSS, f"Flattened content group missing {card}"
assert "align-items: start" in CSS and "align-self: start" in CSS

# Form labels, hints, and controls use a vertical sequence and constrained fluid sizing.
assert ".admin-shell .admin-form-label {" in CSS
label = CSS.split(".admin-shell .admin-form-label {", 1)[1].split("}", 1)[0]
assert "display: block" in label
assert ".admin-shell .admin-form-hint {" in CSS
assert 'max-width: var(--admin-form-measure)' in CSS
assert 'width: 100%' in CSS
assert "fieldset" in GUIDE.lower() and "legend" in GUIDE.lower()
assert "visible label" in GUIDE.lower()
assert "independently scrollable" in GUIDE.lower()

# Keep the existing accessible navigation, focus, and responsive layout contracts intact.
assert 'className="admin-skip-link" href="#admin-workspace-main"' in SOURCE
assert ".admin-nav-item:focus-visible" in ADMIN_CSS
print("Admin HCI layout and form contract passed.")

# Visual polish remains restrained and respects motion preferences.
assert ".admin-shell .admin-panel::before" in CSS
assert "linear-gradient(90deg, transparent" in CSS
assert "0 10px 26px rgba(20, 42, 49, .045)" in CSS
assert "@media (prefers-reduced-motion: no-preference)" in CSS
assert "@media (prefers-reduced-motion: reduce)" in CSS
reduced = CSS.rsplit("@media (prefers-reduced-motion: reduce)", 1)[1]
assert "transform: none" in reduced
assert "animation: admin-dialog-enter" in CSS
assert ".audit-page__date-presets button" in CSS


# Intentional scroll regions have visible, themed scrollbar affordances across engines.
for required in (
    "scrollbar-width: thin",
    "scrollbar-color: var(--admin-scrollbar-thumb) var(--admin-scrollbar-track)",
    "scrollbar-gutter: stable",
    "::-webkit-scrollbar",
    "::-webkit-scrollbar-track",
    "::-webkit-scrollbar-thumb",
    "::-webkit-scrollbar-thumb:hover",
    ".admin-sidebar",
    ".admin-navigation",
    ".admin-table-wrap",
    ".admin-analytics-bars",
    ".admin-modal",
):
    assert required in CSS, f"Admin scrollbar affordance is missing {required}"
assert "scrollbar-color: auto" in CSS  # preserve forced-colors user-agent behavior

# Audit subcontent is presented as a restrained hierarchy rather than nested cards.
for selector in (
    ".admin-shell .audit-page__coverage",
    ".admin-shell .audit-page__coverage-details",
    ".admin-shell .audit-page__lifecycle",
    ".admin-shell .audit-page__filters",
    ".admin-shell .audit-event-card",
    ".admin-shell .audit-event-card__details",
):
    assert selector in CSS, f"Minimal audit surface rule missing {selector}"
assert ".admin-shell .audit-event-card {\n  padding-inline: 0;\n  border-bottom: 1px solid #e5eae2;" in CSS
