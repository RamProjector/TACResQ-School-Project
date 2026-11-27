import { useEffect, useRef, useState } from "react";
import { AlertCircle, Check, ChevronDown, ChevronLeft, ChevronRight, Clock3, Copy, LoaderCircle, RefreshCw, Search } from "lucide-react";
import { getAdminAccountLifecycleEvents, getAdminInvestigationEventsPage } from "@/lib/supabase";
import type { AdminAccountLifecycleEvent, AdminInvestigationCategory, AdminInvestigationEvent, AdminInvestigationSource, TacruProfile } from "@/lib/supabase";
import { getAuditDateRangeError } from "@/lib/adminAuditFilters";

type AuditLogsPageProps = { profile: TacruProfile };

const PAGE_SIZE = 25;
const categoryLabels: Record<Exclude<AdminInvestigationCategory, "all">, string> = {
  administrative: "Administrative",
  authentication: "Authentication",
  operations: "API operations",
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
  device_security_events: "Device security events",
  api_operation_logs: "Laravel API operations",
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

function manilaDateOffset(daysAgo: number) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Manila",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const part = (type: string) => parts.find((item) => item.type === type)?.value || "";
  const date = new Date(Date.UTC(Number(part("year")), Number(part("month")) - 1, Number(part("day")) - daysAgo));
  return date.toISOString().slice(0, 10);
}

function isDatePresetSelected(days: number, from: string, to: string) {
  return from === manilaDateOffset(days - 1) && to === manilaDateOffset(0);
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

const MAX_AUDIT_DETAIL_DEPTH = 3;
const MAX_AUDIT_DETAIL_ITEMS = 20;
const MAX_AUDIT_DETAIL_TEXT = 500;

function boundedAuditText(value: string): string {
  return value.length > MAX_AUDIT_DETAIL_TEXT
    ? value.slice(0, MAX_AUDIT_DETAIL_TEXT) + "…"
    : value;
}

function formatDetailValue(value: unknown, depth = 0): string {
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "string") return boundedAuditText(value);
  if (typeof value === "number") return String(value);
  if (Array.isArray(value)) {
    const stringValues = value.filter((item): item is string => typeof item === "string");
    const labels = stringValues.slice(0, MAX_AUDIT_DETAIL_ITEMS).map(boundedAuditText);
    if (!labels.length) return "None";
    const remaining = stringValues.length > labels.length
      ? " · +" + (stringValues.length - labels.length) + " more"
      : "";
    return labels.join(", ") + remaining;
  }
  if (value && typeof value === "object") {
    if (depth >= MAX_AUDIT_DETAIL_DEPTH) return "Additional nested details omitted";
    const entries = Object.entries(value as Record<string, unknown>);
    const visible = entries.slice(0, MAX_AUDIT_DETAIL_ITEMS);
    const formatted = visible
      .map(([key, item]) => boundedAuditText(formatDetailLabel(key)) + ": " + formatDetailValue(item, depth + 1));
    if (entries.length > visible.length) formatted.push("+" + (entries.length - visible.length) + " more fields");
    return formatted.join(" · ");
  }
  return "Not available";
}

function actorDisplay(event: AdminInvestigationEvent, profile: TacruProfile) {
  return event.actor_user_id === profile.id ? "You" : event.actor_label || "Unknown actor";
}

