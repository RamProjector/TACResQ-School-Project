import { useCallback, useEffect, useRef, useState } from "react";
import {
  AlertCircle,
  Activity,
  BarChart3,
  Download,
  History,
  LayoutDashboard,
  ShieldAlert,
  ArrowDownUp,
  CheckCircle2,
  CircleHelp,
  Settings,
  BookOpen,
  Save,
  RotateCcw,
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
import "./AdminResolution.css";

type AdminConsoleProps = {
  profile: TacruProfile;
  onSignOut: () => void;
};

type RoleFilter = "all" | AdminManagedRole | "admin";
type AdminSection = "overview" | "users" | "security" | "analytics" | "audit" | "settings" | "help";

type AdminPreferences = {
  density: "comfortable" | "compact";
  reducedMotion: boolean;
  highContrast: boolean;
  contextualHelp: boolean;
};

const DEFAULT_ADMIN_PREFERENCES: AdminPreferences = {
  density: "comfortable",
  reducedMotion: false,
  highContrast: false,
  contextualHelp: true,
};

const ADMIN_PREFERENCES_KEY = "tacresq.admin.preferences.v1";

function readAdminPreferences(): AdminPreferences {
  try {
    const stored = window.localStorage.getItem(ADMIN_PREFERENCES_KEY);
    if (!stored) return DEFAULT_ADMIN_PREFERENCES;
    const parsed = JSON.parse(stored) as Partial<AdminPreferences>;
    return {
      density: parsed.density === "compact" ? "compact" : "comfortable",
      reducedMotion: parsed.reducedMotion === true,
      highContrast: parsed.highContrast === true,
      contextualHelp: parsed.contextualHelp !== false,
    };
  } catch {
    return DEFAULT_ADMIN_PREFERENCES;
  }
}

const adminSections: { id: AdminSection; label: string; icon: typeof LayoutDashboard }[] = [
  { id: "overview", label: "Overview", icon: LayoutDashboard },
  { id: "users", label: "User management", icon: Users },
  { id: "security", label: "Security", icon: ShieldAlert },
  { id: "analytics", label: "Reports", icon: BarChart3 },
  { id: "audit", label: "Activity log", icon: History },
  { id: "settings", label: "Settings", icon: Settings },
  { id: "help", label: "Help & about", icon: BookOpen },
];
const roleOptions: AdminManagedRole[] = ["requester", "responder", "driver", "dispatcher"];

function resolveAdminSection(search: string): AdminSection {
  const route = new URLSearchParams(search).get("adminPage") ?? "overview";
  if (route === "audit-logs") return "audit";
  return ["overview", "users", "security", "analytics", "settings", "help"].includes(route) ? route as AdminSection : "overview";
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

function exportAnalyticsCsv(summary: AdminAnalyticsSummary) {
  const rows: (string | number)[][] = [
    ["TACResQ incident performance report"],
    ["Reporting timezone", summary.reporting_timezone],
    ["Period start", summary.period_start],
    ["Period end", summary.period_end],
    [],
    ["Metric", "Value"],
    ["Total requests", summary.total_requests],
    ["Pending", summary.pending],
    ["Active", summary.active],
    ["Resolved", summary.resolved],
    ["Cancelled", summary.cancelled],
    ["Average assignment minutes", summary.average_assignment_minutes ?? "Not available"],
    ["Median assignment minutes", summary.median_assignment_minutes ?? "Not available"],
    ["Requests with assignment time", summary.assigned_request_count],
    [],
    ["Daily request volume"],
    ["Date", "Requests"],
    ...summary.daily_requests.map((point) => [point.day, point.requests]),
  ];
  const csv = "\uFEFF" + rows.map((row) =>
    row.map((value) => '"' + String(value).replaceAll('"', '""') + '"').join(",")
  ).join("\r\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = objectUrl;
  link.download = "tacresq-incident-report-" + summary.period_start.slice(0, 10) + "-" + summary.period_end.slice(0, 10) + ".csv";
  link.hidden = true;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(objectUrl);
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
  const [roleToast, setRoleToast] = useState<{ type: "success" | "error"; message: string } | null>(null);
  const roleToastTimer = useRef<number | null>(null);

  useEffect(() => {
    if (!roleToast) return;
    if (roleToastTimer.current !== null) window.clearTimeout(roleToastTimer.current);
    roleToastTimer.current = window.setTimeout(() => {
      roleToastTimer.current = null;
      setRoleToast(null);
    }, 5000);
    return () => {
      if (roleToastTimer.current !== null) window.clearTimeout(roleToastTimer.current);
      roleToastTimer.current = null;
    };
  }, [roleToast]);

  function pauseRoleToastDismissal() {
    if (roleToastTimer.current !== null) window.clearTimeout(roleToastTimer.current);
    roleToastTimer.current = null;
  }

  function resumeRoleToastDismissal() {
    if (!roleToast) return;
    if (roleToastTimer.current !== null) window.clearTimeout(roleToastTimer.current);
    roleToastTimer.current = window.setTimeout(() => {
      roleToastTimer.current = null;
      setRoleToast(null);
    }, 5000);
  }
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [roleFilter, setRoleFilter] = useState<RoleFilter>("all");
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setPage(0);
      setDebouncedQuery(query.trim());
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);
  const [activeSection, setActiveSection] = useState<AdminSection>(() => resolveAdminSection(window.location.search));
  const [preferences, setPreferences] = useState<AdminPreferences>(() => readAdminPreferences());
  const [savedPreferences, setSavedPreferences] = useState<AdminPreferences>(() => readAdminPreferences());
  const [preferencesError, setPreferencesError] = useState<string | null>(null);
  const preferencesDirty = JSON.stringify(preferences) !== JSON.stringify(savedPreferences);

  function savePreferences() {
    try {
      window.localStorage.setItem(ADMIN_PREFERENCES_KEY, JSON.stringify(preferences));
      setSavedPreferences(preferences);
      setPreferencesError(null);
      setNotice("Your Admin display preferences were saved on this device.");
    } catch {
      setPreferencesError("Preferences could not be saved in this browser. Check its storage settings and try again.");
    }
  }

  function resetPreferences() {
    setPreferences(DEFAULT_ADMIN_PREFERENCES);
    setPreferencesError(null);
  }

  function navigateToSection(section: AdminSection) {
    setActiveSection(section);
    const url = new URL(window.location.href);
    const route = section === "audit" ? "audit-logs" : section;
    if (url.searchParams.get("adminPage") !== route) {
      url.searchParams.set("adminPage", route);
      window.history.pushState({}, "", url.toString());
    }
  }

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
  const [pageSize, setPageSize] = useState<10 | 25 | 50 | 100>(25);
  const [totalUsers, setTotalUsers] = useState(0);
  const [directoryLoadedAt, setDirectoryLoadedAt] = useState<string | null>(null);
  const [target, setTarget] = useState<AdminUser | null>(null);
  const [nextRole, setNextRole] = useState<AdminManagedRole>("requester");
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const roleChangeInFlight = useRef(false);
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
  }, [activeSection, page, pageSize, debouncedQuery, roleFilter]);

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

  // Resolve the pending state before deriving visible results. Keeping this
  // declaration first avoids a temporal-dead-zone error during initial render.
  const directoryFilterPending = query.trim() !== debouncedQuery;
  const hasDirectoryFilters = query.trim().length > 0 || roleFilter !== "all";
  // Do not present results for the previous query while a new server-side search is pending.
  const filteredUsers = directoryFilterPending ? [] : users;

  function openRoleChange(user: AdminUser) {
    if (user.role === "admin") return;
    previousFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setTarget(user);
    setNextRole(user.role);
    setReason("");
    setError(null);
    setNotice(null);
    setRoleToast(null);
  }

  async function confirmRoleChange() {
    if (!target || saving || roleChangeInFlight.current || target.role === "admin") return;
    if (reason.trim().length < 10 || reason.trim().length > 500) {
      setRoleToast({ type: "error", message: "Enter an audit reason between 10 and 500 characters." });
      return;
    }
    if (nextRole === target.role) {
      setRoleToast({ type: "error", message: "Choose a different role before saving." });
      return;
    }
    roleChangeInFlight.current = true;
    setSaving(true);
    setError(null);
    setNotice(null);
    setRoleToast(null);
    try {
      // Re-read this exact account immediately before mutation. The dialog may
      // have remained open while another administrator changed the account.
      const latestPage = await getAdminUsersPage(100, 0, String(target.id), "all");
      const latestTarget = latestPage.users.find((user) => user.id === target.id);
      if (!latestTarget || latestTarget.role !== target.role) {
        setTarget(null);
        await refresh();
        setRoleToast({ type: "error", message: !latestTarget
          ? "This account is no longer available in the directory. Refresh the results and review the account before trying again."
          : "This account's role changed while the confirmation dialog was open. The requested change was not submitted. Review the refreshed account before trying again." });
        return;
      }
      const changed = await setAdminUserRole(target.id, nextRole, reason.trim());
      setRoleToast({ type: "success", message: changed.name + "'s role changed from " + displayRole(changed.old_role) + " to " + displayRole(changed.role) + ". The action was recorded in the audit log." });
      setTarget(null);
      setReason("");
      await refresh();
    } catch (cause) {
      setRoleToast({ type: "error", message: cause instanceof Error ? cause.message : "The role change was not saved." });
    } finally {
      roleChangeInFlight.current = false;
      setSaving(false);
    }
  }

  const dependencyProbes = dependencyHealth
    ? [
        ["Laravel backend", dependencyHealth.laravel_backend],
        ["Supabase Auth", dependencyHealth.supabase_auth],
        ["Supabase database", dependencyHealth.supabase_database],
      ] as const
    : [];
  const reachableDependencyCount = dependencyProbes.filter(([, probe]) => probe.status === "healthy").length;
  const ActiveSectionIcon = adminSections.find((section) => section.id === activeSection)?.icon ?? LayoutDashboard;

  return (
    <div className={"admin-shell" + (preferences.density === "compact" ? " admin-shell--compact" : "") + (preferences.reducedMotion ? " admin-shell--reduced-motion" : "") + (preferences.highContrast ? " admin-shell--high-contrast" : "")}>
      <a className="admin-skip-link" href="#admin-workspace-main">Skip to admin content</a>
      <div className="admin-toast-region" aria-atomic="true">
        {roleToast && (
          <div className={"admin-toast admin-toast--" + roleToast.type} role={roleToast.type === "error" ? "alert" : "status"} onMouseEnter={pauseRoleToastDismissal} onMouseLeave={resumeRoleToastDismissal} onFocusCapture={pauseRoleToastDismissal} onBlurCapture={resumeRoleToastDismissal}>
            <span>{roleToast.message}</span>
            <button type="button" aria-label="Dismiss notification" onClick={() => setRoleToast(null)}><X size={15} /></button>
          </div>
        )}
      </div>
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
              {index === 5 && <div className="admin-navigation__group-label admin-navigation__group-label--spaced">PREFERENCES &amp; SUPPORT</div>}
              <button type="button" className={"admin-nav-item" + (activeSection === id ? " admin-nav-item--active" : "")} aria-current={activeSection === id ? "page" : undefined} onClick={() => navigateToSection(id)}>
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

      <main className="admin-main" id="admin-workspace-main" tabIndex={-1}>
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
                <p>{activeSection === "overview" ? "A live overview of account governance and the dependencies currently checked by the portal." : activeSection === "users" ? "Review accounts and manage operational roles with an auditable reason." : activeSection === "security" ? "Review your current administrator session, authenticator protection, and the security controls verified by this branch." : activeSection === "analytics" ? "Review request volume, current status distribution, and assignment timing for a selected reporting period." : activeSection === "audit" ? "Search recorded administrative, authentication, incident, and responder activity in one read-only feed." : activeSection === "settings" ? "Personal display and accessibility preferences for this browser. These settings do not change TACResQ-wide policy." : activeSection === "help" ? "Guidance for using the Admin portal, understanding its limits, and finding the right workspace." : "Administrative workspace. Capabilities are shown only when their supporting backend controls are available."}</p>
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
            <article><span>MATCHING ACCOUNTS</span><strong>{directoryError ? "Unavailable" : loading && users.length === 0 ? "Loading" : totalUsers.toLocaleString()}</strong><small>Across all directory pages; follows the active search and role filters</small></article>
            <article><span>REQUESTERS · CURRENT PAGE</span><strong>{directoryError ? "Unavailable" : refreshing || directoryFilterPending ? "Updating" : users.filter((user) => user.role === "requester").length}</strong><small>Loaded page only; not a system-wide total</small></article>
            <article><span>FIELD UNITS · CURRENT PAGE</span><strong>{directoryError ? "Unavailable" : refreshing || directoryFilterPending ? "Updating" : users.filter((user) => user.role === "responder" || user.role === "driver").length}</strong><small>Responders and drivers on the loaded page only</small></article>
            <article><span>AUDIT HISTORY</span><strong>Activity feed</strong><small>Searchable read-only history across seven event sources</small></article>
          </section>
          <p id="admin-overview-metrics-context" className="admin-panel__footnote" hidden={activeSection !== "overview"}>Counts follow the current directory search and role filters. Requester and field-unit breakdowns describe only the currently loaded page of up to {pageSize} profiles; they are not system-wide role totals. Current directory page: {totalUsers === 0 ? 0 : page + 1} of {Math.max(1, Math.ceil(totalUsers / pageSize))}. Last successful directory retrieval: {directoryLoadedAt ? formatManilaDateTime(directoryLoadedAt) : "not yet retrieved"} (Asia/Manila). This is the retrieval time, not each profile’s last-updated time.</p>

          <section className="admin-panel admin-overview-shortcuts" aria-labelledby="admin-overview-shortcuts-title" hidden={activeSection !== "overview"}>
            <div className="admin-panel__heading">
              <div><p className="admin-eyebrow">WORKSPACE SHORTCUTS</p><h2 id="admin-overview-shortcuts-title">Go to a workspace</h2></div>
              <span className="admin-count">Quick access</span>
            </div>
            <div className="admin-overview-shortcuts__grid">
              <button type="button" onClick={() => navigateToSection("users")}><Users size={18} /><span><strong>User management</strong><small>Search accounts and review operational roles</small></span><span aria-hidden="true">→</span></button>
              <button type="button" onClick={() => navigateToSection("analytics")}><BarChart3 size={18} /><span><strong>Request reports</strong><small>Review incident volume and assignment timing</small></span><span aria-hidden="true">→</span></button>
              <button type="button" onClick={() => navigateToSection("audit")}><History size={18} /><span><strong>Activity log</strong><small>Inspect recorded administrative and operational events</small></span><span aria-hidden="true">→</span></button>
            </div>
          </section>

          <section className="admin-panel admin-health-panel" aria-labelledby="admin-health-title" aria-describedby="admin-health-limitations" aria-busy={dependencyHealthLoading} hidden={activeSection !== "overview"}>
            <div className="admin-panel__heading">
              <div><p className="admin-eyebrow">SERVICE REACHABILITY</p><h2 id="admin-health-title"><ShieldCheck size={19} /> Dependency reachability</h2></div>
              <span className="admin-count">{dependencyHealthLoading ? "Checking…" : dependencyHealth ? "Last checked " + formatManilaDateTime(dependencyHealth.checked_at) + " (Asia/Manila)" : "No verified result"}</span>
            </div>
            {dependencyHealthError ? (
              <div className="admin-health-unavailable" role="status">{dependencyHealthError}</div>
            ) : !dependencyHealth ? (
              <div className="admin-health-unavailable" role="status">{dependencyHealthLoading ? "Checking the three configured dependencies…" : "Waiting for the first diagnostic check…"}</div>
            ) : (
              <>
                <div className="admin-health-summary" role="status" aria-live="polite">
                  <strong>
                    {reachableDependencyCount === dependencyProbes.length
                      ? "All three dependencies responded within the probe threshold"
                      : reachableDependencyCount + " of " + dependencyProbes.length + " dependencies responded within the probe threshold"}
                  </strong>
                  <span>This summary covers only the three probes listed below, not complete emergency-service readiness.</span>
                </div>
                <div className="admin-health-grid">
                {dependencyProbes.map(([label, result]) => (
                  <article className="admin-health-item" key={label}>
                    <div><strong>{label}</strong><span className={"admin-health-status admin-health-status--" + result.status}>{result.status === "healthy" ? "reachable" : result.status === "slow" ? "timed out" : "unavailable"}</span></div>
                    <small>{result.duration_ms === null ? "Response time unavailable" : result.duration_ms + " ms response time"}</small>
                  </article>
                ))}
                </div>
              </>
            )}
            <p id="admin-health-limitations" className="admin-panel__footnote">Reachability only — not service readiness or emergency-operational status. Laravel /up confirms application liveness; Supabase Auth checks its health endpoint; the database probe executes a fixed, data-free RPC using the current Admin session. These checks do not verify incident creation, responder assignment, location accuracy, routing, push delivery, or end-to-end sign-in. “Timed out” means the probe exceeded five seconds; startup delay or network latency may contribute and does not by itself prove an outage. Refresh to retry.</p>
          </section>

          <section className="admin-panel" id="admin-user-management" aria-labelledby="admin-user-management-title" hidden={activeSection !== "users"}>
            <div className="admin-panel__heading">
              <div><p className="admin-eyebrow">IDENTITY &amp; ACCESS</p><h2 id="admin-user-management-title"><Users size={19} /> User management</h2></div>
              <span className="admin-count">{directoryError ? "Account results unavailable" : totalUsers === 0 ? "0 matching accounts" : (page * pageSize + 1) + "–" + Math.min((page + 1) * pageSize, totalUsers) + " of " + totalUsers + " matching"}</span>
            </div>
            <div className="admin-toolbar">
              <label className="admin-search"><Search size={16} aria-hidden="true" /><input id="admin-directory-search" type="search" value={query} onChange={(event) => setQuery(event.target.value)} maxLength={120} placeholder="Search name, email or profile ID" aria-label="Search users" aria-controls="admin-account-directory-table" aria-describedby="admin-directory-status" /></label>
              <label className="admin-role-filter"><span>Role</span><select id="admin-directory-role-filter" value={roleFilter} onChange={(event) => { setPage(0); setRoleFilter(event.target.value as RoleFilter); }} aria-label="Filter users by role" aria-controls="admin-account-directory-table" aria-describedby="admin-directory-status"><option value="all">All roles</option><option value="requester">Requester</option><option value="responder">Responder</option><option value="driver">Driver</option><option value="dispatcher">Dispatcher</option><option value="admin">Admin</option></select></label>
              <label className="admin-page-size"><span>Rows</span><select id="admin-directory-page-size" value={pageSize} onChange={(event) => { setPage(0); setPageSize(Number(event.target.value) as 10 | 25 | 50 | 100); }} aria-label="Accounts per page" aria-controls="admin-account-directory-table"><option value={10}>10</option><option value={25}>25</option><option value={50}>50</option><option value={100}>100</option></select></label>
              {hasDirectoryFilters && <button className="admin-filter-reset" type="button" onClick={() => { setQuery(""); setDebouncedQuery(""); setRoleFilter("all"); setPage(0); }} disabled={refreshing}>Clear filters</button>}
            </div>
            <p className="admin-directory-status" id="admin-directory-status" role="status" aria-live="polite">{directoryFilterPending ? "Applying search…" : refreshing ? "Updating account results…" : directoryError ? "Account results could not be retrieved. Use Refresh data to try again." : hasDirectoryFilters ? "Showing " + totalUsers.toLocaleString() + " matching accounts. Search and role filters are applied on the server." : "Showing all accounts. Search and role filters are applied on the server."}{directoryLoadedAt && !directoryError ? " Last successful retrieval: " + formatManilaDateTime(directoryLoadedAt) + " (Asia/Manila)." : ""}</p>
            <div className="admin-table-wrap" role="region" aria-label="Account results; horizontally scrollable" tabIndex={0} aria-busy={loading || refreshing || directoryFilterPending}>
              <table className="admin-table" id="admin-account-directory-table" aria-label="Administrator account directory" aria-describedby="admin-directory-status">
                <caption className="admin-table-caption">Account details and available role-management actions for the current directory page.</caption>
                <thead><tr><th scope="col">Account</th><th scope="col">Role</th><th scope="col">Duty status</th><th scope="col">Created</th><th scope="col">Action</th></tr></thead>
                <tbody>
                  {directoryFilterPending ? (
                    <tr><td colSpan={5}><div className="admin-empty" role="status" aria-live="polite"><LoaderCircle size={19} className="admin-spin" /> Applying search…</div></td></tr>
                  ) : loading && users.length === 0 ? (
                    <tr><td colSpan={5}><div className="admin-empty" role="status" aria-live="polite"><LoaderCircle size={19} className="admin-spin" /> Loading accounts from Supabase…</div></td></tr>
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
            <p className="admin-table-scroll-hint">On smaller screens, scroll the account table horizontally to view every column.</p>
            <nav className="admin-pagination" aria-label="User directory pagination" aria-controls="admin-account-directory-table"><span role="status" aria-live="polite">{totalUsers === 0 ? "Showing 0 accounts" : `Showing ${page * pageSize + 1}–${Math.min((page + 1) * pageSize, totalUsers)} of ${totalUsers.toLocaleString()} accounts`} <span aria-hidden="true">·</span> Page {totalUsers === 0 ? 0 : page + 1} of {Math.max(1, Math.ceil(totalUsers / pageSize))}</span><div><button type="button" aria-label="Previous page of accounts" onClick={() => setPage((current) => Math.max(0, current - 1))} disabled={page === 0 || refreshing || directoryFilterPending || Boolean(directoryError)}>Previous</button><button type="button" aria-label="Next page of accounts" onClick={() => setPage((current) => current + 1)} disabled={refreshing || directoryFilterPending || Boolean(directoryError) || (page + 1) * pageSize >= totalUsers}>Next</button></div></nav>
            <p className="admin-panel__footnote">Admin accounts cannot be created, promoted, demoted, or edited here. Initial administrator provisioning is a trusted-operator task. Role changes for field units are blocked while they are assigned to an active incident. Duty status describes a responder or driver’s operational availability; it is not an account-enabled, suspended, or sign-in status. This directory does not currently expose account suspension state.</p>
          </section>

          {activeSection === "audit" && <AuditLogsPage profile={profile} />}
          {activeSection === "analytics" && (
            <section className="admin-panel admin-analytics-panel" aria-labelledby="admin-analytics-title">
              <div className="admin-panel__heading">
                <div><p className="admin-eyebrow">INCIDENT OPERATIONS</p><h2 id="admin-analytics-title"><BarChart3 size={19} /> Request performance</h2></div>
                <div className="admin-analytics-controls">
                  <label className="admin-analytics-range"><span>Period (Manila time)</span><select value={analyticsDays} onChange={(event) => setAnalyticsDays(Number(event.target.value) as 7 | 30 | 90)} aria-label="Analytics reporting period"><option value={7}>Last 7 calendar days</option><option value={30}>Last 30 calendar days</option><option value={90}>Last 90 calendar days</option></select></label>
                  <button className="admin-refresh" type="button" onClick={() => { if (analytics) exportAnalyticsCsv(analytics); }} disabled={analyticsLoading || !analytics || Boolean(analyticsError)} aria-label="Export incident performance report as CSV"><Download size={15} /> Export CSV</button>
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
                      {analytics.daily_requests.length > 0 && (
                        <details className="admin-analytics-data">
                          <summary>View daily values</summary>
                          <div className="admin-analytics-data__table-wrap">
                            <table>
                              <caption>Daily request counts for the selected reporting period</caption>
                              <thead><tr><th scope="col">Date</th><th scope="col">Requests</th></tr></thead>
                              <tbody>{analytics.daily_requests.map((point) => (
                                <tr key={point.day}><th scope="row">{point.day}</th><td>{point.requests.toLocaleString()}</td></tr>
                              ))}</tbody>
                            </table>
                          </div>
                        </details>
                      )}
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
          {activeSection === "settings" && (
            <section className="admin-panel admin-settings-panel" aria-labelledby="admin-settings-title">
              <div className="admin-panel__heading">
                <div><p className="admin-eyebrow">YOUR WORKSPACE</p><h2 id="admin-settings-title"><Settings size={19} /> Display & accessibility</h2></div>
                <span className="admin-count">Saved on this device</span>
              </div>
              <p className="admin-settings-intro">These preferences affect only how the Admin portal appears in this browser. They do not change emergency notifications, user permissions, security policy, or other users’ settings.</p>
              <div className="admin-settings-grid">
                <article className="admin-settings-card">
                  <div className="admin-settings-card__heading"><div><h3>Interface density</h3><p>Choose how much information appears in tables and workspace panels.</p></div><details className="admin-context-help"><summary aria-label="About interface density"><CircleHelp size={16} /></summary><div>Compact mode reduces row and panel spacing. It does not hide fields or change which records are loaded.</div></details></div>
                  <label className="admin-settings-field"><span>Layout density</span><select value={preferences.density} onChange={(event) => setPreferences((current) => ({ ...current, density: event.target.value === "compact" ? "compact" : "comfortable" }))}><option value="comfortable">Comfortable</option><option value="compact">Compact</option></select></label>
                </article>
                <article className="admin-settings-card">
                  <div className="admin-settings-card__heading"><div><h3>Accessibility</h3><p>Adjust motion and contrast to make the interface easier to use.</p></div><details className="admin-context-help"><summary aria-label="About accessibility preferences"><CircleHelp size={16} /></summary><div>These preferences change this portal’s presentation only. They do not replace operating-system accessibility settings or a full accessibility review.</div></details></div>
                  <label className="admin-settings-toggle"><span><strong>Reduce motion</strong><small>Limit decorative transitions and animations.</small></span><input type="checkbox" checked={preferences.reducedMotion} onChange={(event) => setPreferences((current) => ({ ...current, reducedMotion: event.target.checked }))} /></label>
                  <label className="admin-settings-toggle"><span><strong>High contrast</strong><small>Strengthen borders and text contrast across Admin panels.</small></span><input type="checkbox" checked={preferences.highContrast} onChange={(event) => setPreferences((current) => ({ ...current, highContrast: event.target.checked }))} /></label>
                  <label className="admin-settings-toggle"><span><strong>Contextual help</strong><small>Show short explanations beside settings and complex controls.</small></span><input type="checkbox" checked={preferences.contextualHelp} onChange={(event) => setPreferences((current) => ({ ...current, contextualHelp: event.target.checked }))} /></label>
                </article>
              </div>
              {preferences.contextualHelp && <div className="admin-help-note"><CircleHelp size={17} /><p><strong>About these preferences</strong> Changes preview immediately. Select Save preferences to keep them in this browser. Reset restores the default display without changing your account or server data.</p></div>}
              {preferencesError && <p className="admin-settings-error" role="alert">{preferencesError}</p>}
              <div className="admin-settings-actions">
                <button className="admin-refresh" type="button" onClick={resetPreferences} disabled={!preferencesDirty}><RotateCcw size={15} /> Reset defaults</button>
                <button className="admin-settings-save" type="button" onClick={savePreferences} disabled={!preferencesDirty}><Save size={15} /> Save preferences</button>
              </div>
              <div className="admin-settings-subsection">
                <p className="admin-eyebrow">PRIVACY & DATA</p>
                <h3>Privacy information</h3>
                <p>The Admin portal handles account and operational information. The approved TACResQ privacy notice, controller contact, and retention schedule must be maintained by the project owner before this page can publish legal commitments or offer data-rights actions.</p>
                <div className="admin-settings-status"><span>Privacy notice</span><strong>Not published in this Admin workspace</strong></div>
                <div className="admin-settings-status"><span>Data requests</span><strong>Not available as an online workflow</strong></div>
                <p className="admin-panel__footnote">No deletion, export-of-personal-data, or retention controls are provided here. Do not treat the aggregate Reports CSV as a personal-data request response.</p>
              </div>
              <div className="admin-settings-subsection">
                <p className="admin-eyebrow">NOTIFICATIONS</p>
                <h3>Delivery preferences</h3>
                <p>Emergency notification preferences are not configurable from this Admin portal. This section intentionally provides no quiet-hours or suppression toggle, because no verified preference-enforcement workflow is connected here.</p>
                <div className="admin-settings-status"><span>Preference controls</span><strong>Unavailable — no verified settings service</strong></div>
              </div>
            </section>
          )}
          {activeSection === "help" && (
            <section className="admin-panel admin-help-panel" aria-labelledby="admin-help-title">
              <div className="admin-panel__heading">
                <div><p className="admin-eyebrow">SUPPORT & GUIDANCE</p><h2 id="admin-help-title"><BookOpen size={19} /> Admin help centre</h2></div>
                <span className="admin-count">Operator guidance</span>
              </div>
              <div className="admin-help-grid">
                <article className="admin-help-card"><h3>Overview & service reachability</h3><p>The Overview probes Laravel, Supabase Auth, and the database. A reachable dependency does not prove that incident creation, assignment, mapping, push delivery, or the full emergency workflow works.</p><button type="button" onClick={() => navigateToSection("overview")}>Open Overview <span aria-hidden="true">→</span></button></article>
                <article className="admin-help-card"><h3>User management</h3><p>Search and filter accounts, then change eligible operational roles with a required reason. Admin accounts are operator-managed. Duty status is not an account suspension or sign-in state.</p><button type="button" onClick={() => navigateToSection("users")}>Open User management <span aria-hidden="true">→</span></button></article>
                <article className="admin-help-card"><h3>Security & authenticator</h3><p>Use a verified authenticator for the required AAL2 session. Keep the only verified factor available. The current Security page is not a complete security-event monitoring centre.</p><button type="button" onClick={() => navigateToSection("security")}>Open Security <span aria-hidden="true">→</span></button></article>
                <article className="admin-help-card"><h3>Reports & Activity Log</h3><p>Reports contain aggregate request metrics. Activity Log contains only events collected by its connected sources; missing sources must not be interpreted as zero activity.</p><div><button type="button" onClick={() => navigateToSection("analytics")}>Open Reports <span aria-hidden="true">→</span></button><button type="button" onClick={() => navigateToSection("audit")}>Open Activity Log <span aria-hidden="true">→</span></button></div></article>
              </div>
              <div className="admin-settings-subsection">
                <p className="admin-eyebrow">ABOUT TACRESQ ADMIN</p>
                <h3>Privileged workspace</h3>
                <p>This portal is intended for authorized administration of TACResQ accounts, security status, aggregate reporting, and recorded activity. It is not a dispatch console and does not grant responder or driver permissions.</p>
                <div className="admin-settings-status"><span>Current workspace</span><strong>Admin web portal</strong></div>
                <div className="admin-settings-status"><span>Privacy-safe reporting</span><strong>Aggregate data only</strong></div>
                <div className="admin-settings-status"><span>Support contact</span><strong>Not configured in this build</strong></div>
              </div>
              <p className="admin-panel__footnote">For operational issues, follow the project’s approved support and incident-response procedure. Do not send passwords, authenticator setup keys, recovery material, or access tokens through support channels.</p>
            </section>
          )}
          {activeSection === "security" && (
            <>
            <section className="admin-panel admin-roadmap-panel">
              <div className="admin-panel__heading">
                <div><p className="admin-eyebrow">IDENTITY &amp; ACCESS</p><h2><ShieldCheck size={19} /> Security overview</h2></div>
                <span className="admin-count">Branch policy · deployment unverified</span>
              </div>
              <div className="admin-roadmap-content">
                <strong>Administrator access requires Google Authenticator TOTP.</strong>
                <p>In this branch, the Supabase Auth flow supports a six-digit Google Authenticator TOTP factor, and protected Admin RPCs and audit-log reads check for an AAL2 session with a TOTP authentication method. This describes the repository implementation only; deployment and live-project enforcement have not been independently verified. No SMS or email OTP is configured by this flow. Supabase Auth does not currently provide app-generated recovery codes, so keep your authenticator available and do not remove your only verified factor.</p>
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
          <section className="admin-modal" role="dialog" aria-modal="true" aria-labelledby="admin-role-dialog-title" aria-describedby="admin-role-dialog-description admin-role-dialog-guidance">
            <button className="admin-modal__close" type="button" aria-label="Close role change" onClick={() => setTarget(null)} disabled={saving}><X size={18} /></button>
            <div className="admin-modal__icon"><ShieldCheck size={22} /></div>
            <p className="admin-eyebrow">PRIVILEGED ACTION</p>
            <h2 id="admin-role-dialog-title">Change account role</h2>
            <p className="admin-modal__description" id="admin-role-dialog-description">You are changing access for <strong>{target.name}</strong> ({target.email}). Review the role transition carefully. The database enforces authorization and records the reason in the audit log.</p>
            <div className="admin-role-transition" aria-live="polite" aria-atomic="true"><div><span>Current role</span><strong>{displayRole(target.role)}</strong></div><span className="admin-role-transition__arrow" aria-hidden="true">→</span><div><span>New role</span><strong>{displayRole(nextRole)}</strong></div></div>
            <p className="admin-role-warning" id="admin-role-dialog-guidance">Changing a role changes the account’s permissions in TACResQ. Confirm the intended access level with the responsible operator before saving.</p>
            <label className="admin-form-label" htmlFor="admin-new-role">New role</label>
            <select id="admin-new-role" value={nextRole} onChange={(event) => setNextRole(event.target.value as AdminManagedRole)} disabled={saving}>
              {roleOptions.map((role) => <option key={role} value={role}>{displayRole(role)}</option>)}
            </select>
            <label className="admin-form-label" htmlFor="admin-change-reason">Reason for change <span>Required</span></label>
            <p className="admin-form-hint" id="admin-change-reason-guidance">Explain why this access change is necessary. Enter 10–500 characters; surrounding whitespace is ignored.</p>
            <textarea id="admin-change-reason" value={reason} onChange={(event) => setReason(event.target.value)} minLength={10} maxLength={500} rows={4} placeholder="Record the operational reason for this role change." aria-describedby="admin-change-reason-guidance admin-reason-count" disabled={saving} />
            <div className="admin-reason-count" id="admin-reason-count">{reason.trim().length}/500 characters · minimum 10</div>
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
