from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SECURITY = (ROOT / "backend/web/src/components/AdminSecurityStatus.tsx").read_text(encoding="utf-8")
ADMIN = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
CSS = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")

# MFA management must use the defined method parser and require AAL2.
assert 'activeSection === "security" ? "Review your current administrator session' in ADMIN
assert 'activeSection === "analytics" ? "Review request volume' in ADMIN
assert ".admin-security-status__factor-list li > span:last-child strong" in CSS
assert "@media (max-width:560px)" in CSS

assert "function hasTotpMethod(" in SECURITY
assert 'aal.currentLevel === "aal2" && hasTotpMethod(aal)' in SECURITY
assert 'data.currentLevel === "aal2" && hasTotpMethod(data)' in SECURITY
assert "hasTotpAal2" not in SECURITY

# The manual authenticator secret is hidden by default and explicitly revealed.
assert 'type={showEnrollmentSecret ? "text" : "password"}' in SECURITY
assert 'aria-label={showEnrollmentSecret ? "Hide manual setup key" : "Show manual setup key"}' in SECURITY
assert "Treat it like a password and do not share it." in SECURITY
assert ".admin-security-status__secret-field" in CSS

# The chart has a tabular alternative for precise values and assistive technology.
assert 'className="admin-analytics-data"' in ADMIN
assert "Daily request counts for the selected reporting period" in ADMIN
assert 'scope="row"' in ADMIN
assert ".admin-analytics-data__table-wrap" in CSS

print("Admin MFA and analytics UI source contract passed.")
