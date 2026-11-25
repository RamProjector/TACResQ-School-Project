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
admin_supabase = (ROOT / "backend/web/src/lib/supabase.ts").read_text()
assert "p_expected_account_status" in admin_supabase.split("api_admin_set_account_status:", 1)[1].splitlines()[0], "Admin lifecycle RPC type must match the compare-and-set SQL signature."
assert "p_expected_account_status: expectedStatus" in admin_supabase, "Admin lifecycle RPC call must send the expected status for compare-and-set."
assert 'if (["42501", "22023", "P0002", "55006"].includes(error.code ?? "")) throwAdminError(error);' in admin_supabase, "Blocked suspension must preserve the actionable active-assignment error."
audit_page = (ROOT / "backend/web/src/pages/AuditLogsPage.tsx").read_text()
for page in ('"overview"', '"users"', '"security"', '"analytics"', '"audit"'):
    assert page in admin_console
assert '"settings"' in admin_console
assert "AdminSettingsPage" in admin_console
assert "navigateToSettingsPage" in admin_console
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
privacy_migration = (ROOT / "supabase/migrations/20261123045605_20261122200000_admin_directory_contact_minimization.sql").read_text().lower()
assert "api_admin_list_users_page_internal" in privacy_migration
assert "'phone'" not in privacy_migration and "p.phone" not in privacy_migration
assert "api_admin_list_users_page(" not in privacy_migration, "Keep the public TOTP/AAL2 wrapper intact."
assert "revoke all on function public.api_admin_list_users_page_internal" in privacy_migration

# Mobile Admin navigation must remain reachable instead of disappearing with the desktop sidebar.
admin_css = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text()
admin_resolution_css = (ROOT / "backend/web/src/pages/AdminResolution.css").read_text()
admin_security = (ROOT / "backend/web/src/components/AdminSecurityStatus.tsx").read_text()
assert "@media (max-width: 760px)" in admin_css
assert ".admin-navigation {" in admin_css
assert "overflow-x: auto" in admin_css
assert ".admin-sidebar .admin-nav-item" in admin_css
assert ".admin-nav-item:focus-visible" in admin_css
assert "@media (max-width: 520px)" in admin_css
assert ".admin-topbar__right" in admin_css
assert 'className="admin-skip-link" href="#admin-workspace-main"' in admin_console
assert 'id="admin-workspace-main" tabIndex={-1}' in admin_console
assert ".admin-skip-link:focus" in admin_css
assert 'id="admin-user-management" aria-labelledby="admin-user-management-title"' in admin_console
assert 'id="admin-account-directory-table" aria-label="Administrator account directory" aria-describedby="admin-directory-status"' in admin_console
assert '<nav className="admin-pagination" aria-label="User directory pagination" aria-controls="admin-account-directory-table">' in admin_console
assert '<th scope="col">Account</th>' in admin_console
assert 'id="audit-event-list" aria-busy={loading}' in audit_page
assert '<nav className="admin-pagination audit-page__pagination" aria-label="Investigation history pagination" aria-controls="audit-event-list">' in audit_page
assert ".admin-user strong" in admin_css
# Printed security reports should preserve the page heading and avoid forced blank pages.
assert ".admin-heading__identity { break-after:avoid; }" in admin_css
assert ".admin-security-status__coverage { break-before:auto !important; }" in admin_css
assert "break-inside:avoid-page" in admin_css

# Account lifecycle events have their own bounded server-side pagination and honest loading/error states.
assert "const lifecyclePageSize = 5" in audit_page
assert "getAdminAccountLifecycleEvents(lifecyclePageSize, lifecyclePage * lifecyclePageSize)" in audit_page
assert "const lifecyclePageCount = Math.ceil(lifecycleTotal / lifecyclePageSize)" in audit_page
assert 'aria-label="Account lifecycle history pagination"' in audit_page
assert 'disabled={lifecycleLoading || lifecyclePage === 0}' in audit_page
assert 'disabled={lifecycleLoading || lifecyclePage + 1 >= lifecyclePageCount}' in audit_page
assert 'role="status" aria-live="polite"><LoaderCircle' in audit_page
assert 'role="alert">{lifecycleError}' in audit_page
assert 'aria-busy={lifecycleLoading}' in audit_page
assert '{lifecycleError ? "Unavailable" : lifecycleTotal.toLocaleString() + " recorded"}' in audit_page
assert "setLifecyclePage(Math.max(0, Math.ceil(result.total_count / lifecyclePageSize) - 1))" in audit_page
assert ".audit-page__lifecycle-pagination" in admin_css
assert ".audit-page__lifecycle-pagination button:focus-visible" in admin_css
assert "@media (max-width:520px)" in admin_css

