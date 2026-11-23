from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
home = (ROOT / "backend/web/src/pages/Home.tsx").read_text()
admin = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
styles = (ROOT / "backend/web/src/pages/PortalVisualFoundation.css").read_text()

# The sign-in surface has authored visual structure, not a placeholder hero.
assert "login-story__visual" in home
assert "Identity" in home and "Accountability" in home
assert "ROLE-GATED · SERVER-ENFORCED" in home
assert 'import "./PortalVisualFoundation.css";' in admin

# The admin workspace has clear navigation groups and a section identity marker.
assert "admin-sidebar__context" in admin
assert "admin-navigation__group-label" in admin
assert "admin-heading__icon" in admin
assert "ActiveSectionIcon" in admin

# Responsive navigation and narrow-screen table handling are deliberate.
for breakpoint in ("max-width: 1120px", "max-width: 900px", "max-width: 640px", "max-width: 390px"):
    assert breakpoint in styles
assert ".admin-navigation {\n    flex-direction: row;" in styles
assert ".admin-table { min-width: 690px; }" in styles
assert "min-width: max-content;" in styles
assert "prefers-reduced-motion: reduce" in styles

# This is a visual-only enhancement: do not introduce web dispatch operations.
for forbidden in ("assignIncident(", "getAllIncidents(", "getFieldUnits(", "DispatcherBoard"):
    assert forbidden not in home + admin
print("TACResQ website visual foundation contract passed.")

# Account creation timestamps must use the same explicit Manila timezone as portal activity timestamps.
assert 'new Intl.DateTimeFormat("en-PH", { timeZone: "Asia/Manila"' in admin
