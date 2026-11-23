from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
home = (ROOT / "backend/web/src/pages/Home.tsx").read_text()
dockerfile = (ROOT / "backend/Dockerfile").read_text()
web_routes = (ROOT / "backend/src/routes/web.php").read_text()
ci = (ROOT / ".github/workflows/ci.yml").read_text()
web_readme = (ROOT / "backend/web/README.md").read_text()

# The browser frontend must not offer an incident queue or dispatch mutations.
for forbidden in ("DispatcherBoard", "assignIncident(", "getAllIncidents(", "getFieldUnits("):
    assert forbidden not in home, f"Web frontend still contains dispatcher operation: {forbidden}"

# Keep administrator governance and a clear dispatcher-to-Android handoff.
assert 'profile.role === "admin"' in home
assert "<AdminConsole" in home
assert 'profile.role === "dispatcher"' in home
assert "TACResQ Android app" in home

# Web sign-out must match Android local-session behavior; global revocation is separate.
assert 'auth.signOut({ scope: "local" })' in home
assert "auth.signOut()" not in home

# The deployed static app is the admin portal; no legacy /dispatch dashboard path remains.
assert "VITE_BASE_PATH=/admin/" in dockerfile
assert "./public/admin/" in dockerfile
assert "public_path('admin/index.html')" not in web_routes
assert "redirect('/admin/')" in web_routes
assert "Route::get('/dispatch/config.json'" not in web_routes
assert "VITE_BASE_PATH: /admin/" in ci
assert "http://127.0.0.1:8000/admin/" in ci
assert "/admin/assets/" in ci
assert "administrator-only" in web_readme.lower()
assert "incident queue" in web_readme.lower()

# Keep the portal focused on implemented administrative capabilities.
admin_console = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
audit_page = (ROOT / "backend/web/src/pages/AuditLogsPage.tsx").read_text()
for page in ('"overview"', '"users"', '"security"', '"analytics"', '"audit"'):
    assert page in admin_console
assert '"settings"' not in admin_console
assert '"backups"' not in admin_console
assert 'import AuditLogsPage from "@/pages/AuditLogsPage"' in admin_console
assert 'activeSection === "audit" && <AuditLogsPage profile={profile} />' in admin_console
assert 'value={query}' in audit_page
assert 'events.map((event)' in audit_page
assert 'getAdminInvestigationEventsPage(' in audit_page
assert "const PAGE_SIZE = 25" in audit_page
assert "latest 100 events" not in audit_page
assert "BACKEND-VERIFIED CAPABILITIES" not in admin_console

# The default Admin directory must not return, render, or search phone numbers.
admin_user_type = (ROOT / "backend/web/src/lib/supabase.ts").read_text().split("export interface AdminUser", 1)[1].split("\n", 1)[0]
assert "phone" not in admin_user_type, "Admin directory client type must not include phone contact data."
assert 'placeholder="Search name, email or profile ID"' in admin_console
assert '{user.email}</small>' in admin_console and "user.phone" not in admin_console
privacy_migration = (ROOT / "supabase/migrations/20261122200000_admin_directory_contact_minimization.sql").read_text().lower()
assert "api_admin_list_users_page_internal" in privacy_migration
assert "'phone'" not in privacy_migration and "p.phone" not in privacy_migration
assert "api_admin_list_users_page(" not in privacy_migration, "Keep the public TOTP/AAL2 wrapper intact."
assert "revoke all on function public.api_admin_list_users_page_internal" in privacy_migration

# Mobile Admin navigation must remain reachable instead of disappearing with the desktop sidebar.
admin_css = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text()
admin_security = (ROOT / "backend/web/src/components/AdminSecurityStatus.tsx").read_text()
assert "@media (max-width: 760px)" in admin_css
assert ".admin-navigation {" in admin_css
assert "overflow-x: auto" in admin_css
assert ".admin-sidebar .admin-nav-item" in admin_css
assert ".admin-nav-item:focus-visible" in admin_css
assert "@media (max-width: 520px)" in admin_css
assert ".admin-topbar__right" in admin_css
assert ".admin-user strong" in admin_css
# Printed security reports should preserve the page heading and avoid forced blank pages.
assert ".admin-heading__identity { break-after:avoid; }" in admin_css
assert ".admin-security-status__coverage { break-before:auto !important; }" in admin_css
assert "break-inside:avoid-page" in admin_css