# Audit history documents each integrated source, not a fragile copy string.
# Keep this inventory aligned with the source-specific coverage explanations rendered to Admins.
for source_label in (
    "Admin role changes",
    "Supabase Auth",
    "Incident status",
    "Incident claiming",
    "Requester edits",
    "Responder assignments",
    "App actions",
):
    assert f"<strong>{source_label}</strong>" in audit_page, f"Audit coverage is missing {source_label}."
assert "Feed reader: integrated." in audit_page
assert "not proof that every source writer or provider setting is active" in audit_page
assert "A zero-row result means no matching records were returned" in audit_page
assert "selected-action subset, not comprehensive activity monitoring" in audit_page
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
assert 'todayActivityError ? "Unavailable" : todayActivityLoading ? "Loading" : todayActivityCount === null ? "—" : todayActivityCount.toLocaleString()' in admin_console
assert "setTodayActivityCount(result.total_count)" in admin_console
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

# Security overview copy must not imply unverified production enforcement, and long panels must paginate.
assert '<section className="admin-panel admin-roadmap-panel">' in admin_console
assert 'Security overview</h2>' in admin_console
assert 'Branch policy · deployment unverified' in admin_console
assert 'This describes the repository implementation only; deployment and live-project enforcement have not been independently verified.' in admin_console
assert 'TOTP enforced</span>' not in admin_console
assert 'aria-label={profile.name} title={profile.name}' in admin_console
assert '.admin-security-status { break-inside:auto !important; }' in admin_css
assert '.admin-security-status__factor-list li,' in admin_css
assert '.admin-security-status__confirm { display:none !important; }' in admin_css

# Analytics announcements and labels must communicate only the changed state and its scope.
assert '<section className="admin-panel admin-analytics-panel"' in admin_console
assert 'aria-labelledby="admin-analytics-title"' in admin_console
assert 'aria-busy={analyticsLoading}' in admin_console
assert '<div className="admin-empty" role="status" aria-live="polite"><LoaderCircle' in admin_console
assert 'role="group" aria-label="Current status of requests created during the selected reporting period"' in admin_console
assert '<span>Period (Manila time)</span>' in admin_console
assert 'No daily buckets were returned for this reporting period.' in admin_console
assert 'No daily data for this period.' not in admin_console

# A shrinking server-side account set must move the directory back to its last valid page.
assert "if (page > 0 && page * pageSize >= userPage.total_count)" in admin_console
assert "Math.max(0, Math.ceil(userPage.total_count / pageSize) - 1)" in admin_console
assert "setUsers([]);" in admin_console

# Pending server-side directory searches must not display rows from the previous query.
assert "const filteredUsers = directoryFilterPending ? [] : users;" in admin_console
assert "Applying search…" in admin_console
assert "setDebouncedQuery(query.trim());" in admin_console

# Directory filters identify their result table, and mobile users can discover and operate horizontal scrolling.
assert 'id="admin-directory-search"' in admin_console
assert 'id="admin-directory-role-filter"' in admin_console
assert 'aria-controls="admin-account-directory-table" aria-describedby="admin-directory-status"' in admin_console
assert 'className="admin-table-scroll-hint"' in admin_console
assert ".admin-toolbar {\n  flex-wrap:wrap;" in admin_css
assert ".admin-search {\n  flex:1 1 260px;" in admin_css
assert ".admin-table {\n  min-width:720px;" in admin_css
assert ".admin-account strong,\n.admin-account small {\n  overflow-wrap:anywhere;" in admin_css
assert ".admin-table-wrap:focus-visible {" in admin_css
assert "@media (max-width:760px) {\n  .admin-table-scroll-hint {" in admin_css

print("Web admin-only contract passed.")


