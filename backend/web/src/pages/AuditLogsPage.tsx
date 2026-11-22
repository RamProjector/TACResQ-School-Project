import { Fragment, useEffect, useState } from "react";
import { AlertCircle, Check, ChevronDown, ChevronLeft, ChevronRight, Clock3, Copy, LoaderCircle, RefreshCw, Search } from "lucide-react";
import { getAdminInvestigationEventsPage } from "@/lib/supabase";
import type { AdminInvestigationCategory, AdminInvestigationEvent, AdminInvestigationSource, TacruProfile } from "@/lib/supabase";

type AuditLogsPageProps = { profile: TacruProfile };

const PAGE_SIZE = 25;

const categoryLabels: Record<Exclude<AdminInvestigationCategory, "all">, string> = {
  administrative: "Administrative",
  authentication: "Authentication",
  incident: "Incident",
  assignment: "Assignment",
};

const sourceLabels: Record<Exclude<AdminInvestigationSource, "all">, string> = {
  admin_audit_logs: "Admin role changes",
  supabase_auth_audit_logs: "Supabase Auth",
  incident_status_logs: "Incident status",
  incident_claimability_logs: "Incident claiming",
  incident_request_edit_logs: "Requester edits",
  incident_assignment_events: "Responder assignments",
};

function formatDate(value: string | null | undefined) {
  if (!value) return "Time unavailable";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Time unavailable";
  return new Intl.DateTimeFormat(undefined, {
    timeZone: "Asia/Manila",
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(date);
}

// Audit date filters use the fixed TACResQ reporting zone, independent of the
// administrator's device timezone.
function startOfDay(value: string) {
  return value ? value + "T00:00:00+08:00" : null;
}

function startOfNextDay(value: string) {
  if (!value) return null;
  const date = new Date(value + "T00:00:00Z");
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10) + "T00:00:00+08:00";
}

function formatEventType(value: string) {
  return value.replaceAll("_", " ");
}

function formatDetailLabel(value: string) {
  return value.replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatDetailValue(value: unknown) {
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "string" || typeof value === "number") return String(value);
  if (Array.isArray(value)) {
    const labels = value.filter((item): item is string => typeof item === "string");
    return labels.length ? labels.map(formatDetailLabel).join(", ") : "None";
  }
  return "Not available";
}

function actorDisplay(event: AdminInvestigationEvent, profile: TacruProfile) {
  return event.actor_user_id === profile.id ? "You" : event.actor_label || "Unknown actor";
}

export default function AuditLogsPage({ profile }: AuditLogsPageProps) {
  const [events, setEvents] = useState<AdminInvestigationEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<AdminInvestigationCategory>("all");
  const [source, setSource] = useState<AdminInvestigationSource>("all");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [actorId, setActorId] = useState("");
  const [targetId, setTargetId] = useState("");
  const [incidentId, setIncidentId] = useState("");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [lastSuccessfulRefresh, setLastSuccessfulRefresh] = useState<string | null>(null);
  const [copiedEventId, setCopiedEventId] = useState<string | null>(null);
  const [copyError, setCopyError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      setLoading(true);
      setError(null);
      const fromValue = startOfDay(from);
      const toValue = startOfNextDay(to);
      const actorFilter = actorId.trim() ? Number(actorId) : null;
      const targetFilter = targetId.trim() ? Number(targetId) : null;
      const incidentFilter = incidentId.trim() ? Number(incidentId) : null;
      const numericFilters = [actorFilter, targetFilter, incidentFilter];
      if (numericFilters.some((value) => value !== null && (!Number.isSafeInteger(value) || value < 1))) {
        setEvents([]);
        setTotal(0);
        setError("Profile and incident ID filters must be positive whole numbers.");
        setLoading(false);
        return;
      }
      void getAdminInvestigationEventsPage(
        PAGE_SIZE,
        page * PAGE_SIZE,
        query,
        category,
        source,
        fromValue,
        toValue,
        actorFilter,
        targetFilter,
        incidentFilter,
      ).then((result) => {
        if (!active) return;
        if (page > 0 && page * PAGE_SIZE >= result.total_count) {
          setEvents([]);
          setTotal(result.total_count);
          setExpanded(null);
          setPage(Math.max(0, Math.ceil(result.total_count / PAGE_SIZE) - 1));
          return;
        }
        setEvents(result.events);
        setTotal(result.total_count);
        setExpanded(null);
        setLastSuccessfulRefresh(new Date().toISOString());
      }).catch((cause: unknown) => {
        if (!active) return;
        setEvents([]);
        setTotal(0);
        setError(cause instanceof Error ? cause.message : "Investigation history could not be loaded.");
      }).finally(() => {
        if (active) setLoading(false);
      });
    }, query.trim() ? 250 : 0);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [page, query, category, source, from, to, actorId, targetId, incidentId, refreshVersion]);

  async function copyEventId(eventId: string) {
    setCopyError(null);
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard access is unavailable in this browser context.");
      await navigator.clipboard.writeText(eventId);
      setCopiedEventId(eventId);
    } catch {
      setCopiedEventId(null);
      setCopyError("Could not copy the event ID. You can select the ID text and copy it manually.");
    }
  }

  function resetFilters() {
    setQuery("");
    setCategory("all");
    setSource("all");
    setFrom("");
    setTo("");
    setActorId("");
    setTargetId("");
    setIncidentId("");
    setPage(0);
  }

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const hasFilters = Boolean(query || category !== "all" || source !== "all" || from || to || actorId || targetId || incidentId);

  return (
    <section className="audit-page" aria-labelledby="audit-page-title">
      <div className="audit-page__intro">
        <div>
          <p className="admin-eyebrow">ACCOUNTABILITY · READ-ONLY INVESTIGATION</p>
          <h2 id="audit-page-title"><Clock3 size={20} /> Audit history</h2>
          <p>Review recorded administrative, authentication, incident-status, and responder-assignment events. Each entry retains its original source and event meaning. This page does not replace operational incident records.</p>
        </div>
        <button className="admin-refresh" type="button" onClick={() => {
          setLoading(true);
          setRefreshVersion((current) => current + 1);
        }} disabled={loading}>
          <RefreshCw size={15} className={loading ? "admin-spin" : undefined} /> {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      <div className="audit-page__coverage" role="note">
        <strong>Coverage boundaries</strong>
        <span>This feed includes six existing event sources. Supabase Auth events appear only when Postgres Auth Audit Log storage is active. Denied privileged RPC attempts, device-security events, and direct-client API/Storage activity are not included yet.</span>
      </div>

      <details className="audit-page__coverage-details">
        <summary>Review included sources and known gaps</summary>
        <div className="audit-page__coverage-grid">
          <section aria-labelledby="audit-sources-included">
            <h3 id="audit-sources-included">Included event sources</h3>
            <ul>
              <li><strong>Admin role changes</strong><span>Role-change records and recorded reasons.</span></li>
              <li><strong>Supabase Auth</strong><span>Provider events only when Auth Audit Logs Postgres storage is enabled and populated.</span></li>
              <li><strong>Incident status</strong><span>Recorded status outcomes; not necessarily a complete before-and-after transition.</span></li>
              <li><strong>Incident claiming</strong><span>Dispatcher claimability history.</span></li>
              <li><strong>Requester edits</strong><span>Changed-field names only; private snapshots are excluded.</span></li>
              <li><strong>Responder assignments</strong><span>Assignment, release, and team-closure events.</span></li>
            </ul>
          </section>
          <section aria-labelledby="audit-sources-gaps">
            <h3 id="audit-sources-gaps">Not covered by this feed</h3>
            <ul>
              <li><strong>Denied privileged RPC attempts</strong><span>No trusted, durable application event source is integrated yet.</span></li>
              <li><strong>Device-security events</strong><span>Trusted server-side event writers are not implemented.</span></li>
              <li><strong>Direct-client API and Storage activity</strong><span>Laravel telemetry does not cover direct Android or browser Supabase calls.</span></li>
            </ul>
          </section>
        </div>
      </details>

      <div className="audit-page__filters">
        <label className="admin-search audit-page__search">
          <Search size={16} />
          <input type="search" value={query} onChange={(event) => { setPage(0); setQuery(event.target.value); }} maxLength={120} placeholder="Search actor, target, incident, event or details" aria-label="Search investigation history" />
        </label>
        <label><span>Category</span><select value={category} onChange={(event) => { setPage(0); setCategory(event.target.value as AdminInvestigationCategory); }}><option value="all">All categories</option><option value="administrative">Administrative</option><option value="authentication">Authentication</option><option value="incident">Incident</option><option value="assignment">Assignment</option></select></label>
        <label><span>Source</span><select value={source} onChange={(event) => { setPage(0); setSource(event.target.value as AdminInvestigationSource); }}><option value="all">All sources</option>{Object.entries(sourceLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label><span>From (Manila)</span><input type="date" value={from} max={to || undefined} onChange={(event) => { setPage(0); setFrom(event.target.value); }} /></label>
        <label><span>To (Manila)</span><input type="date" value={to} min={from || undefined} onChange={(event) => { setPage(0); setTo(event.target.value); }} /></label>
        <label><span>Actor profile ID</span><input type="number" min="1" step="1" value={actorId} onChange={(event) => { setPage(0); setActorId(event.target.value); }} placeholder="Any actor" /></label>
        <label><span>Target profile ID</span><input type="number" min="1" step="1" value={targetId} onChange={(event) => { setPage(0); setTargetId(event.target.value); }} placeholder="Any target" /></label>
        <label><span>Incident ID</span><input type="number" min="1" step="1" value={incidentId} onChange={(event) => { setPage(0); setIncidentId(event.target.value); }} placeholder="Any incident" /></label>
        <button className="audit-page__reset" type="button" onClick={resetFilters} disabled={!hasFilters}>Clear filters</button>
      </div>

      {error && <div className="admin-banner admin-banner--error" role="alert"><AlertCircle size={17} /><span>{error}</span></div>}

      <div className="audit-page__summary" aria-live="polite">
        <span>{loading ? "Loading investigation events…" : total.toLocaleString() + " matching events"}</span>
        <span>{lastSuccessfulRefresh ? "Last successful refresh: " + formatDate(lastSuccessfulRefresh) : "Not yet refreshed"} · Page {total === 0 ? 0 : page + 1} of {pageCount}</span>
      </div>

      <div className="admin-table-wrap audit-page__table-wrap">
        <table className="admin-table admin-audit-table audit-page__table">
          <thead><tr><th>Recorded (Manila)</th><th>Category</th><th>Actor</th><th>Target / incident</th><th>Event</th><th>Details</th></tr></thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={6}><div className="admin-empty"><LoaderCircle size={19} className="admin-spin" /> Loading records from protected event sources…</div></td></tr>
            ) : events.length === 0 ? (
              <tr><td colSpan={6}><div className="admin-empty"><Clock3 size={20} /><strong>{error ? "Investigation records unavailable" : "No matching investigation events"}</strong><span>{error ? "The page does not substitute sample data." : "Adjust your filters or check the source coverage note below."}</span></div></td></tr>
            ) : events.map((event) => (
              <Fragment key={event.event_id}>
                <tr>
                  <td className="admin-date">{formatDate(event.occurred_at)}</td>
                  <td><span className="audit-page__category">{categoryLabels[event.category]}</span></td>
                  <td><strong>{actorDisplay(event, profile)}</strong><small className="audit-page__subtext">{event.actor_user_id === null ? "No linked profile" : "Profile #" + event.actor_user_id}</small></td>
                  <td>
                    <strong>{event.target_label || (event.incident_id !== null ? "Incident #" + event.incident_id : "—")}</strong>
                    <small className="audit-page__subtext">{event.target_user_id !== null ? "Profile #" + event.target_user_id : event.incident_id !== null ? "Incident record" : "No linked target"}</small>
                  </td>
                  <td><strong>{event.summary}</strong><small className="audit-page__subtext">{formatEventType(event.event_type)}</small></td>
                  <td><button className="audit-page__details" type="button" aria-expanded={expanded === event.event_id} onClick={() => setExpanded(expanded === event.event_id ? null : event.event_id)}>{expanded === event.event_id ? "Hide" : "View"} <ChevronDown size={14} /></button></td>
                </tr>
                {expanded === event.event_id && (
                  <tr key={event.event_id + "-details"}>
                    <td className="audit-page__expanded" colSpan={6}>
                      <strong>Event details</strong>
                      <p>{event.summary}</p>
                      <dl>
                        <div><dt>Source</dt><dd>{event.source}</dd></div>
                        <div><dt>Event ID</dt><dd className="audit-page__event-id"><code>{event.event_id}</code><button type="button" onClick={() => void copyEventId(event.event_id)} aria-label={copiedEventId === event.event_id ? "Event ID copied" : "Copy event ID"} title={copiedEventId === event.event_id ? "Copied" : "Copy event ID"}>{copiedEventId === event.event_id ? <Check size={12} /> : <Copy size={12} />}{copiedEventId === event.event_id ? "Copied" : "Copy ID"}</button></dd></div>
                        <div><dt>Actor</dt><dd>{event.actor_label || "Unknown actor"}{event.actor_user_id === null ? "" : " · Profile #" + event.actor_user_id}</dd></div>
                        <div><dt>Target</dt><dd>{event.target_label || (event.target_user_id === null ? "Not applicable" : "Profile #" + event.target_user_id)}</dd></div>
                        <div><dt>Incident</dt><dd>{event.incident_id === null ? "Not applicable" : "#" + event.incident_id}</dd></div>
                        <div><dt>Exact timestamp</dt><dd>{event.occurred_at}</dd></div>
                        {Object.entries(event.details).map(([key, value]) => (
                          <div key={key}><dt>{formatDetailLabel(key)}</dt><dd>{formatDetailValue(value)}</dd></div>
                        ))}
                      </dl>
                      {copyError && <p className="audit-page__copy-error" role="status">{copyError}</p>}
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>

      <div className="admin-pagination audit-page__pagination" aria-label="Investigation history pagination">
        <span>Showing {total === 0 ? 0 : page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, total)} of {total.toLocaleString()}</span>
        <div><button type="button" onClick={() => setPage((current) => Math.max(0, current - 1))} disabled={page === 0 || loading}><ChevronLeft size={14} /> Previous</button><button type="button" onClick={() => setPage((current) => current + 1)} disabled={loading || (page + 1) * PAGE_SIZE >= total}>Next <ChevronRight size={14} /></button></div>
      </div>
      <p className="admin-panel__footnote">This feed reads existing administrative role-change, Supabase Auth, incident-status, claimability, sanitized requester-edit, and responder-assignment records through a TOTP/AAL2-protected, server-side query. Authentication events depend on Supabase Auth Audit Logs Postgres storage; if this category is empty, verify Authentication → Audit Logs because an empty table does not prove no sign-in activity occurred. Incident status records show the resulting status, not a guaranteed before/after pair. Requester edit events are reduced to changed-field names only; their raw snapshots are never exposed. Denied privileged RPC attempts are not yet durably represented in this feed. API transport telemetry, device-security events, and FCM delivery attempts are not included. Raw Auth payloads, IP addresses, precise coordinates, incident narratives, messages, attachments, and credentials are not exposed. Filters and pagination run server-side; date boundaries and displayed times use Asia/Manila (UTC+08:00). This interface is read-only.</p>
    </section>
  );
}
