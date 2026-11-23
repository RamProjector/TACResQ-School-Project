import { useCallback, useEffect, useRef, useState } from "react";
import {
  AlertCircle,
  Activity,
  BarChart3,
  History,
  LayoutDashboard,
  ShieldAlert,
  ArrowDownUp,
  CheckCircle2,
  LoaderCircle,
  LogOut,
  RefreshCw,
  Search,
  ShieldCheck,
  Users,
  X,
} from "lucide-react";
import { Brand } from "@/components/Brand";
import AuditLogsPage from "@/pages/AuditLogsPage";
import AdminSecurityStatus from "@/components/AdminSecurityStatus";
import {
  getAdminAnalyticsSummary,
  getAdminDependencyHealth,
  getAdminUsersPage,
  getMyProfile,
  setAdminUserRole,
} from "@/lib/supabase";
import type {
  AdminAnalyticsSummary,
  AdminDependencyHealth,
  AdminManagedRole,
  AdminUser,
  TacruProfile,
} from "@/lib/supabase";
import "./AdminConsole.css";
import "./PortalVisualFoundation.css";

type AdminConsoleProps = {
  profile: TacruProfile;
  onSignOut: () => void;
};

type RoleFilter = "all" | AdminManagedRole | "admin";
type AdminSection = "overview" | "users" | "security" | "analytics" | "audit";

const adminSections: { id: AdminSection; label: string; icon: typeof LayoutDashboard }[] = [
  { id: "overview", label: "Overview", icon: LayoutDashboard },
  { id: "users", label: "User management", icon: Users },
  { id: "security", label: "Security", icon: ShieldAlert },
  { id: "analytics", label: "Reports", icon: BarChart3 },
  { id: "audit", label: "Activity log", icon: History },
];
const roleOptions: AdminManagedRole[] = ["requester", "responder", "driver", "dispatcher"];

function resolveAdminSection(search: string): AdminSection {
  const route = new URLSearchParams(search).get("adminPage") ?? "overview";
  if (route === "audit-logs") return "audit";
  return ["overview", "users", "security", "analytics"].includes(route) ? route as AdminSection : "overview";
}

function displayRole(role: string) {
  return role.charAt(0).toUpperCase() + role.slice(1);
}

function getManilaAnalyticsRange(days: 7 | 30 | 90, now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Manila",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const part = (type: string) => parts.find((item) => item.type === type)?.value ?? "";
  const year = Number(part("year"));
  const month = Number(part("month"));
  const day = Number(part("day"));
  // Manila uses UTC+08:00. Start at local midnight and include today plus
  // the preceding days so daily buckets align with the database calendar.
  const from = new Date(Date.UTC(year, month - 1, day - (days - 1), 0, 0, 0) - 8 * 60 * 60 * 1000);
  return { from: from.toISOString(), to: now.toISOString() };
}

function formatManilaDateTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Time unavailable";
  return new Intl.DateTimeFormat("en-PH", {
    timeZone: "Asia/Manila",
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

function formatDate(value: string | null | undefined) {
  if (!value) return "Time unavailable";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Time unavailable";
  return new Intl.DateTimeFormat("en-PH", { timeZone: "Asia/Manila", dateStyle: "medium", timeStyle: "short" }).format(date);
}

function initials(name: string) {
  return name.trim().split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("") || "A";
}

export default function AdminConsole({ profile, onSignOut }: AdminConsoleProps) {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [dependencyHealth, setDependencyHealth] = useState<AdminDependencyHealth | null>(null);
  const [dependencyHealthError, setDependencyHealthError] = useState<string | null>(null);
  const [dependencyHealthLoading, setDependencyHealthLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [directoryError, setDirectoryError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [roleFilter, setRoleFilter] = useState<RoleFilter>("all");
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setPage(0);
      setDebouncedQuery(query);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);
  const [activeSection, setActiveSection] = useState<AdminSection>(() => resolveAdminSection(window.location.search));

  // Keep the selected admin view in sync with browser Back/Forward navigation.
  useEffect(() => {
    const syncSectionFromLocation = () => {
      setActiveSection(resolveAdminSection(window.location.search));
    };
    window.addEventListener("popstate", syncSectionFromLocation);
    return () => window.removeEventListener("popstate", syncSectionFromLocation);
  }, []);

  const [analytics, setAnalytics] = useState<AdminAnalyticsSummary | null>(null);
  const [analyticsLoading, setAnalyticsLoading] = useState(false);
  const [analyticsError, setAnalyticsError] = useState<string | null>(null);
  const [analyticsDays, setAnalyticsDays] = useState<7 | 30 | 90>(30);
  const [analyticsRefreshVersion, setAnalyticsRefreshVersion] = useState(0);
  const [page, setPage] = useState(0);
  const [totalUsers, setTotalUsers] = useState(0);
  const [directoryLoadedAt, setDirectoryLoadedAt] = useState<string | null>(null);
  const pageSize = 25;
  const [target, setTarget] = useState<AdminUser | null>(null);
  const [nextRole, setNextRole] = useState<AdminManagedRole>("requester");
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const modalRoot = useRef<HTMLDivElement>(null);
  const previousFocus = useRef<HTMLElement | null>(null);
  const requestVersion = useRef(0);

  // Return keyboard focus to the control that opened the role-change dialog.
  useEffect(() => {
    if (target) return;
    const trigger = previousFocus.current;
    if (trigger?.isConnected) trigger.focus();
    previousFocus.current = null;
  }, [target]);

  useEffect(() => {
    if (!target) return;
    const root = modalRoot.current;
    const getFocusable = () => Array.from(root?.querySelectorAll<HTMLElement>("button:not([disabled]), select:not([disabled]), textarea:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex='-1'])") ?? []);
    getFocusable()[0]?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !saving) {
        setTarget(null);
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = getFocusable();
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      // Keep keyboard navigation inside the modal even if focus was moved
      // outside it programmatically or by an unexpected browser interaction.
      if (!root?.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
        return;
      }
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [target, saving]);

  const refresh = useCallback(async () => {
    const requestId = ++requestVersion.current;
    setRefreshing(true);
    setError(null);
    setDirectoryError(null);
    // A section change invalidates an in-flight Overview probe. Clear its
    // loading state here because the superseded request will no longer finish
    // with the current request ID and therefore cannot clear it itself.
    if (activeSection !== "overview") setDependencyHealthLoading(false);
    if (activeSection !== "overview" && activeSection !== "users") {
      setLoading(false);
      setRefreshing(false);
      return;
    }
    // Run dependency probes independently from the account-directory RPC.
    // A user-list failure must not suppress system-health diagnostics.
    if (activeSection === "overview") {
      setDependencyHealthLoading(true);
      void getAdminDependencyHealth()
        .then((nextHealth) => {
          if (requestId !== requestVersion.current) return;
          setDependencyHealth(nextHealth);
          setDependencyHealthError(null);
        })
        .catch(() => {
          if (requestId !== requestVersion.current) return;
          setDependencyHealth(null);
          setDependencyHealthError("Dependency diagnostics are unavailable. User management and audit data remain independently available.");
        })
        .finally(() => {
          if (requestId === requestVersion.current) setDependencyHealthLoading(false);
        });
    }
    try {
      const userPage = await getAdminUsersPage(pageSize, page * pageSize, debouncedQuery, roleFilter);
      if (requestId !== requestVersion.current) return;
      // Record every successful directory response, including a response that
      // requires moving pagination back after the account count shrinks.
      setDirectoryLoadedAt(new Date().toISOString());
      // The underlying account set can shrink while an administrator is on a
      // later page (for example, after another admin changes/removes records).
      // Move to the last valid page instead of presenting a false empty result.
      if (page > 0 && page * pageSize >= userPage.total_count) {
        setTotalUsers(userPage.total_count);
        setUsers([]);
        setPage(Math.max(0, Math.ceil(userPage.total_count / pageSize) - 1));
        return;
      }
      setUsers(userPage.users);
      setTotalUsers(userPage.total_count);
    } catch (cause) {
      if (requestId !== requestVersion.current) return;
      // Never keep privileged data visible after an authorization or connection failure.
      setUsers([]);
      setTotalUsers(0);
      setDirectoryLoadedAt(null);
      // Dependency health is fetched independently and may still be valid
      // even when the account-directory RPC fails.
      const message = cause instanceof Error ? cause.message : "The Admin service could not load its data.";
      setDirectoryError(message);
      setError(message);
    } finally {
      if (requestId === requestVersion.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [activeSection, page, debouncedQuery, roleFilter]);

  useEffect(() => {
    const syncSection = () => setActiveSection(resolveAdminSection(window.location.search));
    syncSection();
    window.addEventListener("popstate", syncSection);
    return () => window.removeEventListener("popstate", syncSection);
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  useEffect(() => {
    if (activeSection !== "analytics") return;
    let active = true;
    const range = getManilaAnalyticsRange(analyticsDays);
    setAnalyticsLoading(true);
    setAnalyticsError(null);
    setAnalytics(null);
    void getAdminAnalyticsSummary(range.from, range.to)
      .then((result) => { if (active) setAnalytics(result); })
      .catch((cause: unknown) => {
        if (!active) return;
        setAnalytics(null);
        setAnalyticsError(cause instanceof Error ? cause.message : "Analytics could not be loaded.");
      })
      .finally(() => { if (active) setAnalyticsLoading(false); });
    return () => { active = false; };
  }, [activeSection, analyticsDays, analyticsRefreshVersion]);

  // Admin privileges can be revoked outside this browser session. Revalidate
  // when the console is visible and periodically while it remains open; a
  // successful profile response with a different role immediately signs out.
  useEffect(() => {
    let checking = false;
    const verifyCurrentRole = async () => {
      if (checking || document.visibilityState === "hidden") return;
      checking = true;
      try {
        const currentProfile = await getMyProfile();
        if (currentProfile.role !== "admin") {
          setUsers([]);
          setTarget(null);
          setError("Administrator access has been revoked. Sign in again with an authorized account.");
          onSignOut();
        }
      } catch {
        // A transient network failure is not proof that the role was revoked.
        // Protected RPCs still enforce authorization for every privileged action.
      } finally {
        checking = false;
      }
    };
    const interval = window.setInterval(() => { void verifyCurrentRole(); }, 60_000);
    document.addEventListener("visibilitychange", verifyCurrentRole);
    return () => {
      window.clearInterval(interval);
      document.removeEventListener("visibilitychange", verifyCurrentRole);
    };
  }, [onSignOut]);

  const filteredUsers = users;
  const hasDirectoryFilters = query.trim().length > 0 || roleFilter !== "all";
  const directoryFilterPending = query.trim() !== debouncedQuery;

  function openRoleChange(user: AdminUser) {
    if (user.role === "admin") return;
    previousFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setTarget(user);
    setNextRole(user.role);
    setReason("");
    setError(null);
    setNotice(null);
  }

  async function confirmRoleChange() {
    if (!target || saving || target.role === "admin") return;
    if (reason.trim().length < 10 || reason.trim().length > 500) {
      setError("Enter an audit reason between 10 and 500 characters.");
      return;
    }
    if (nextRole === target.role) {
      setError("Choose a different role before saving.");
      return;
    }
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      // Re-read this exact account immediately before mutation. The dialog may
      // have remained open while another administrator changed the account.
      const latestPage = await getAdminUsersPage(100, 0, String(target.id), "all");
      const latestTarget = latestPage.users.find((user) => user.id === target.id);
      if (!latestTarget || latestTarget.role !== target.role) {
        setTarget(null);
        await refresh();
        setError(!latestTarget
          ? "This account is no longer available in the directory. Refresh the results and review the account before trying again."
          : "This account's role changed while the confirmation dialog was open. The requested change was not submitted. Review the refreshed account before trying again.");
        return;
      }
      const changed = await setAdminUserRole(target.id, nextRole, reason.trim());
      setNotice(changed.name + "'s role changed from " + displayRole(changed.old_role) + " to " + displayRole(changed.role) + ". The action was recorded in the audit log.");
      setTarget(null);
      setReason("");
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The role change was not saved.");
    } finally {
      setSaving(false);
    }
  }

  const ActiveSectionIcon = adminSections.find((section) => section.id === activeSection)?.icon ?? LayoutDashboard;

  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <Brand inverse />
        <div className="admin-sidebar__context">
          <span>ADMIN WORKSPACE</span>
          <p>Manage accounts, security, and activity.</p>
        </div>
        <nav className="admin-navigation" aria-label="Admin sections">
          {adminSections.map(({ id, label, icon: Icon }, index) => (
            <div className="admin-nav-entry" key={id}>
              {index === 0 && <div className="admin-navigation__group-label">WORKSPACE</div>}
              {index === 1 && <div className="admin-navigation__group-label admin-navigation__group-label--spaced">GOVERNANCE</div>}
              <button type="button" className={"admin-nav-item" + (activeSection === id ? " admin-nav-item--active" : "")} aria-current={activeSection === id ? "page" : undefined} onClick={() => { setActiveSection(id); const url = new URL(window.location.href); const route = id === "audit" ? "audit-logs" : id; if (url.searchParams.get("adminPage") !== route) { url.searchParams.set("adminPage", route); window.history.pushState({}, "", url.toString()); } }}>
                <Icon size={17} aria-hidden="true" /><span>{label}</span>
              </button>
            </div>
          ))}
        </nav>
        <div className="admin-sidebar__bottom">
          <div className="admin-security-note"><ShieldCheck size={16} /><p>Role changes require a reason and are recorded by the database.</p></div>
          <div className="admin-sidebar__version">TACRESQ · PRIVILEGED ACCESS</div>
        </div>
      </aside>

      <main className="admin-main">
        <header className="admin-topbar">
          <div className="admin-breadcrumb" aria-label="Current location"><span>TACRESQ</span><b aria-hidden="true">/</b><span>ADMIN</span><b aria-hidden="true">/</b><strong aria-current="page">{adminSections.find((section) => section.id === activeSection)?.label ?? "Overview"}</strong></div>
          <div className="admin-topbar__right">
            <span className="admin-access-pill"><ShieldCheck size={14} /> ADMIN ACCESS</span>
            <div className="admin-user">
              <span className="admin-avatar">{initials(profile.name)}</span>
              <span><strong aria-label={profile.name} title={profile.name}>{profile.name}</strong><small>Administrator</small></span>
            </div>
            <button className="admin-signout" type="button" onClick={onSignOut}><LogOut size={16} /><span>Sign out</span></button>
          </div>
        </header>

        <div className="admin-workspace">
          <div className="admin-heading">
            <div className="admin-heading__identity">
              <span className="admin-heading__icon"><ActiveSectionIcon size={22} aria-hidden="true" /></span>
              <div>
                <p className="admin-eyebrow">ADMINISTRATION</p>
                <h1>{adminSections.find((section) => section.id === activeSection)?.label ?? "Overview"}</h1>
                <p>{activeSection === "overview" ? "A live overview of account governance and the dependencies currently checked by the portal." : activeSection === "users" ? "Review accounts and manage operational roles with an auditable reason." : activeSection === "audit" ? "Search recorded administrative, authentication, incident, and responder activity in one read-only feed." : "Administrative workspace. Capabilities are shown only when their supporting backend controls are available."}</p>
              </div>
            </div>
            {(activeSection === "overview" || activeSection === "users") && <button className="admin-refresh" type="button" onClick={() => { void refresh(); }} disabled={refreshing}>
              <RefreshCw size={16} className={refreshing ? "admin-spin" : ""} /> Refresh data
            </button>}
          </div>

          {error && (
            <div className="admin-banner admin-banner--error" role="alert">
              <AlertCircle size={17} />
              <span>{error}</span>
              <button type="button" aria-label="Dismiss error" onClick={() => setError(null)}><X size={15} /></button>
            </div>
          )}
          {notice && (
            <div className="admin-banner admin-banner--success" role="status">
              <CheckCircle2 size={17} />
              <span>{notice}</span>
              <button type="button" aria-label="Dismiss confirmation" onClick={() => setNotice(null)}><X size={15} /></button>
            </div>
          )}

          <section className="admin-metrics" aria-label="Account summary" aria-describedby="admin-overview-metrics-context" hidden={activeSection !== "overview"}>
            <article><span>PROFILES MATCHING FILTERS</span><strong>{totalUsers}</strong><small>Across all directory pages</small></article>
            <article><span>REQUESTERS · CURRENT PAGE</span><strong>{users.filter((user) => user.role === "requester").length}</strong><small>Only the profiles on this page</small></article>
            <article><span>FIELD UNITS · CURRENT PAGE</span><strong>{users.filter((user) => user.role === "responder" || user.role === "driver").length}</strong><small>Responders and drivers on this page only</small></article>
            <article><span>AUDIT HISTORY</span><strong>Activity feed</strong><small>Searchable read-only history across seven event sources</small></article>
          </section>
          <p className="admin-panel__footnote" hidden={activeSection !== "overview"}>Counts follow the current directory search and role filters. Requester and field-unit breakdowns describe only the currently loaded page of up to 25 profiles; they are not system-wide role totals. Directory page retrieved: {directoryLoadedAt ? formatManilaDateTime(directoryLoadedAt) : "not yet retrieved"} (Asia/Manila). This is the retrieval time, not each profile’s last-updated time.</p>

          <section className="admin-panel admin-health-panel" aria-labelledby="admin-health-title" hidden={activeSection !== "overview"}>
            <div className="admin-panel__heading">
              <div><p className="admin-eyebrow">SERVICE REACHABILITY</p><h2 id="admin-health-title"><ShieldCheck size={19} /> Dependency reachability</h2></div>
              <span className="admin-count">{dependencyHealthLoading ? "Checking…" : dependencyHealth ? "Last checked " + formatManilaDateTime(dependencyHealth.checked_at) : "No verified result"}</span>
            </div>
            {dependencyHealthError ? (
              <div className="admin-health-unavailable" role="status">{dependencyHealthError}</div>
            ) : !dependencyHealth ? (
              <div className="admin-health-unavailable" role="status">{dependencyHealthLoading ? "Checking the three configured dependencies…" : "Waiting for the first diagnostic check…"}</div>
            ) : (
              <>
                <div className="admin-health-summary" role="status" aria-live="polite">
                  <strong>
                    {[dependencyHealth.laravel_backend, dependencyHealth.supabase_auth, dependencyHealth.supabase_database].every((probe) => probe.status === "healthy")
                      ? "All three reachability probes responded"
                      : [dependencyHealth.laravel_backend, dependencyHealth.supabase_auth, dependencyHealth.supabase_database].some((probe) => probe.status === "unavailable")
                        ? "At least one reachability probe did not respond"
                        : "At least one reachability probe exceeded five seconds"}
                  </strong>
                  <span>This summary covers only the three probes listed below, not complete emergency-service readiness.</span>
                </div>
                <div className="admin-health-grid">
                {([
                  ["Laravel backend", dependencyHealth.laravel_backend],
                  ["Supabase Auth", dependencyHealth.supabase_auth],
                  ["Supabase database", dependencyHealth.supabase_database],
                ] as const).map(([label, result]) => (
                  <article className="admin-health-item" key={label}>
                    <div><strong>{label}</strong><span className={"admin-health-status admin-health-status--" + result.status} role="status" aria-label={label + " reachability: " + result.status}>{result.status === "healthy" ? "reachable" : result.status === "slow" ? "timed out" : "unavailable"}</span></div>
                    <small>{result.duration_ms === null ? "Response time unavailable" : result.duration_ms + " ms response time"}</small>
                  </article>
                ))}
                </div>
              </>
            )}
            <p className="admin-panel__footnote">Reachability only — not service readiness or emergency-operational status. Laravel /up confirms application liveness; Supabase Auth checks its health endpoint; the database probe executes a fixed, data-free RPC using the current Admin session. These checks do not verify incident creation, responder assignment, location accuracy, routing, push delivery, or end-to-end sign-in. “Timed out” means the probe exceeded five seconds; startup delay or network latency may contribute and does not by itself prove an outage. Refresh to retry.</p>
          </section>

          <section className="admin-panel" hidden={activeSection !== "users"}>
            <div className="admin-panel__heading">
              <div><p className="admin-eyebrow">IDENTITY &amp; ACCESS</p><h2><Users size={19} /> User management</h2></div>
              <span className="admin-count">{directoryError ? "Account results unavailable" : totalUsers === 0 ? "0 matching accounts" : (page * pageSize + 1) + "–" + Math.min((page + 1) * pageSize, totalUsers) + " of " + totalUsers + " matching"}</span>
            </div>
            <div className="admin-toolbar">
              <label className="admin-search"><Search size={16} /><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} maxLength={120} placeholder="Search name, email or profile ID" aria-label="Search users" /></label>
              <label className="admin-role-filter"><span>Role</span><select value={roleFilter} onChange={(event) => { setPage(0); setRoleFilter(event.target.value as RoleFilter); }} aria-label="Filter users by role"><option value="all">All roles</option><option value="requester">Requester</option><option value="responder">Responder</option><option value="driver">Driver</option><option value="dispatcher">Dispatcher</option><option value="admin">Admin</option></select></label>
              {hasDirectoryFilters && <button className="admin-filter-reset" type="button" onClick={() => { setQuery(""); setDebouncedQuery(""); setRoleFilter("all"); setPage(0); }} disabled={refreshing}>Clear filters</button>}
            </div>
            <p className="admin-directory-status" role="status" aria-live="polite">{directoryFilterPending ? "Applying search…" : refreshing ? "Updating account results…" : directoryError ? "Account results could not be retrieved. Use Refresh data to try again." : hasDirectoryFilters ? "Showing " + totalUsers.toLocaleString() + " matching accounts. Search and role filters are applied on the server." : "Showing all accounts. Search and role filters are applied on the server."}{directoryLoadedAt && !directoryError ? " Last successful retrieval: " + formatManilaDateTime(directoryLoadedAt) + " (Asia/Manila)." : ""}</p>
            <div className="admin-table-wrap" role="region" aria-label="Account results; horizontally scrollable" tabIndex={0} aria-busy={loading || refreshing || directoryFilterPending}>
              <table className="admin-table" aria-label="Administrator account directory">
                <caption className="admin-table-caption">Account details and available role-management actions for the current directory page.</caption>
                <thead><tr><th>Account</th><th>Role</th><th>Duty status</th><th>Created</th><th>Action</th></tr></thead>
                <tbody>
                  {loading && users.length === 0 ? (
                    <tr><td colSpan={5}><div className="admin-empty"><LoaderCircle size={19} className="admin-spin" /> Loading accounts from Supabase…</div></td></tr>
                  ) : filteredUsers.length === 0 ? (
                    <tr><td colSpan={5}><div className="admin-empty"><Users size={20} /><strong>{directoryError ? "User data is unavailable" : "No accounts match this filter"}</strong><span>{directoryError ? "The page does not substitute demo accounts for live data." : "Try another search or role filter."}</span></div></td></tr>
                  ) : filteredUsers.map((user) => (
                    <tr key={user.id}>
                      <td><div className="admin-account"><span className="admin-account__avatar">{initials(user.name)}</span><span><strong>{user.name}</strong><small>{user.email}</small><small className="admin-account__id">Profile #{user.id}</small></span></div></td>
                      <td><span className={"admin-role admin-role--" + user.role}>{displayRole(user.role)}</span></td>
                      <td><span className="admin-duty">{user.duty_status ? user.duty_status.replaceAll("_", " ") : "Not applicable"}</span></td>
                      <td className="admin-date">{formatDate(user.created_at)}</td>
                      <td>{user.role === "admin" ? <span className="admin-provisioned">Operator managed</span> : <button className="admin-change-role" type="button" onClick={() => openRoleChange(user)} disabled={loading || refreshing || directoryFilterPending || Boolean(directoryError)}><ArrowDownUp size={14} /> Change role</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="admin-pagination" aria-label="User directory pagination"><span role="status" aria-live="polite">{totalUsers === 0 ? "Showing 0 accounts" : `Showing ${page * pageSize + 1}–${Math.min((page + 1) * pageSize, totalUsers)} of ${totalUsers.toLocaleString()} accounts`} <span aria-hidden="true">·</span> Page {totalUsers === 0 ? 0 : page + 1} of {Math.max(1, Math.ceil(totalUsers / pageSize))}</span><div><button type="button" aria-label="Previous page of accounts" onClick={() => setPage((current) => Math.max(0, current - 1))} disabled={page === 0 || refreshing || directoryFilterPending || Boolean(directoryError)}>Previous</button><button type="button" aria-label="Next page of accounts" onClick={() => setPage((current) => current + 1)} disabled={refreshing || directoryFilterPending || Boolean(directoryError) || (page + 1) * pageSize >= totalUsers}>Next</button></div></div>
            <p className="admin-panel__footnote">Admin accounts cannot be created, promoted, demoted, or edited here. Initial administrator provisioning is a trusted-operator task. Role changes for field units are blocked while they are assigned to an active incident. Duty status describes a responder or driver’s operational availability; it is not an account-enabled, suspended, or sign-in status. This directory does not currently expose account suspension state.</p>
          </section>

          {activeSection === "audit" && <AuditLogsPage profile={profile} />}
          {activeSection === "analytics" && (
            <section className="admin-panel admin-analytics-panel" aria-labelledby="admin-analytics-title">
              <div className="admin-panel__heading">
                <div><p className="admin-eyebrow">INCIDENT OPERATIONS</p><h2 id="admin-analytics-title"><BarChart3 size={19} /> Request performance</h2></div>
                <div className="admin-analytics-controls">
                  <label className="admin-analytics-range"><span>Period (Manila time)</span><select value={analyticsDays} onChange={(event) => setAnalyticsDays(Number(event.target.value) as 7 | 30 | 90)} aria-label="Analytics reporting period"><option value={7}>Last 7 calendar days</option><option value={30}>Last 30 calendar days</option><option value={90}>Last 90 calendar days</option></select></label>
                  <button className="admin-refresh" type="button" onClick={() => setAnalyticsRefreshVersion((version) => version + 1)} disabled={analyticsLoading}><RefreshCw size={15} className={analyticsLoading ? "admin-spin" : ""} /> Refresh report</button>
                </div>
              </div>
              {analyticsLoading ? (
                <div className="admin-empty" role="status" aria-live="polite"><LoaderCircle size={19} className="admin-spin" /> Loading verified incident aggregates…</div>
              ) : analyticsError ? (
                <div className="admin-health-unavailable" role="alert"><strong>Analytics unavailable.</strong> {analyticsError} The endpoint must be deployed before this report can display live data.</div>
              ) : analytics ? (
                <>
                  <p className="admin-panel__footnote">Reporting window: {formatManilaDateTime(analytics.period_start)} – {formatManilaDateTime(analytics.period_end)} (Asia/Manila). The range starts at Manila midnight and includes today’s partial totals.</p>
                  <div className="admin-analytics-metrics">
                    <article><span>TOTAL REQUESTS</span><strong>{analytics.total_requests.toLocaleString()}</strong><small>Created during selected period</small></article>
                    <article><span>ACTIVE</span><strong>{analytics.active.toLocaleString()}</strong><small>Assigned, en route, on scene or transporting</small></article>
                    <article><span>RESOLVED</span><strong>{analytics.resolved.toLocaleString()}</strong><small>Currently marked resolved</small></article>
                    <article><span>CANCELLED</span><strong>{analytics.cancelled.toLocaleString()}</strong><small>Currently marked cancelled</small></article>
                  </div>
                  <div className="admin-analytics-detail-grid">
                    <section className="admin-analytics-card" aria-labelledby="admin-assignment-time-title">
                      <p className="admin-eyebrow">DISPATCH EFFICIENCY</p><h3 id="admin-assignment-time-title">Time to first assignment</h3>
                      <div className="admin-analytics-time"><strong>{analytics.average_assignment_minutes === null ? "—" : analytics.average_assignment_minutes.toLocaleString() + " min"}</strong><span>Average</span></div>
                      <div className="admin-analytics-secondary"><span>Median</span><strong>{analytics.median_assignment_minutes === null ? "—" : analytics.median_assignment_minutes.toLocaleString() + " min"}</strong></div>
                      <p className="admin-panel__footnote">Based on the first recorded “assigned” status transition for requests created in this period. {analytics.assigned_request_count.toLocaleString()} requests had a valid assignment time.</p>
                    </section>
                    <section className="admin-analytics-card" aria-labelledby="admin-request-trend-title">
                      <p className="admin-eyebrow">REQUEST VOLUME</p><h3 id="admin-request-trend-title">Daily requests</h3>
                      {analytics.daily_requests.length === 0 ? <div className="admin-empty" role="status">No daily buckets were returned for this reporting period.</div> : <div className="admin-analytics-bars" role="img" aria-label={analytics.daily_requests.map((point) => point.day + ": " + point.requests + " requests").join("; ")}>
                        {analytics.daily_requests.map((point) => {
                          const maximum = Math.max(1, ...analytics.daily_requests.map((item) => item.requests));
                          return <div className="admin-analytics-bar-column" key={point.day} title={point.day + ": " + point.requests + " requests"}><span>{point.requests || ""}</span><div><i style={{ height: Math.max(point.requests > 0 ? 4 : 0, point.requests / maximum * 100) + "%" }} /></div><small>{new Date(point.day + "T00:00:00Z").toLocaleDateString(undefined, { month: "short", day: "numeric", timeZone: "UTC" })}</small></div>;
                        })}
                      </div>}
                    </section>
                  </div>
                  <div className="admin-analytics-status-grid" role="group" aria-label="Current status of requests created during the selected reporting period">
                    {[["Pending", analytics.pending], ["Active", analytics.active], ["Resolved", analytics.resolved], ["Cancelled", analytics.cancelled]].map(([label, value]) => <div key={label}><span>{label}</span><strong>{value.toLocaleString()}</strong></div>)}
                  </div>
                  <p className="admin-panel__footnote">Aggregates include requests created within the selected timestamp range. Status counts reflect each request’s current status at query time, not historical status totals. All timestamps are evaluated by the database; daily buckets use {analytics.reporting_timezone}. No requester names, contact details, descriptions or coordinates are returned.</p>
                </>
              ) : null}
            </section>
          )}
          {activeSection === "security" && (
            <>
            <section className="admin-panel admin-roadmap-panel" aria-live="polite">
              <div className="admin-panel__heading">
                <div><p className="admin-eyebrow">IDENTITY &amp; ACCESS</p><h2><ShieldCheck size={19} /> Security &amp; countermeasures</h2></div>
                <span className="admin-count">TOTP enforced</span>
              </div>
              <div className="admin-roadmap-content">
                <strong>Administrator access requires Google Authenticator TOTP.</strong>
                <p>After password sign-in, administrators must enroll or verify a six-digit time-based code from Google Authenticator. Supabase Auth verifies the factor and issues an AAL2 session; the Admin RPCs and audit-log table independently require an AAL2 token with a TOTP authentication method. No SMS or email OTP is used. Supabase Auth does not currently provide app-generated recovery codes, so keep your authenticator available and do not remove your only verified factor.</p>
                <span className="admin-roadmap-status"><ShieldCheck size={14} /> Password + Google Authenticator TOTP · enforced server-side</span>
              </div>
            </section>
            <AdminSecurityStatus />
            </>
          )}
          <footer className="admin-footer"><span>TACRESQ · ADMINISTRATION</span><span>Administrator permissions are checked before each action</span></footer>
        </div>
      </main>

      {target && (
        <div className="admin-modal-scrim" ref={modalRoot} role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !saving) setTarget(null); }}>
          <section className="admin-modal" role="dialog" aria-modal="true" aria-labelledby="admin-role-dialog-title">
            <button className="admin-modal__close" type="button" aria-label="Close role change" onClick={() => setTarget(null)} disabled={saving}><X size={18} /></button>
            <div className="admin-modal__icon"><ShieldCheck size={22} /></div>
            <p className="admin-eyebrow">PRIVILEGED ACTION</p>
            <h2 id="admin-role-dialog-title">Change account role</h2>
            <p className="admin-modal__description">You are changing access for <strong>{target.name}</strong> ({target.email}). Review the role transition carefully. The database enforces authorization and records the reason in the audit log.</p>
            <div className="admin-role-transition" aria-live="polite" aria-atomic="true"><div><span>Current role</span><strong>{displayRole(target.role)}</strong></div><span className="admin-role-transition__arrow" aria-hidden="true">→</span><div><span>New role</span><strong>{displayRole(nextRole)}</strong></div></div>
            <p className="admin-role-warning">Changing a role changes the account’s permissions in TACResQ. Confirm the intended access level with the responsible operator before saving.</p>
            <label className="admin-form-label" htmlFor="admin-new-role">New role</label>
            <select id="admin-new-role" value={nextRole} onChange={(event) => setNextRole(event.target.value as AdminManagedRole)} disabled={saving}>
              {roleOptions.map((role) => <option key={role} value={role}>{displayRole(role)}</option>)}
            </select>
            <label className="admin-form-label" htmlFor="admin-change-reason">Reason for change <span>Required</span></label>
            <textarea id="admin-change-reason" value={reason} onChange={(event) => setReason(event.target.value)} minLength={10} maxLength={500} rows={4} placeholder="Record the operational reason for this role change." disabled={saving} />
            <div className="admin-reason-count">{reason.trim().length}/500 characters · minimum 10</div>
            <div className="admin-modal__actions">
              <button className="admin-modal-cancel" type="button" onClick={() => setTarget(null)} disabled={saving}>Cancel</button>
              <button className="admin-modal-confirm" type="button" onClick={() => { void confirmRoleChange(); }} disabled={saving || nextRole === target.role || reason.trim().length < 10 || reason.trim().length > 500}>
                {saving ? <><LoaderCircle size={16} className="admin-spin" /> Saving…</> : <><ShieldCheck size={16} /> Confirm {displayRole(nextRole)} access</>}
              </button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