# Shared Admin design-system contract: stable type scale and explicit resolution tiers.
assert "--admin-space-1:4px;" in admin_css
assert "--admin-font-base:clamp(" in admin_css
assert "--admin-content-max:1680px;" in admin_css
assert "@media (min-width:1600px)" in admin_css
assert "@media (max-width:1200px)" in admin_css
assert "@media (max-width:900px)" in admin_css
assert "@media (max-width:760px)" in admin_css
assert "@media (max-width:480px)" in admin_css
assert "@media (min-width:1920px)" in admin_css
assert ".admin-table td { padding-block:15px; }" in admin_css
assert ".admin-pagination { min-height:54px;" in admin_css
assert '.admin-shell :where(button,input,select,textarea) { font-family:inherit; }' in admin_css


# Resolution-aware Admin design contract: typography, layout tiers, and page-level reflow.
assert 'import "./AdminResolution.css";' in admin_console
assert '--admin-ui-font:' in admin_resolution_css
assert '--admin-ui-gutter: 36px;' in admin_resolution_css
assert 'font-size: var(--admin-ui-title);' in admin_resolution_css
for breakpoint in (
    '@media (min-width: 1920px)',
    '@media (min-width: 1440px) and (max-width: 1919px)',
    '@media (min-width: 1201px) and (max-width: 1439px)',
    '@media (min-width: 901px) and (max-width: 1200px)',
    '@media (min-width: 761px) and (max-width: 900px)',
    '@media (max-width: 760px)',
    '@media (max-width: 480px)',
    '@media (max-width: 520px)',
):
    assert breakpoint in admin_resolution_css, f"Missing Admin resolution tier: {breakpoint}"
assert '.admin-navigation {\n    min-width: 0;' in admin_resolution_css
assert 'overflow-x: auto;' in admin_resolution_css
assert '.audit-page__filters {' in admin_resolution_css
assert 'grid-template-columns: repeat(3,minmax(0,1fr));' in admin_resolution_css
assert '.audit-event-card__meta { grid-template-columns: minmax(0,1fr); }' in admin_resolution_css
assert '.admin-analytics-detail-grid { grid-template-columns: minmax(0,1fr); }' in admin_resolution_css
assert '.admin-table { min-width: 760px; }' in admin_resolution_css
assert 'max-height: min(88dvh, 760px);' in admin_resolution_css
assert '@media (prefers-reduced-motion: reduce)' in admin_resolution_css


# Admin page usability contract: header reflow, audit detail hierarchy, directory controls, and print.
assert ".admin-breadcrumb strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }" in admin_resolution_css
assert "@media (min-width: 761px) and (max-width: 1100px)" in admin_resolution_css
assert ".admin-topbar__right { width: 100%; justify-content: flex-end; }" in admin_resolution_css
assert ".audit-event-card__details dl {" in admin_resolution_css
assert "grid-template-columns: minmax(0,1fr);" in admin_resolution_css
assert ".audit-page__coverage-details > summary { min-height: 44px;" in admin_resolution_css
assert ".admin-directory-status {" in admin_resolution_css
assert ".admin-table-caption {" in admin_resolution_css
assert ".admin-skip-link:focus { transform: translateY(0); }" in admin_resolution_css
assert "min-height: 44px;" in admin_resolution_css
assert "@media (prefers-contrast: more)" in admin_resolution_css
assert "@media (forced-colors: active)" in admin_resolution_css
assert "@media print {" in admin_resolution_css
assert ".audit-event-card__details, .audit-event-card__references { display: block !important; }" in admin_resolution_css


# Printed tables repeat their headings and avoid splitting individual account rows.
assert ".admin-table thead { display: table-header-group; }" in admin_resolution_css
assert ".admin-table tr { break-inside: avoid; page-break-inside: avoid; }" in admin_resolution_css
assert ".admin-table tfoot { display: table-footer-group; }" in admin_resolution_css

# Dense identifiers remain inside the printable page box.
assert ".audit-event-card__details dd," in admin_resolution_css
assert "overflow-wrap: anywhere;" in admin_resolution_css
assert ".audit-event-card__details dl { gap: 7pt 12pt; }" in admin_resolution_css

# Very narrow viewports and zoomed layouts retain usable controls without shrinking tables.
assert "@media (max-width: 360px)" in admin_resolution_css
assert ".admin-pagination > div { flex-direction: column; }" in admin_resolution_css
assert ".admin-metrics { grid-template-columns: minmax(0,1fr); }" in admin_resolution_css