function getTechnicalReference(event: AdminInvestigationEvent, key: string): string | null {
  const value = event.details[key];
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function parseOptionalPositiveId(value: string): number | null {
  if (!value.trim() || !/^[1-9]\d*$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

export default function AuditLogsPage({ profile }: AuditLogsPageProps) {
  const [events, setEvents] = useState<AdminInvestigationEvent[]>([]);
  // Clipboard promises can resolve after a filter/page change has cleared the
  // current result set. Track the IDs rendered now, not the stale closure.
  const visibleEventIds = useRef<Set<string>>(new Set());
  visibleEventIds.current = new Set(events.map((event) => String(event.event_id)));
  const [lifecycleEvents, setLifecycleEvents] = useState<AdminAccountLifecycleEvent[]>([]);
  const [lifecycleTotal, setLifecycleTotal] = useState(0);
  const [lifecyclePage, setLifecyclePage] = useState(0);
  const [lifecycleLoading, setLifecycleLoading] = useState(true);
  const [lifecycleError, setLifecycleError] = useState<string | null>(null);
  const [lifecycleLastCheckedAt, setLifecycleLastCheckedAt] = useState<string | null>(null);
  const [lifecycleRefreshVersion, setLifecycleRefreshVersion] = useState(0);
  const lifecyclePageSize = 5;
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<AdminInvestigationCategory>("all");
  const [source, setSource] = useState<AdminInvestigationSource>("all");
  const [from, setFrom] = useState(() => new URLSearchParams(window.location.search).get("auditFrom") ?? "");
  const [to, setTo] = useState(() => new URLSearchParams(window.location.search).get("auditTo") ?? "");
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

  const dateRangeError = getAuditDateRangeError(from, to);
  const actorFilterId = parseOptionalPositiveId(actorId);
  const targetFilterId = parseOptionalPositiveId(targetId);
  const incidentFilterId = parseOptionalPositiveId(incidentId);
  const idFilterError = (
    (actorId.trim() && actorFilterId === null)
    || (targetId.trim() && targetFilterId === null)
    || (incidentId.trim() && incidentFilterId === null)
  ) ? "Actor, target, and incident filters must be positive whole numbers." : null;

  useEffect(() => {
    let active = true;
    // Clipboard feedback belongs to the currently displayed investigation
    // result. Clear it as soon as filters or pagination change.
    setCopiedEventId(null);
    setCopyError(null);
    if (dateRangeError || idFilterError) {
      setEvents([]);
      setTotal(0);
      setExpanded(null);
      setLastSuccessfulRefresh(null);
      setError(dateRangeError || idFilterError || "Invalid audit filter.");
      setLoading(false);
      return () => { active = false; };
    }
    // Invalidate the prior result as soon as filters/page change, including
    // during the search debounce, so old events cannot look like matches.
    setLoading(true);
    setError(null);
    setEvents([]);
    setTotal(0);
    setExpanded(null);
    setLastSuccessfulRefresh(null);
    const timer = window.setTimeout(() => {
      void getAdminInvestigationEventsPage(
        PAGE_SIZE,
        page * PAGE_SIZE,
        query,
        category,
        source,
        startOfDay(from),
        startOfNextDay(to),
        actorFilterId,
        targetFilterId,
        incidentFilterId,
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
        setLastSuccessfulRefresh(null);
        setError(cause instanceof Error ? cause.message : "Investigation history could not be loaded.");
      }).finally(() => {
        if (active) setLoading(false);
      });
    }, query.trim() ? 250 : 0);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [page, query, category, source, from, to, actorId, targetId, incidentId, actorFilterId, targetFilterId, incidentFilterId, idFilterError, refreshVersion, dateRangeError]);

  useEffect(() => {
    let active = true;
    setLifecycleLoading(true);
    setLifecycleError(null);
    // A new page or retry starts a new read. Remove the prior page's rows and
    // count until this response succeeds so stale lifecycle data is not shown
    // as though it belongs to the requested page.
    setLifecycleEvents([]);
    setLifecycleTotal(0);
    setLifecycleLastCheckedAt(null);
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
      setLifecycleLastCheckedAt(new Date().toISOString());
    }).catch((cause: unknown) => {
      if (!active) return;
      setLifecycleEvents([]);
      setLifecycleTotal(0);
      setLifecycleLastCheckedAt(null);
      setLifecycleError(cause instanceof Error ? cause.message : "Account lifecycle history is unavailable.");
    }).finally(() => {
      if (active) setLifecycleLoading(false);
    });
    return () => { active = false; };
  }, [lifecyclePage, refreshVersion, lifecycleRefreshVersion]);

  async function copyEventId(eventId: string) {
    setCopyError(null);
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard access is unavailable in this browser context.");
      await navigator.clipboard.writeText(eventId);
      if (visibleEventIds.current.has(eventId)) setCopiedEventId(eventId);
    } catch {
      if (!visibleEventIds.current.has(eventId)) return;
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
    setActorId("");
    setTargetId("");
    setIncidentId("");
    setPage(0);
  }

  const lifecyclePageCount = Math.ceil(lifecycleTotal / lifecyclePageSize);
  const pageCount = Math.ceil(total / PAGE_SIZE);
  const notificationReadCount = events.filter((event) =>
    event.source === "app_action_audit_logs" && event.event_type === "notification_marked_read"
  ).length;

  const hasFilters = Boolean(query || category !== "all" || source !== "all" || from || to || actorId || targetId || incidentId);
  const firstVisiblePage = Math.max(0, Math.min(page - 2, pageCount - 5));
  const visiblePages = Array.from({ length: Math.min(5, pageCount) }, (_, index) => firstVisiblePage + index);
  const rangeStart = total === 0 ? 0 : page * PAGE_SIZE + 1;
  const rangeEnd = Math.min((page + 1) * PAGE_SIZE, total);
  return (
    <section className="audit-page" aria-labelledby="audit-page-title" aria-busy={loading}>
      <div className="audit-page__intro">
        <div>
          <h2 id="audit-page-title"><Clock3 size={20} /> Audit history</h2>
        </div>
        <button className="admin-refresh" type="button" onClick={() => {
          setLoading(true);
          setRefreshVersion((current) => current + 1);
        }} disabled={loading || Boolean(dateRangeError || idFilterError)} aria-label="Refresh investigation activity">
          <RefreshCw size={15} className={loading ? "admin-spin" : undefined} /> {loading ? "Refreshing…" : error ? "Retry" : "Refresh"}
        </button>
      </div>

      <details className="audit-page__coverage-details">
        <summary>Audit coverage and limitations</summary>
        <div className="audit-page__coverage"><strong>Coverage boundaries</strong><span>Nine source readers are queried; this describes feed integration, not proof that every source writer or provider setting is active. A zero-row result means no matching records were returned, not that no activity occurred. Supabase Auth capture is conditional on optional Postgres storage being enabled. Denied privileged RPC attempts, direct-client API/Storage activity, and FCM delivery attempts are not included. Device-security rows are read when present, but no trusted server-side writer currently creates them.</span></div>
      </details>

      <details className="audit-page__coverage-details">
        <summary>Included sources and known limitations</summary>
        <div className="audit-page__coverage-grid">
          <section aria-labelledby="audit-sources-included">
            <h3 id="audit-sources-included">Included sources</h3>
            <ul>
              <li><strong>Admin role changes</strong><span>Feed reader: integrated. Shows role-change records and recorded reasons; an empty result is not a health check for the writer.</span></li>
              <li><strong>Supabase Auth</strong><span>Feed reader: integrated. Capture is conditional on the Supabase project’s optional Auth Audit Logs Postgres storage setting; this setting is not verified by the page. Empty results cannot establish that authentication activity did not occur.</span></li>
              <li><strong>Incident status</strong><span>Feed reader: integrated. Shows recorded resulting-status outcomes; not necessarily a complete before-and-after transition. No rows means no matching records were returned.</span></li>
              <li><strong>Incident claiming</strong><span>Feed reader: integrated. Shows recorded claimability history; an empty result does not verify trigger health or prove no claimability changes occurred.</span></li>
              <li><strong>Requester edits</strong><span>Feed reader: integrated. Shows allowlisted changed-field names only; private before/after snapshots are excluded. Empty results are not a writer-health check.</span></li>
              <li><strong>Responder assignments</strong><span>Feed reader: integrated. Shows recorded assignment, release, and team-closure events; an empty result may simply mean none were recorded in the selected range.</span></li>
              <li><strong>App actions</strong><span>Feed reader and database event writers: integrated for chat-message sends, notification-read changes, and field-unit duty-status changes. Bounded metadata includes the verified Auth session ID when the action has a user JWT; message content and credentials are excluded. This is a selected-action subset, not comprehensive activity monitoring.</span></li>
              <li><strong>Device security events</strong><span>Feed reader: integrated. Sanitized event type, severity, and verified Auth session ID are searchable alongside API operations. No trusted server-side event writer is implemented yet, so an empty result is not a device-health signal.</span></li>
              <li><strong>Laravel API operations</strong><span>Feed reader: integrated for bridge-mediated requests. Records request correlation ID, provider request ID, and verified Auth session ID when available. This best-effort source does not cover direct Android/browser Supabase or Storage calls.</span></li>
              <li><strong>Device-security events</strong><span>Feed reader: integrated for sanitized event type, severity, and Auth session ID. No trusted server-side writer currently creates these rows, and the live source is empty; an empty result is not evidence that devices are safe.</span></li>
            </ul>
          </section>
          <section aria-labelledby="audit-sources-gaps">
            <h3 id="audit-sources-gaps">Not yet covered</h3>
            <ul>
              <li><strong>Denied privileged RPC attempts</strong><span>No trusted, durable application event source is integrated yet.</span></li>
              <li><strong>Direct-client API and Storage activity</strong><span>Laravel telemetry does not cover direct Android or browser Supabase calls.</span></li>
              <li><strong>FCM delivery attempts</strong><span>The server-only delivery-attempt ledger is not included in this feed. Provider acceptance does not prove device receipt or user acknowledgement.</span></li>
              <li><strong>Retention and integrity policy</strong><span>A single reviewed retention schedule, export-control policy, and cross-source integrity/restore standard is not established for every audit source.</span></li>
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
          <div className="audit-page__lifecycle-actions">
            <span className="admin-data-freshness" data-state={lifecycleError ? "error" : lifecycleLoading ? "loading" : lifecycleLastCheckedAt ? "success" : "empty"} role="status" aria-live="polite">{lifecycleError ? "Unavailable" : lifecycleLoading ? "Refreshing history…" : lifecycleTotal.toLocaleString() + " recorded" + (lifecycleLastCheckedAt ? " · Updated " + formatDate(lifecycleLastCheckedAt) + " · Asia/Manila" : " · Not yet checked")}</span>
            <button className="admin-refresh" type="button" onClick={() => setLifecycleRefreshVersion((current) => current + 1)} disabled={lifecycleLoading}>
              <RefreshCw size={15} className={lifecycleLoading ? "admin-spin" : undefined} /> {lifecycleError ? "Retry history" : "Refresh history"}
            </button>
          </div>
        </div>
        {lifecycleError ? (
          <div className="audit-page__lifecycle-error"><p className="admin-panel__footnote" role="alert">{lifecycleError}</p></div>
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
            <span aria-live="polite" aria-atomic="true">Page {lifecyclePage + 1} of {lifecyclePageCount} · {lifecycleTotal.toLocaleString()} events</span>
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
          <input type="search" value={query} onChange={(event) => { setPage(0); setQuery(event.target.value); }} maxLength={120} placeholder="Search people, incidents, events, correlation IDs or session IDs" aria-label="Search investigation history" />
        </label>
        <label><span>Category</span><select value={category} onChange={(event) => { setPage(0); setCategory(event.target.value as AdminInvestigationCategory); }}><option value="all">All categories</option><option value="administrative">Administrative</option><option value="authentication">Authentication</option><option value="operations">API operations</option><option value="incident">Incident</option><option value="assignment">Assignment</option></select></label>
        <label><span>Source</span><select value={source} onChange={(event) => { setPage(0); setSource(event.target.value as AdminInvestigationSource); }}><option value="all">All sources</option>{Object.entries(sourceLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label><span>From</span><input type="date" value={from} max={to || undefined} aria-invalid={Boolean(dateRangeError)} onChange={(event) => { setPage(0); setFrom(event.target.value); }} /></label>
        <label><span>To</span><input type="date" value={to} min={from || undefined} aria-invalid={Boolean(dateRangeError)} onChange={(event) => { setPage(0); setTo(event.target.value); }} /></label>
        <details className="audit-page__technical-filters">
          <summary>Technical reference filters</summary>
          <div className="audit-page__filters">
            <label><span>Actor profile reference</span><input type="number" inputMode="numeric" min="1" step="1" value={actorId} aria-invalid={Boolean(actorId.trim() && actorFilterId === null)} onChange={(event) => { setPage(0); setActorId(event.target.value); }} placeholder="Any actor" /></label>
            <label><span>Target profile reference</span><input type="number" inputMode="numeric" min="1" step="1" value={targetId} aria-invalid={Boolean(targetId.trim() && targetFilterId === null)} onChange={(event) => { setPage(0); setTargetId(event.target.value); }} placeholder="Any target" /></label>
            <label><span>Incident reference</span><input type="number" inputMode="numeric" min="1" step="1" value={incidentId} aria-invalid={Boolean(incidentId.trim() && incidentFilterId === null)} onChange={(event) => { setPage(0); setIncidentId(event.target.value); }} placeholder="Any incident" /></label>
          </div>
        </details>
        <div className="audit-page__date-presets" role="group" aria-label="Quick date range">
          <span>Quick range</span>
          {[7, 30, 90].map((days) => <button key={days} type="button" aria-pressed={isDatePresetSelected(days, from, to)} onClick={() => { setPage(0); setFrom(manilaDateOffset(days - 1)); setTo(manilaDateOffset(0)); }}>{days} days</button>)}
        </div>
        <button className="audit-page__reset" type="button" onClick={resetFilters} disabled={!hasFilters}>Clear filters</button>
      </div>

      {error && <div className="admin-banner admin-banner--error" role="alert"><AlertCircle size={17} /><span>{error}</span></div>}

      <div className="audit-page__summary" role="status" aria-live="polite">
        <span>{loading ? "Loading activity…" : error ? "Results unavailable" : total.toLocaleString() + " matching events"}</span>
        <span>{loading || error ? "Notification reads: —" : "Notification reads on this page: " + notificationReadCount.toLocaleString()}</span>
        <span className="admin-data-freshness" data-state={error ? "error" : loading ? "loading" : lastSuccessfulRefresh ? "success" : "empty"}>{error ? "Unavailable" : loading ? "Refreshing…" : lastSuccessfulRefresh ? "Updated " + formatDate(lastSuccessfulRefresh) + " · Asia/Manila" : "Not yet refreshed"}</span>
      </div>

      <p className="audit-page__copy-error" role="status" aria-live="polite">{copyError || (copiedEventId ? "Technical reference copied." : "")}</p>

      <div className="audit-page__list" id="audit-event-list" aria-busy={loading}>
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
              <button className="audit-page__details" type="button" aria-label={(expanded === event.event_id ? "Hide details for " : "View details for ") + event.summary} aria-expanded={expanded === event.event_id} aria-controls={expanded === event.event_id ? "audit-event-details-" + event.event_id : undefined} onClick={() => setExpanded(expanded === event.event_id ? null : event.event_id)}>
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
                    {getTechnicalReference(event, "correlation_id") && (
                      <div>
                        <dt>Correlation ID</dt>
                        <dd>
                          <code>{getTechnicalReference(event, "correlation_id")}</code>
                          <button type="button" onClick={() => { setQuery(getTechnicalReference(event, "correlation_id") || ""); setPage(0); setExpanded(null); }} aria-label="Find events with this correlation ID" title="Find related events">Find related</button>
                        </dd>
                      </div>
                    )}
                    {getTechnicalReference(event, "auth_session_id") && (
                      <div>
                        <dt>Auth session ID</dt>
                        <dd>
                          <code>{getTechnicalReference(event, "auth_session_id")}</code>
                          <button type="button" onClick={() => { setQuery(getTechnicalReference(event, "auth_session_id") || ""); setPage(0); setExpanded(null); }} aria-label="Find events from this Auth session" title="Find session activity">Find related</button>
                        </dd>
                      </div>
                    )}
                    {getTechnicalReference(event, "provider_request_id") && (
                      <div>
                        <dt>Provider request ID</dt>
                        <dd>
                          <code>{getTechnicalReference(event, "provider_request_id")}</code>
                          <button type="button" onClick={() => { setQuery(getTechnicalReference(event, "provider_request_id") || ""); setPage(0); setExpanded(null); }} aria-label="Find events with this provider request ID" title="Find related events">Find related</button>
                        </dd>
                      </div>
                    )}
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

      <nav className="admin-pagination audit-page__pagination" aria-label="Investigation history pagination" aria-controls="audit-event-list">
        <span aria-live="polite" aria-atomic="true">Showing {rangeStart}–{rangeEnd} of {total.toLocaleString()} <small>{pageCount ? "· Page " + (page + 1) + " of " + pageCount : ""}</small></span>
        <div>
          <button type="button" onClick={() => setPage((current) => Math.max(0, current - 1))} disabled={page === 0 || loading}><ChevronLeft size={14} /> Previous</button>
          <div className="audit-page__page-numbers" aria-label="Page numbers">
            {visiblePages.map((pageIndex) => <button key={pageIndex} type="button" className={pageIndex === page ? "is-current" : ""} aria-current={pageIndex === page ? "page" : undefined} onClick={() => setPage(pageIndex)} disabled={loading}>{pageIndex + 1}</button>)}
          </div>
          <button type="button" onClick={() => setPage((current) => current + 1)} disabled={loading || page + 1 >= pageCount}>Next <ChevronRight size={14} /></button>
        </div>
      </nav>
      <p className="admin-panel__footnote">This feed reads administrative role-change, Supabase Auth, incident-status, claimability, sanitized requester-edit, responder-assignment, metadata-only app-action, device-security, and Laravel API-operation records through a TOTP/AAL2-protected, server-side query. App-action events represent server-recorded message sends, notification reads, and field-unit duty-status changes; they do not expose message content. Auth session IDs are recorded only when the source has a verified JWT context; correlation IDs and provider request IDs remain distinct. Authentication events depend on Supabase Auth Audit Logs Postgres storage; an empty table does not prove no sign-in activity occurred. Incident status records show the resulting status, not a guaranteed before/after pair. Requester edit events expose changed-field names only; raw snapshots are never shown. Denied privileged RPC attempts are not yet durably represented in this feed. Device-security rows are included when present but have no trusted server-side writer yet; direct-client API/Storage activity and FCM delivery attempts are not included. Raw Auth payloads, IP addresses, precise coordinates, incident narratives, messages, attachments, and credentials are not exposed. Filters and pagination run server-side; date boundaries and displayed times use Asia/Manila (UTC+08:00). This interface is read-only.</p>
    </section>
  );
}
