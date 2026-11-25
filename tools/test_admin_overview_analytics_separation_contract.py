from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ADMIN = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
CSS = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")
GUIDE = (ROOT / "docs/admin-portal-operator-guide.md").read_text(encoding="utf-8")

# Overview stays an operational snapshot, not a second historical analytics page.
assert '{ id: "analytics", label: "Analytics & reports", icon: BarChart3 }' in ADMIN
assert 'aria-label="Operational snapshot"' in ADMIN
assert "RECORDED EVENTS TODAY" in ADMIN
assert "MATCHING ACCOUNTS" in ADMIN
assert "REQUESTERS · CURRENT PAGE" not in ADMIN
assert "FIELD UNITS · CURRENT PAGE" not in ADMIN
assert 'id="admin-overview-activity-title"' in ADMIN
assert "Recent recorded activity" in ADMIN
assert 'onClick={() => navigateToSection("audit")}>Open Activity Log</button>' in ADMIN

# Today's count is the protected RPC total, not the five visible preview records.
assert 'function getManilaTodayRange(now = new Date())' in ADMIN
assert 'timeZone: "Asia/Manila"' in ADMIN
assert "getAdminInvestigationEventsPage(" in ADMIN
assert '5, 0, "", "all", "all", range.from, range.to, null, null, null' in ADMIN
assert "setTodayActivityCount(result.total_count)" in ADMIN
assert "setTodayActivityEvents(result.events)" in ADMIN
assert "todayActivityEvents.length" in ADMIN
assert "todayActivityCount === 0" in ADMIN
assert "Today's activity is an exact count from the protected investigation feed" in ADMIN
assert "not a complete record of every action or denied attempt" in ADMIN
assert "todayActivityError ? \"Unavailable\"" in ADMIN
assert "todayActivityLoading" in ADMIN
assert "todayActivityRequest.current" in ADMIN

# Analytics owns aggregate charts and performance over selectable periods.
assert "Request status distribution" in ADMIN
assert "CURRENT STATUS MIX" in ADMIN
assert "admin-analytics-status-chart__track" in ADMIN
assert "analytics.pending" in ADMIN and "analytics.active" in ADMIN
assert "analytics.resolved" in ADMIN and "analytics.cancelled" in ADMIN
assert "not a historical status-transition chart" in ADMIN
assert "Daily requests" in ADMIN
assert "Time to first assignment" in ADMIN
assert 'Last 7 calendar days' in ADMIN and 'Last 30 calendar days' in ADMIN and 'Last 90 calendar days' in ADMIN
assert 'aria-label="Export incident performance report as CSV"' in ADMIN

# Activity Log remains the individual-record investigation workspace.
assert '{activeSection === "audit" && <AuditLogsPage profile={profile} />}' in ADMIN
assert "Search recorded administrative, authentication, incident, and responder activity" in ADMIN

# Compact overview metrics and readable responsive charts.
assert ".admin-metrics { display:grid; grid-template-columns:repeat(2,minmax(0,1fr));" in CSS
assert ".admin-overview-activity__list" in CSS
assert ".admin-analytics-status-chart__rows" in CSS
assert ".admin-analytics-card--wide { grid-column:1 / -1; }" in CSS
assert "@media (prefers-reduced-motion:reduce)" in CSS

assert "**Overview:** compact operational snapshot" in GUIDE
assert "**Analytics & reports:** aggregate incident request totals" in GUIDE
assert "**Activity log:** read-only, paginated investigation history" in GUIDE
assert "not a complete count of every action or denied attempt" in GUIDE

print("Admin Overview, Analytics & Reports, and Activity Log separation contract passed.")
