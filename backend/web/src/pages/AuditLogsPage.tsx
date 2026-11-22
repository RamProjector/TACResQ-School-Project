import { Fragment, useEffect, useState } from "react";
import { AlertCircle, ChevronDown, ChevronLeft, ChevronRight, Clock3, LoaderCircle, RefreshCw, Search } from "lucide-react";
import { getAdminAuditLogsPage } from "@/lib/supabase";
import type { AdminAuditEvent, TacruProfile } from "@/lib/supabase";

type AuditAction = "all" | "user_role_changed";
type AuditLogsPageProps = { profile: TacruProfile };

const PAGE_SIZE = 25;

function formatDate(value: string | null | undefined) {
  if (!value) return "Time unavailable";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Time unavailable";
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "medium" }).format(date);
}

function startOfDay(value: string) {
  return value ? new Date(value + "T00:00:00").toISOString() : null;
}

function startOfNextDay(value: string) {
  if (!value) return null;
  const date = new Date(value + "T00:00:00");
  date.setDate(date.getDate() + 1);
  return date.toISOString();
}

function displayRole(value: string | null | undefined) {
  if (!value) return "Unknown";
  return value.charAt(0).toUpperCase() + value.slice(1);
}

export default function AuditLogsPage({ profile }: AuditLogsPageProps) {
  const [events, setEvents] = useState<AdminAuditEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState("");
  const [action, setAction] = useState<AuditAction>("all");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<number | null>(null);
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      setLoading(true);
      setError(null);
      const fromValue = startOfDay(from);
      const toValue = startOfNextDay(to);
      void getAdminAuditLogsPage(
        PAGE_SIZE,
        page * PAGE_SIZE,
        query,
        action,
        fromValue,
        toValue,
      ).then((result) => {
        if (!active) return;
        setEvents(result.events);
        setTotal(result.total_count);
        setExpanded(null);
      }).catch((cause: unknown) => {
        if (!active) return;
        setEvents([]);
        setTotal(0);
        setError(cause instanceof Error ? cause.message : "Audit history could not be loaded.");
      }).finally(() => {
        if (active) setLoading(false);
      });
    }, query.trim() ? 250 : 0);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [page, query, action, from, to, refreshVersion]);

  function resetFilters() {
    setQuery("");
    setAction("all");
    setFrom("");
    setTo("");
    setPage(0);
  }

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <section className="audit-page" aria-labelledby="audit-page-title">
      <div className="audit-page__intro">
        <div>
          <p className="admin-eyebrow">ACCOUNTABILITY · READ-ONLY RECORDS</p>
          <h2 id="audit-page-title"><Clock3 size={20} /> Audit history</h2>
          <p>Search the full administrative audit history. Records are written by protected database operations and cannot be edited or deleted here.</p>
        </div>
        <button className="admin-refresh" type="button" onClick={() => {
          setLoading(true);
          setPage((current) => current);
          // A same-page refresh is handled by changing a dedicated refresh counter below.
          setRefreshVersion((current) => current + 1);
        }} disabled={loading}>
          <RefreshCw size={15} /> Refresh
        </button>
      </div>

      <div className="audit-page__filters">
        <label className="admin-search audit-page__search">
          <Search size={16} />
          <input type="search" value={query} onChange={(event) => { setPage(0); setQuery(event.target.value); }} maxLength={120} placeholder="Search actor, target ID, action, role, reason or timestamp" aria-label="Search all audit logs" />
        </label>
        <label><span>Action</span><select value={action} onChange={(event) => { setPage(0); setAction(event.target.value as AuditAction); }}><option value="all">All actions</option><option value="user_role_changed">User role changed</option></select></label>
        <label><span>From</span><input type="date" value={from} max={to || undefined} onChange={(event) => { setPage(0); setFrom(event.target.value); }} /></label>
        <label><span>To</span><input type="date" value={to} min={from || undefined} onChange={(event) => { setPage(0); setTo(event.target.value); }} /></label>
        <button className="audit-page__reset" type="button" onClick={resetFilters} disabled={!query && action === "all" && !from && !to}>Clear filters</button>
      </div>

      {error && <div className="admin-banner admin-banner--error" role="alert"><AlertCircle size={17} /><span>{error}</span></div>}

      <div className="audit-page__summary">
        <span>{loading ? "Loading audit records…" : total.toLocaleString() + " matching records"}</span>
        <span>Page {total === 0 ? 0 : page + 1} of {pageCount}</span>
      </div>

      <div className="admin-table-wrap audit-page__table-wrap">
        <table className="admin-table admin-audit-table audit-page__table">
          <thead><tr><th>Recorded</th><th>Actor</th><th>Target</th><th>Action</th><th>Role change</th><th>Details</th></tr></thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={6}><div className="admin-empty"><LoaderCircle size={19} className="admin-spin" /> Loading records from Supabase…</div></td></tr>
            ) : events.length === 0 ? (
              <tr><td colSpan={6}><div className="admin-empty"><Clock3 size={20} /><strong>{error ? "Audit records unavailable" : "No matching audit records"}</strong><span>{error ? "The page does not substitute sample data." : "Adjust your filters or check again later."}</span></div></td></tr>
            ) : events.map((event) => (
              <Fragment key={event.id}>
              <tr>
                <td className="admin-date">{formatDate(event.created_at)}</td>
                <td><strong>{event.actor_user_id === profile.id ? "You" : event.actor_identity || "Unknown actor"}</strong><small className="audit-page__subtext">{event.actor_user_id === null ? "No linked profile" : "Profile #" + event.actor_user_id}</small></td>
                <td>{event.target_user_id === null ? "Unknown / removed" : "Profile #" + event.target_user_id}</td>
                <td><span className="admin-role">{event.action.replaceAll("_", " ")}</span></td>
                <td><span className="admin-audit-change">{displayRole(event.old_role)} <span>→</span> {displayRole(event.new_role)}</span></td>
                <td><button className="audit-page__details" type="button" aria-expanded={expanded === event.id} onClick={() => setExpanded(expanded === event.id ? null : event.id)}>{expanded === event.id ? "Hide" : "View"} <ChevronDown size={14} /></button></td>
              </tr>
              {expanded === event.id && (
                <tr key={event.id + "-details"}><td className="audit-page__expanded" colSpan={6}>
                  <strong>Recorded reason</strong>
                  <p>{event.reason || "No reason was stored."}</p>
                  <dl><div><dt>Audit record</dt><dd>#{event.id}</dd></div><div><dt>Actor profile</dt><dd>{event.actor_user_id ?? "Not linked"}</dd></div><div><dt>Target profile</dt><dd>{event.target_user_id ?? "Not linked"}</dd></div><div><dt>Exact timestamp</dt><dd>{event.created_at}</dd></div></dl>
                </td></tr>
              )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>

      <div className="admin-pagination audit-page__pagination" aria-label="Audit history pagination">
        <span>Showing {total === 0 ? 0 : page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, total)} of {total.toLocaleString()}</span>
        <div><button type="button" onClick={() => setPage((current) => Math.max(0, current - 1))} disabled={page === 0 || loading}><ChevronLeft size={14} /> Previous</button><button type="button" onClick={() => setPage((current) => current + 1)} disabled={loading || (page + 1) * PAGE_SIZE >= total}>Next <ChevronRight size={14} /></button></div>
      </div>
      <p className="admin-panel__footnote">Audit records are retrieved through a server-side, TOTP-protected RPC. Search, action filtering, date filtering and pagination are applied in the database, not limited to the records visible on this page. This interface is read-only.</p>
    </section>
  );
}
