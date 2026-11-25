#!/usr/bin/env python3
"""Static contract for consolidated Admin workspace navigation."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
page = (root / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
styles = (root / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")

# Backup/recovery and attachment safeguards are now two views of one workspace.
assert 'label: "Data & continuity"' in page
assert 'type AdminContinuityPage = "recovery" | "attachments"' in page
assert 'if (route === "attachments") return "recovery";' in page
assert 'activeSection === "recovery" && continuityPage === "recovery"' in page
assert 'activeSection === "recovery" && continuityPage === "attachments"' in page
assert 'aria-label="Data and continuity views"' in page
assert 'aria-current={continuityPage === "attachments" ? "page" : undefined}' in page
assert 'function navigateToContinuityPage(page: AdminContinuityPage)' in page
assert 'url.searchParams.set("adminPage", page)' in page
assert 'onClick={() => navigateToContinuityPage("attachments")}' in page
assert 'onClick={() => navigateToContinuityPage("recovery")}' in page
assert 'type AdminSection = "overview" | "users" | "security" | "analytics" | "audit" | "recovery" | "settings" | "help";' in page
assert 'id: "attachments"' not in page

# Authenticator/session operations have one home: the Security workspace.
assert 'type AdminSettingsPage = "general" | "accessibility" | "notifications" | "privacy";' in page
assert '["security", "Security & sessions"]' not in page
assert 'settingsPage === "security"' not in page
assert 'activeSection === "security"' in page

# The combined navigation has consistent keyboard focus and responsive overflow.
assert ".admin-continuity-nav" in styles
assert ".admin-continuity-nav__item.is-active" in styles
assert ".admin-continuity-nav__item:focus-visible" in styles

print("PASS: Admin features are consolidated without removing legacy attachment deep links.")