# Privileged role-change feedback uses a dismissible, timed toast outside the modal.
assert 'const [roleToast, setRoleToast] = useState<{ type: "success" | "error"; message: string } | null>(null);' in admin_console
assert 'roleToastTimer.current = window.setTimeout(() => {' in admin_console
assert 'roleToastTimer.current = null;' in admin_console
assert '}, 5000);' in admin_console
assert 'function pauseRoleToastDismissal()' in admin_console
assert 'function resumeRoleToastDismissal()' in admin_console
assert 'className="admin-toast-region" aria-atomic="true"' in admin_console
assert 'className={"admin-toast admin-toast--" + roleToast.type}' in admin_console
assert 'role={roleToast.type === "error" ? "alert" : "status"}' in admin_console
assert 'onMouseEnter={pauseRoleToastDismissal}' in admin_console
assert 'onMouseLeave={resumeRoleToastDismissal}' in admin_console
assert 'onFocusCapture={pauseRoleToastDismissal}' in admin_console
assert 'onBlurCapture={resumeRoleToastDismissal}' in admin_console
assert 'aria-label="Dismiss notification"' in admin_console
assert 'const roleChangeInFlight = useRef(false);' in admin_console
assert 'if (!target || saving || roleChangeInFlight.current || target.role === "admin") return;' in admin_console
assert 'roleChangeInFlight.current = true;' in admin_console
assert 'roleChangeInFlight.current = false;' in admin_console
assert 'setRoleToast({ type: "error", message: "Enter an audit reason between 10 and 500 characters." })' in admin_console
assert 'setRoleToast({ type: "success", message: changed.name +' in admin_console
assert 'setRoleToast({ type: "error", message: cause instanceof Error ? cause.message' in admin_console
assert 'className="admin-modal__error"' not in admin_console
assert 'aria-describedby="admin-role-dialog-description admin-role-dialog-guidance"' in admin_console
assert 'id="admin-role-dialog-description"' in admin_console
assert 'id="admin-role-dialog-guidance"' in admin_console
assert 'id="admin-change-reason-guidance"' in admin_console
assert 'aria-describedby="admin-change-reason-guidance admin-reason-count"' in admin_console
assert 'id="admin-reason-count"' in admin_console
assert ".admin-toast-region {" in admin_resolution_css
assert ".admin-toast--error {" in admin_resolution_css
assert ".admin-toast--success {" in admin_resolution_css
assert "@media (max-width: 480px)" in admin_resolution_css
assert "@media print {" in admin_resolution_css and ".admin-toast-region { display: none !important; }" in admin_resolution_css
assert ".admin-form-hint {" in admin_resolution_css
assert ".admin-reason-count {" in admin_resolution_css

# MFA factor management stays unavailable without a TOTP-verified AAL2 session.
assert 'const canManageFactors = snapshot?.currentLevel === "aal2" && snapshot.usedTotp === true;' in admin_security
assert 'disabled={busy || !canManageFactors || (factor.status === "verified" && verifiedFactors.length <= 1)}' in admin_security
assert 'disabled={busy || !canManageFactors || snapshot.factors.length >= 10}' in admin_security
assert 'Verify a registered authenticator in this session to add or remove factors.' in admin_security
assert 'aria-label={`Confirm removal of ${factor.name}`}' in admin_security
assert 'role="group" aria-label="Confirm global sign-out"' in admin_security
assert 'aria-describedby="admin-backup-enrollment-guidance" onSubmit={verifyBackupAuthenticator}' in admin_security
assert '.admin-security-status__factor-list li { flex-wrap:wrap; }' in admin_css
assert '.admin-security-status :where(button,input):focus-visible {' in admin_css
assert '.admin-security-status__factor-list li > .admin-security-status__actions { width:100%;' in admin_css

# Authenticator mutations re-read current factor inventory instead of trusting stale UI state.
assert 'function hasTotpMethod(data:' in admin_security
assert 'const currentTotpFactors = latestFactors.all.filter((item) => item.factor_type === "totp");' in admin_security
assert 'const currentTarget = currentTotpFactors.find((item) => item.id === factor.id);' in admin_security
assert 'const currentVerifiedCount = currentTotpFactors.filter((item) => item.status === "verified").length;' in admin_security
assert 'if (currentTarget.status === "verified" && currentVerifiedCount <= 1)' in admin_security
assert 'const currentTotpFactors = latestFactors.all.filter((factor) => factor.factor_type === "totp");' in admin_security
assert 'if (currentTotpFactors.length >= 10)' in admin_security
assert 'const existingNames = new Set(currentTotpFactors.map((factor) => factor.friendly_name || "Authenticator app"));' in admin_security
