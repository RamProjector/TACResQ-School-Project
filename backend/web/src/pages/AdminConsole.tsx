import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertCircle,
  ArrowDownUp,
  CheckCircle2,
  Clock3,
  LoaderCircle,
  LogOut,
  RefreshCw,
  Search,
  ShieldCheck,
  Users,
  X,
} from "lucide-react";
import { Brand } from "@/components/Brand";
import {
  getAdminAuditLogs,
  getAdminUsers,
  setAdminUserRole,
} from "@/lib/supabase";
import type {
  AdminAuditEvent,
  AdminManagedRole,
  AdminUser,
  TacruProfile,
} from "@/lib/supabase";
import "./AdminConsole.css";

type AdminConsoleProps = {
  profile: TacruProfile;
  onSignOut: () => void;
};

type RoleFilter = "all" | AdminManagedRole | "admin";
const roleOptions: AdminManagedRole[] = ["requester", "responder", "driver", "dispatcher"];

function displayRole(role: string) {
  return role.charAt(0).toUpperCase() + role.slice(1);
}

function formatDate(value: string | null | undefined) {
  if (!value) return "Time unavailable";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Time unavailable";
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function initials(name: string) {
  return name.trim().split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("") || "A";
}

export default function AdminConsole({ profile, onSignOut }: AdminConsoleProps) {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [audit, setAudit] = useState<AdminAuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [roleFilter, setRoleFilter] = useState<RoleFilter>("all");
  const [target, setTarget] = useState<AdminUser | null>(null);
  const [nextRole, setNextRole] = useState<AdminManagedRole>("requester");
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const modalRoot = useRef<HTMLDivElement>(null);

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
    setRefreshing(true);
    setError(null);
    try {
      const [nextUsers, nextAudit] = await Promise.all([getAdminUsers(), getAdminAuditLogs(100)]);
      setUsers(nextUsers);
      setAudit(nextAudit);
    } catch (cause) {
      // Never keep privileged data visible after an authorization or connection failure.
      setUsers([]);
      setAudit([]);
      setError(cause instanceof Error ? cause.message : "The Admin service could not load its data.");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  const filteredUsers = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return users.filter((user) => {
      if (roleFilter !== "all" && user.role !== roleFilter) return false;
      if (!normalized) return true;
      return [user.name, user.email, user.phone, user.id, user.role]
        .filter((value) => value !== null && value !== undefined)
        .join(" ").toLocaleLowerCase().includes(normalized);
    });
  }, [users, query, roleFilter]);

  const userById = useMemo(() => new Map<number, AdminUser>(users.map((user) => [user.id, user] as const)), [users]);

  function openRoleChange(user: AdminUser) {
    if (user.role === "admin") return;
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

  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <Brand inverse />
        <div className="admin-sidebar__label">SECURITY &amp; GOVERNANCE</div>
        <div className="admin-nav-item admin-nav-item--active"><ShieldCheck size={17} /><span>Admin console</span></div>
        <div className="admin-sidebar__bottom">
          <div className="admin-security-note"><ShieldCheck size={16} /><p>Role changes require a reason and are recorded by the database.</p></div>
          <div className="admin-sidebar__version">TACRESQ · PRIVILEGED ACCESS</div>
        </div>
      </aside>

      <main className="admin-main">
        <header className="admin-topbar">
          <div className="admin-breadcrumb"><span>TACRESQ</span><b>/</b><strong>ADMIN</strong></div>
          <div className="admin-topbar__right">
            <span className="admin-access-pill"><ShieldCheck size={14} /> ADMIN ACCESS</span>
            <div className="admin-user">
              <span className="admin-avatar">{initials(profile.name)}</span>
              <span><strong>{profile.name}</strong><small>Administrator</small></span>
            </div>
            <button className="admin-signout" type="button" onClick={onSignOut}><LogOut size={16} /><span>Sign out</span></button>
          </div>
        </header>

        <div className="admin-workspace">
          <div className="admin-heading">
            <div>
              <p className="admin-eyebrow">ACCESS CONTROL <span>·</span> AUDITABLE OPERATIONS</p>
              <h1>Admin console<span>.</span></h1>
              <p>Manage operational roles and review recorded administrative changes.</p>
            </div>
            <button className="admin-refresh" type="button" onClick={() => { void refresh(); }} disabled={refreshing}>
              <RefreshCw size={16} className={refreshing ? "admin-spin" : ""} /> Refresh data
            </button>
          </div>

          {(error || notice) && (
            <div className={error ? "admin-banner admin-banner--error" : "admin-banner admin-banner--success"} role={error ? "alert" : "status"}>
              {error ? <AlertCircle size={17} /> : <CheckCircle2 size={17} />}
              <span>{error || notice}</span>
              <button type="button" aria-label="Dismiss message" onClick={() => { setError(null); setNotice(null); }}><X size={15} /></button>
            </div>
          )}

          <section className="admin-metrics" aria-label="Account summary">
            <article><span>REGISTERED PROFILES</span><strong>{users.length}</strong><small>Profiles returned by the protected Admin API</small></article>
            <article><span>REQUESTERS</span><strong>{users.filter((user) => user.role === "requester").length}</strong><small>Accounts allowed to submit requests</small></article>
            <article><span>FIELD UNITS</span><strong>{users.filter((user) => user.role === "responder" || user.role === "driver").length}</strong><small>Responder and driver accounts</small></article>
            <article><span>RECENT AUDIT EVENTS</span><strong>{audit.length}</strong><small>Latest records, maximum 100 loaded</small></article>
          </section>

          <section className="admin-panel">
            <div className="admin-panel__heading">
              <div><p className="admin-eyebrow">IDENTITY &amp; ACCESS</p><h2><Users size={19} /> User management</h2></div>
              <span className="admin-count">{filteredUsers.length} of {users.length}</span>
            </div>
            <div className="admin-toolbar">
              <label className="admin-search"><Search size={16} /><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search name, email, phone or ID" aria-label="Search users" /></label>
              <label className="admin-role-filter"><span>Role</span><select value={roleFilter} onChange={(event) => setRoleFilter(event.target.value as RoleFilter)} aria-label="Filter users by role"><option value="all">All roles</option><option value="requester">Requester</option><option value="responder">Responder</option><option value="driver">Driver</option><option value="dispatcher">Dispatcher</option><option value="admin">Admin</option></select></label>
            </div>
            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead><tr><th>Account</th><th>Role</th><th>Duty status</th><th>Created</th><th>Action</th></tr></thead>
                <tbody>
                  {loading && users.length === 0 ? (
                    <tr><td colSpan={5}><div className="admin-empty"><LoaderCircle size={19} className="admin-spin" /> Loading accounts from Supabase…</div></td></tr>
                  ) : filteredUsers.length === 0 ? (
                    <tr><td colSpan={5}><div className="admin-empty"><Users size={20} /><strong>{error ? "User data is unavailable" : "No accounts match this filter"}</strong><span>{error ? "The page does not substitute demo accounts for live data." : "Try another search or role filter."}</span></div></td></tr>
                  ) : filteredUsers.map((user) => (
                    <tr key={user.id}>
                      <td><div className="admin-account"><span className="admin-account__avatar">{initials(user.name)}</span><span><strong>{user.name}</strong><small>{user.email}{user.phone ? " · " + user.phone : ""}</small><small className="admin-account__id">Profile #{user.id}</small></span></div></td>
                      <td><span className={"admin-role admin-role--" + user.role}>{displayRole(user.role)}</span></td>
                      <td><span className="admin-duty">{user.duty_status ? user.duty_status.replaceAll("_", " ") : "Not applicable"}</span></td>
                      <td className="admin-date">{formatDate(user.created_at)}</td>
                      <td>{user.role === "admin" ? <span className="admin-provisioned">Operator managed</span> : <button className="admin-change-role" type="button" onClick={() => openRoleChange(user)}><ArrowDownUp size={14} /> Change role</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="admin-panel__footnote">Admin accounts cannot be created, promoted, demoted, or edited here. Initial administrator provisioning is a trusted-operator task. Role changes for field units are blocked while they are assigned to an active incident.</p>
          </section>

          <section className="admin-panel admin-audit-panel">
            <div className="admin-panel__heading">
              <div><p className="admin-eyebrow">ACCOUNTABILITY</p><h2><Clock3 size={19} /> Administrative audit log</h2></div>
              <span className="admin-count">Newest first · max 100</span>
            </div>
            <div className="admin-table-wrap">
              <table className="admin-table admin-audit-table">
                <thead><tr><th>Recorded</th><th>Actor</th><th>Target</th><th>Change</th><th>Reason</th></tr></thead>
                <tbody>
                  {loading && audit.length === 0 ? (
                    <tr><td colSpan={5}><div className="admin-empty"><LoaderCircle size={19} className="admin-spin" /> Loading audit events…</div></td></tr>
                  ) : audit.length === 0 ? (
                    <tr><td colSpan={5}><div className="admin-empty"><Clock3 size={20} /><strong>No administrative changes recorded yet</strong><span>Events appear after a role change succeeds.</span></div></td></tr>
                  ) : audit.map((event) => (
                    <tr key={event.id}>
                      <td className="admin-date">{formatDate(event.created_at)}</td>
                      <td>{event.actor_user_id === profile.id ? "You" : (userById.get(event.actor_user_id ?? -1)?.name ?? (event.actor_user_id ? "Profile #" + event.actor_user_id : "Unknown / removed"))}</td>
                      <td>{userById.get(event.target_user_id ?? -1)?.name ?? (event.target_user_id ? "Profile #" + event.target_user_id : "Unknown / removed")}</td>
                      <td><span className="admin-audit-change">{displayRole(event.old_role)} <span>→</span> {displayRole(event.new_role)}</span></td>
                      <td className="admin-audit-reason">{event.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="admin-panel__footnote">The client has no direct insert, update, or delete permission on audit rows. The role-change RPC writes the event in the same database transaction as the change.</p>
          </section>
          <footer className="admin-footer"><span>TACRESQ · PRIVILEGED ACCESS</span><span>Server-enforced authorization · No client-side role grants</span></footer>
        </div>
      </main>

      {target && (
        <div className="admin-modal-scrim" ref={modalRoot} role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !saving) setTarget(null); }}>
          <section className="admin-modal" role="dialog" aria-modal="true" aria-labelledby="admin-role-dialog-title">
            <button className="admin-modal__close" type="button" aria-label="Close role change" onClick={() => setTarget(null)} disabled={saving}><X size={18} /></button>
            <div className="admin-modal__icon"><ShieldCheck size={22} /></div>
            <p className="admin-eyebrow">PRIVILEGED ACTION</p>
            <h2 id="admin-role-dialog-title">Change account role</h2>
            <p className="admin-modal__description">You are changing access for <strong>{target.name}</strong> ({target.email}). This action will be written to the audit log.</p>
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
                {saving ? <><LoaderCircle size={16} className="admin-spin" /> Saving…</> : <><ShieldCheck size={16} /> Confirm role change</>}
              </button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
