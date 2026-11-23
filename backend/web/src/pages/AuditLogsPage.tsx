import { useEffect, useState } from "react";
import { AlertCircle, Check, ChevronDown, ChevronLeft, ChevronRight, Clock3, Copy, LoaderCircle, RefreshCw, Search } from "lucide-react";
import { getAdminAccountLifecycleEvents, getAdminInvestigationEventsPage } from "@/lib/supabase";
import type { AdminAccountLifecycleEvent, AdminInvestigationCategory, AdminInvestigationEvent, AdminInvestigationSource, TacruProfile } from "@/lib/supabase";

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
  app_action_audit_logs: "App actions",
};

function formatDate(value: string | null | undefined) {
  if (!value) return "Time unavailable";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Time unavailable";
  return new Intl.DateTimeFormat(undefined, {
    timeZone: "Asia/Manila",
    dateStyle: "medium",
    timeStyle: "short",
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

function formatDetailLabel(value: string) {
  return value.replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function sourceDisplay(value: string) {
  return sourceLabels[value as Exclude<AdminInvestigationSource, "all">] || formatDetailLabel(value);
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
  const [events, setEvents] = useState<AdminInvestigationEvent[]>([]);\n  const [lifecycleEvents, setLifecycleEvents] = useState<AdminAccountLifecycleEvent[]>([]);\n  const [lifecycleTotal, setLifecycleTotal] = useState(0);\n  const [lifecycleError, setLifecycleError] = useState<string | null>(null);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<AdminInvestigationCategory>("all");
  const [source, setSource] = useState<AdminInvestigationSource>("all");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
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
      void getAdminInvestigationEventsPage(
        PAGE_SIZE,
        page * PAGE_SIZE,
        query,
        category,
        source,
        startOfDay(from),
        startOfNextDay(to),
        null,
        null,
        null,
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
  }, [page, query, category, source, from, to, refreshVersion]);

  useEffect(() => {
    let active = true;
    setLifecycleLoading(true);
    setLifecycleError(null);
    void getAdminAccountLifecycleEvents(lifecyclePageSize, lifecyclePage * lifecyclePageSize).then((result) => {
      if (!active) return;
      if (lifecyclePage > 0 && lifecyclePage * lifecyclePageSize >= result.total_count) {
        setLifecycleTotal(result.total_count);
        setLifecycleEvents([]);
        setLifecyclePage(Math.max(0, Math.ceil(result.total_count / lifecyclePageSize) - 1));
        return;
      }
      setLifecycleEvents(result.events);
      setLifecycleTotal(result.total_count);
    }).catch((cause: unknown) => {
      if (!active) return;
      setLifecycleEvents([]);
      setLifecycleTotal(0);
      setLifecycleError(cause instanceof Error ? cause.message : "Account lifecycle history is unavailable.");
    }).finally(() => {
      if (active) setLifecycleLoading(false);
    });
    return () => { active = false; };
  }, [lifecyclePage, refreshVersion]);

  async function copyEventId(eventId: string) {
    setCopyError(null);
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard access is unavailable in this browser context.");
      await navigator.clipboard.writeText(eventId);
      setCopiedEventId(eventId);
    } catch {
      setCopiedEventId(null);
      setCopyError("Could not copy the technical reference. Select the reference text and copy it manually.");
    }
  }

  function resetFilters() {
    setQuery("");
    setCategory("all");
    setSource("all");
    setFrom("");
    setTo("");
    setPage(0);
  }

  const lifecyclePageCount = Math.ceil(lifecycleTotal / lifecyclePageSize);
  const pageCount = Math.ceil(total / PAGE_SIZE);
  const hasFilters = Boolean(query || category !== "all" || source !== "all" || from || to);
  const firstVisiblePage = Math.max(0, Math.min(page - 2, pageCount - 5));
  const visiblePages = Array.from({ length: Math.min(5, pageCount) }, (_, index) => firstVisiblePage + index);
  const rangeStart = total === 0 ? 0 : page * PAGE_SIZE + 1;
  const rangeEnd = Math.min((page + 1) * PAGE_SIZE, total);

  return (
    <section className="audit-page" aria-labelledby="audit-page-title">
      <div className="audit-page__intro">
        <div>
          <p className="admin-eyebrow">ACCOUNTABILITY · READ-ONLY INVESTIGATION</p>
          <h2 id="audit-page-title"><Clock3 size={20} /> Audit history</h2>
          <p>Review recorded account, authentication, incident, and responder activity. Open an entry for its context; technical record identifiers stay out of the main feed.</p>
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
        <span>Seven recorded event sources are queried. Supabase Auth events appear only when Auth Audit Log storage is enabled. Denied privileged RPC attempts, device-security events, and direct-client API/Storage activity are not included.</span>
      </div>

      <details className="audit-page__coverage-details">
        <summary>Included sources and known limitations</summary>
        <div className="audit-page__coverage-grid">
          <section aria-labelledby="audit-sources-included">
            <h3 id="audit-sources-included">Included sources</h3>
            <ul>
              <li><strong>Admin role changes</strong><span>Role-change records and recorded reasons.</span></li>
              <li><strong>Supabase Auth</strong><span>Provider events when Auth Audit Logs Postgres storage is enabled and populated.</span></li>
              <li><strong>Incident status</strong><span>Recorded status outcomes; not necessarily a complete before-and-after transition.</span></li>
              <li><strong>Incident claiming</strong><span>Claimability history.</span></li>
              <li><strong>Requester edits</strong><span>Changed-field names only; private snapshots are excluded.</span></li>
              <li><strong>Responder assignments</strong><span>Assignment, release, and team-closure events.</span></li>
            </ul>
          </section>
          <section aria-labelledby="audit-sources-gaps">
            <h3 id="audit-sources-gaps">Not yet covered</h3>
            <ul>
              <li><strong>Denied privileged RPC attempts</strong><span>No trusted, durable application event source is integrated yet.</span></li>
              <li><strong>Device-security events</strong><span>Trusted server-side event writers are not implemented.</span></li>
              <li><strong>Direct-client API and Storage activity</strong><span>Laravel telemetry does not cover direct Android or browser Supabase calls.</span></li>
            </ul>
          </section>
        </div>
      </details>

      <section className="audit-page__lifecycle" aria-labelledby="account-lifecycle-history-title" aria-busy={lifecycleLoading}>
        <div className="audit-page__heading">
          <div>
            <h3 id="account-lifecycle-history-title">Account suspension and reactivation</h3>
            <p>Trusted account-status transitions recorded by the database. This history is separate from ordinary role changes.</p>
          </div>
          <span>{lifecycleError ? "Unavailable" : lifecycleTotal.toLocaleString() + " recorded"}</span>
        </div>
        {lifecycleError ? (
          <p className="admin-panel__footnote" role="alert">{lifecycleError}</p>
        ) : lifecycleLoading ? (
          <p className="admin-panel__footnote audit-page__lifecycle-loading" role="status" aria-live="polite"><LoaderCircle size={15} className="admin-spin" /> Loading account lifecycle history…</p>
        ) : lifecycleEvents.length === 0 ? (
          <p className="admin-panel__footnote">No account suspension or reactivation events have been recorded.</p>
        ) : (
          <div className="audit-page__list" aria-busy={lifecycleLoading}>
            {lifecycleEvents.map((event) => (
              <article className="audit-event-card" key={event.id}>
                <div className="audit-event-card__top">
                  <span className={"audit-page__category audit-page__category--" + (event.action === "account_suspended" ? "administrative" : "assignment")}>{event.action === "account_suspended" ? "Account suspended" : "Account reactivated"}</span>
                  <time dateTime={event.created_at}>{formatDate(event.created_at)}</time>
                </div>
                <div className="audit-event-card__heading">
                  <div><p>{event.actor_identity}</p><h3>{event.target_name}</h3></div>
                </div>
                <div className="audit-event-card__meta">
                  <span><small>Status transition</small><strong>{formatDetailLabel(event.previous_status)} → {formatDetailLabel(event.new_status)}</strong></span>
                  <span><small>Recorded by</small><strong>{event.actor_identity}</strong></span>
                </div>
                <div className="audit-event-card__details"><p>{event.reason}</p></div>
              </article>
            ))}
          </div>
        )}
        {lifecyclePageCount > 1 && !lifecycleError && (
          <nav className="audit-page__lifecycle-pagination" aria-label="Account lifecycle history pagination">
            <span>Page {lifecyclePage + 1} of {lifecyclePageCount} · {lifecycleTotal.toLocaleString()} events</span>
            <div>
              <button type="button" onClick={() => setLifecyclePage((current) => Math.max(0, current - 1))} disabled={lifecycleLoading || lifecyclePage === 0}><ChevronLeft size={14} /> Previous</button>
              <button type="button" onClick={() => setLifecyclePage((current) => Math.min(lifecyclePageCount - 1, current + 1))} disabled={lifecycleLoading || lifecyclePage + 1 >= lifecyclePageCount}>Next <ChevronRight size={14} /></button>
            </div>
          </nav>
        )}
        <p className="admin-panel__footnote">This section is independently paginated and reads only trusted account-status transitions through the protected database reader.</p>
      </section>

      <div className="audit-page__filters">
        <label className="admin-search audit-page__search">
          <Search size={16} />
          <input type="search" value={query} onChange={(event) => { setPage(0); setQuery(event.target.value); }} maxLength={120} placeholder="Search people, incidents, events or details" aria-label="Search investigation history" />
        </label>
        <label><span>Category</span><select value={category} onChange={(event) => { setPage(0); setCategory(event.target.value as AdminInvestigationCategory); }}><option value="all">All categories</option><option value="administrative">Administrative</option><option value="authentication">Authentication</option><option value="incident">Incident</option><option value="assignment">Assignment</option></select></label>
        <label><span>Source</span><select value={source} onChange={(event) => { setPage(0); setSource(event.target.value as AdminInvestigationSource); }}><option value="all">All sources</option>{Object.entries(sourceLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label><span>From</span><input type="date" value={from} max={to || undefined} onChange={(event) => { setPage(0); setFrom(event.target.value); }} /></label>
        <label><span>To</span><input type="date" value={to} min={from || undefined} onChange={(event) => { setPage(0); setTo(event.target.value); }} /></label>
        <button className="audit-page__reset" type="button" onClick={resetFilters} disabled={!hasFilters}>Clear filters</button>
      </div>

      {error && <div className="admin-banner admin-banner--error" role="alert"><AlertCircle size={17} /><span>{error}</span></div>}

      <div className="audit-page__summary" aria-live="polite">
        <span>{loading ? "Loading activity…" : error ? "Results unavailable" : total.toLocaleString() + " matching events"}</span>
        <span>{lastSuccessfulRefresh ? "Updated " + formatDate(lastSuccessfulRefresh) + " · Asia/Manila" : "Not yet refreshed"}</span>
      </div>

      <p className="audit-page__copy-error" role="status" aria-live="polite">{copyError || (copiedEventId ? "Technical reference copied." : "")}</p>

      <div className="audit-page__list" aria-busy={loading}>
        {loading ? (
          <div className="admin-empty"><LoaderCircle size={19} className="admin-spin" /> Loading recorded activity…</div>
        ) : events.length === 0 ? (
          <div className="admin-empty">
            <Clock3 size={20} />
            <strong>{error ? "Investigation records unavailable" : "No matching activity"}</strong>
            <span>{error ? "No sample records are shown. Refresh after checking the Admin database connection." : "Try another search, date range, category, or source."}</span>
          </div>
        ) : events.map((event) => (
          <article className="audit-event-card" key={event.event_id}>
            <div className="audit-event-card__top">
              <span className={"audit-page__category audit-page__category--" + event.category}>{categoryLabels[event.category]}</span>
              <time dateTime={event.occurred_at}>{formatDate(event.occurred_at)}</time>
            </div>
            <div className="audit-event-card__heading">
              <div>
                <p>{sourceDisplay(event.source)}</p>
                <h3>{event.summary}</h3>
              </div>
              <button className="audit-page__details" type="button" aria-label={(expanded === event.event_id ? "Hide details for " : "View details for ") + event.summary} aria-expanded={expanded === event.event_id} aria-controls={"audit-event-details-" + event.event_id} onClick={() => setExpanded(expanded === event.event_id ? null : event.event_id)}>
                {expanded === event.event_id ? "Hide details" : "View details"} <ChevronDown size={14} />
              </button>
            </div>
            <div className="audit-event-card__meta">
              <span><small>Performed by</small><strong>{actorDisplay(event, profile)}</strong></span>
              <span><small>Related record</small><strong>{event.target_label || (event.incident_id !== null ? "Incident activity" : "—")}</strong></span>
              <span><small>Activity</small><strong>{formatDetailLabel(event.event_type)}</strong></span>
            </div>
            {expanded === event.event_id && (
              <div className="audit-event-card__details" id={"audit-event-details-" + event.event_id}>
                <p>{event.summary}</p>
                <dl>
                  <div><dt>Event source</dt><dd>{sourceDisplay(event.source)}</dd></div>
                  <div><dt>Performed by</dt><dd>{actorDisplay(event, profile)}</dd></div>
                  <div><dt>Related record</dt><dd>{event.target_label || (event.incident_id !== null ? "Incident activity" : "Not applicable")}</dd></div>
                  {Object.entries(event.details).filter(([key]) => key !== "edit_number").map(([key, value]) => (
                    <div key={key}><dt>{formatDetailLabel(key)}</dt><dd>{formatDetailValue(value)}</dd></div>
                  ))}
                </dl>
                <details className="audit-event-card__references">
                  <summary>Technical references</summary>
                  <dl>
                    <div><dt>Event reference</dt><dd><code>{event.event_id}</code><button type="button" onClick={() => void copyEventId(event.event_id)} aria-label={copiedEventId === event.event_id ? "Reference copied" : "Copy technical reference"} title={copiedEventId === event.event_id ? "Copied" : "Copy reference"}>{copiedEventId === event.event_id ? <Check size={12} /> : <Copy size={12} />}{copiedEventId === event.event_id ? "Copied" : "Copy"}</button></dd></div>
                    <div><dt>Source key</dt><dd><code>{event.source}</code></dd></div>
                    {event.actor_user_id !== null && <div><dt>Actor profile reference</dt><dd>{event.actor_user_id}</dd></div>}
                    {event.target_user_id !== null && <div><dt>Target profile reference</dt><dd>{event.target_user_id}</dd></div>}
                    {event.incident_id !== null && <div><dt>Incident reference</dt><dd>{event.incident_id}</dd></div>}
                    <div><dt>Exact timestamp</dt><dd><code>{event.occurred_at}</code></dd></div>
                  </dl>
                </details>
              </div>
            )}
          </article>
        ))}
      </div>

      <div className="admin-pagination audit-page__pagination" aria-label="Investigation history pagination">
        <span>Showing {rangeStart}–{rangeEnd} of {total.toLocaleString()} <small>{pageCount ? "· Page " + (page + 1) + " of " + pageCount : ""}</small></span>
        <div>
          <button type="button" onClick={() => setPage((current) => Math.max(0, current - 1))} disabled={page === 0 || loading}><ChevronLeft size={14} /> Previous</button>
          <div className="audit-page__page-numbers" aria-label="Page numbers">
            {visiblePages.map((pageIndex) => <button key={pageIndex} type="button" className={pageIndex === page ? "is-current" : ""} aria-current={pageIndex === page ? "page" : undefined} onClick={() => setPage(pageIndex)} disabled={loading}>{pageIndex + 1}</button>)}
          </div>
          <button type="button" onClick={() => setPage((current) => current + 1)} disabled={loading || page + 1 >= pageCount}>Next <ChevronRight size={14} /></button>
        </div>
      </div>
      <p className="admin-panel__footnote">This feed reads existing administrative role-change, Supabase Auth, incident-status, claimability, sanitized requester-edit, and responder-assignment records through a TOTP/AAL2-protected, server-side query. Authentication events depend on Supabase Auth Audit Logs Postgres storage; an empty table does not prove no sign-in activity occurred. Incident status records show the resulting status, not a guaranteed before/after pair. Requester edit events expose changed-field names only; raw snapshots are never shown. Denied privileged RPC attempts are not yet durably represented in this feed. API transport telemetry, device-security events, and FCM delivery attempts are not included. Raw Auth payloads, IP addresses, precise coordinates, incident narratives, messages, attachments, and credentials are not exposed. Filters and pagination run server-side; date boundaries and displayed times use Asia/Manila (UTC+08:00). This interface is read-only.</p>
    </section>
  );
}