# Audit history labels its actual seven queried sources and exposes accessible detail controls.
assert "Seven recorded event sources are queried." in audit_page
assert "Six recorded event sources are queried." not in audit_page
assert 'aria-controls={"audit-event-details-" + event.event_id}' in audit_page
assert 'id={"audit-event-details-" + event.event_id}' in audit_page
assert 'aria-label={(expanded === event.event_id ? "Hide details for " : "View details for ") + event.summary}' in audit_page
assert 'role="status" aria-live="polite">{copyError || (copiedEventId ? "Technical reference copied." : "")}' in audit_page
assert '<dd>{actorDisplay(event, profile)}</dd>' in audit_page

# Overview and directory states remain accurate while server-side filters are pending.
assert "Searchable read-only history across seven event sources" in admin_console
assert "Searchable read-only history across six event sources" not in admin_console
assert '{directoryError ? "User data is unavailable" : "No accounts match this filter"}' in admin_console
assert '{directoryError ? "The page does not substitute demo accounts for live data." : "Try another search or role filter."}' in admin_console
assert 'disabled={page === 0 || refreshing || directoryFilterPending || Boolean(directoryError)}>Previous' in admin_console
assert 'disabled={refreshing || directoryFilterPending || Boolean(directoryError) || (page + 1) * pageSize >= totalUsers}>Next' in admin_console
assert 'aria-busy={loading || refreshing || directoryFilterPending}' in admin_console

# Overview metrics must expose their scope and avoid presenting loading or failed data as zero.
assert 'aria-describedby="admin-overview-metrics-context"' in admin_console
assert 'id="admin-overview-metrics-context"' in admin_console
assert 'directoryError ? "Unavailable" : loading && users.length === 0 ? "Loading" : totalUsers.toLocaleString()' in admin_console
assert 'directoryError ? "Unavailable" : refreshing || directoryFilterPending ? "Updating"' in admin_console
assert 'role="status" aria-live="polite"><LoaderCircle' in admin_console
assert ".admin-metrics strong { min-width:0; font-size:clamp(20px,2vw,29px); overflow-wrap:anywhere; }" in admin_css
assert ".admin-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); }" in admin_css

# Dependency diagnostics count the same three probes, expose loading limits, and avoid noisy live announcements.
assert 'aria-describedby="admin-health-limitations" aria-busy={dependencyHealthLoading}' in admin_console
assert 'id="admin-health-limitations"' in admin_console
assert 'reachableDependencyCount === dependencyProbes.length' in admin_console
assert 'reachableDependencyCount + " of " + dependencyProbes.length + " dependencies responded within the probe threshold"' in admin_console
assert '{dependencyProbes.map(([label, result]) => (' in admin_console
assert 'Last checked " + formatManilaDateTime(dependencyHealth.checked_at) + " (Asia/Manila)"' in admin_console
assert 'className={"admin-health-status admin-health-status--" + result.status} role="status"' not in admin_console
assert '.admin-health-grid { grid-template-columns:minmax(0,1fr); }' in admin_css

# MFA status changes must not turn the entire Security panel into a live region.
assert 'className="admin-panel admin-security-status" aria-labelledby="admin-security-status-title" aria-busy={loading || busy}' in admin_security
assert 'className="admin-security-status__message" role="status" aria-live="polite"><Clock3' in admin_security
assert 'Refreshing Supabase Auth status…' in admin_security
assert 'aria-labelledby="admin-security-enrollment-title"' in admin_security
assert 'id="admin-security-enrollment-title"' in admin_security
assert 'aria-describedby="admin-backup-enrollment-guidance"' in admin_security
assert 'id="admin-backup-enrollment-guidance"' in admin_security
assert '.admin-security-status__enrollment { display:none !important; }' in admin_css

# Analytics announcements and labels must communicate only the changed state and its scope.
assert 'className="admin-panel admin-analytics-panel" aria-labelledby="admin-analytics-title">' in admin_console
assert '<div className="admin-empty" role="status" aria-live="polite"><LoaderCircle' in admin_console
assert 'role="group" aria-label="Current status of requests created during the selected reporting period"' in admin_console
assert '<span>Period (Manila time)</span>' in admin_console
assert 'No daily buckets were returned for this reporting period.' in admin_console
assert 'No daily data for this period.' not in admin_console

print("Web admin-only contract passed.")
