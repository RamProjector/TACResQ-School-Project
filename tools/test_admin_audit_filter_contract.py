from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FILTERS = (ROOT / "backend/web/src/lib/adminAuditFilters.ts").read_text(encoding="utf-8")
AUDIT_PAGE = (ROOT / "backend/web/src/pages/AuditLogsPage.tsx").read_text(encoding="utf-8")
ADMIN_CSS = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")

# The validator must reject malformed calendar dates and impossible dates,
# while allowing either boundary to be omitted.
assert r"/^\d{4}-\d{2}-\d{2}$/" in FILTERS
assert "getUTCFullYear() === year" in FILTERS
assert "getUTCMonth() === month - 1" in FILTERS
assert "getUTCDate() === day" in FILTERS
assert "The start date must be on or before the end date." in FILTERS

# Invalid ranges must stop before the protected RPC is called.
assert "getAuditDateRangeError(from, to)" in AUDIT_PAGE
assert "if (dateRangeError)" in AUDIT_PAGE
assert "setError(dateRangeError)" in AUDIT_PAGE
assert "Boolean(dateRangeError)" in AUDIT_PAGE
assert "getAdminInvestigationEventsPage(" in AUDIT_PAGE

# Keyboard focus and reduced-motion preferences must be explicitly respected.
assert ":focus-visible" in ADMIN_CSS
assert "prefers-reduced-motion: reduce" in ADMIN_CSS

print("Admin audit filter and accessibility source contract passed.")


# Quick ranges use the fixed Manila calendar and remain accessible as a button group.
assert 'function manilaDateOffset(daysAgo: number)' in AUDIT_PAGE
assert 'timeZone: "Asia/Manila"' in AUDIT_PAGE
assert 'function isDatePresetSelected(days: number, from: string, to: string)' in AUDIT_PAGE
assert 'role="group" aria-label="Quick date range"' in AUDIT_PAGE
assert 'aria-pressed={isDatePresetSelected(days, from, to)}' in AUDIT_PAGE
assert 'setFrom(manilaDateOffset(days - 1)); setTo(manilaDateOffset(0));' in AUDIT_PAGE
assert ".audit-page__date-presets button[aria-pressed=\"true\"]" in ADMIN_CSS
assert ".audit-page__date-presets button:focus-visible" in ADMIN_CSS
