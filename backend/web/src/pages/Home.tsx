import { useCallback, useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import type { Session } from "@supabase/supabase-js";
import {
  Activity,
  AlertCircle,
  ArrowUpRight,
  BadgeCheck,
  CarFront,
  CheckCircle2,
  CircleDot,
  Clock3,
  Flame,
  HeartPulse,
  LoaderCircle,
  LogOut,
  MapPin,
  Radio,
  RefreshCw,
  Search,
  ShieldAlert,
  Siren,
  Users,
  Waves,
  X,
} from "lucide-react";
import { Brand } from "@/components/Brand";
import {
  assignIncident,
  getAllIncidents,
  getFieldUnits,
  getMyProfile,
  getSupabase,
} from "@/lib/supabase";
import type { Incident, IncidentStatus, IncidentType, TacruProfile } from "@/lib/supabase";

interface HomeProps {
  session: Session | null;
}

type QueueFilter = "all" | "pending" | "active" | "resolved";

const statusNames: Record<IncidentStatus, string> = {
  pending: "Awaiting dispatch",
  assigned: "Unit assigned",
  en_route: "En route",
  on_scene: "On scene",
  transporting: "Transporting",
  resolved: "Resolved",
  cancelled: "Cancelled",
};

const typeNames: Record<IncidentType, string> = {
  medical: "Medical",
  fire: "Fire",
  vehicular: "Vehicle incident",
  disaster: "Flood / disaster",
  other: "Other report",
};

function isActive(status: IncidentStatus) {
  return ["assigned", "en_route", "on_scene", "transporting"].includes(status);
}

function initials(name: string) {
  return name.trim().split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("") || "D";
}

function formatTimestamp(value: string | null) {
  if (!value) return "Time not recorded";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Time not recorded";
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function TypeIcon({ type }: { type: IncidentType }) {
  const common = { size: 19, strokeWidth: 1.8, "aria-hidden": true as const };
  switch (type) {
    case "medical": return <HeartPulse {...common} />;
    case "fire": return <Flame {...common} />;
    case "vehicular": return <CarFront {...common} />;
    case "disaster": return <Waves {...common} />;
    default: return <Siren {...common} />;
  }
}

function StatusBadge({ status }: { status: IncidentStatus }) {
  return <span className={`status-badge status-badge--${status}`}><span />{statusNames[status]}</span>;
}

function LoginScreen() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const { error: authError } = await getSupabase().auth.signInWithPassword({ email: email.trim(), password });
      if (authError) throw authError;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Sign-in did not complete. Check your details and try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-story">
        <Brand inverse />
        <div className="login-story__body">
          <p className="eyebrow eyebrow--light"><span className="live-dot" /> TACRU FIELD OPERATIONS</p>
          <h1>One clear view<br />for every response.</h1>
          <p className="login-story__copy">A practical dispatch workspace for TACRU response operations — built around the team, the queue, and the next step.</p>
          <div className="login-story__signal" aria-hidden="true"><span /><span /><span /><span /><span /><span /><span /><span /><span /><span /><span /><span /><span /><span /><span /><span /><b>RESPONSE LINE</b></div>
        </div>
      </section>

      <section className="login-form-wrap">
        <div className="login-form-card">
          <div className="login-mobile-brand"><Brand /></div>
          <p className="eyebrow">DISPATCH ACCESS</p>
          <h2>Sign in to the desk</h2>
          <p className="muted">Use an authorized dispatcher account connected to the TACResQ Supabase project.</p>
          <form className="login-form" onSubmit={handleSubmit}>
            <label htmlFor="email">Email address</label>
            <input id="email" name="email" type="email" autoComplete="username" placeholder="dispatcher@example.org" value={email} onChange={(event) => setEmail(event.target.value)} required />
            <label htmlFor="password">Password</label>
            <input id="password" name="password" type="password" autoComplete="current-password" placeholder="Enter your password" value={password} onChange={(event) => setPassword(event.target.value)} required />
            {error && <div className="inline-alert inline-alert--error" role="alert"><AlertCircle size={17} />{error}</div>}
            <button className="button button--primary button--wide" type="submit" disabled={submitting}>
              {submitting ? <><LoaderCircle size={17} className="spin" /> Verifying access…</> : <>Enter dispatch desk <ArrowUpRight size={17} /></>}
            </button>
          </form>
          <div className="login-access-note"><BadgeCheck size={17} /><p>Access is role-checked by Supabase. New public sign-ups are requesters; dispatcher accounts must be provisioned by a trusted administrator.</p></div>
        </div>
        <p className="login-footnote">TACRU RESPONSE OPERATIONS</p>
      </section>
    </main>
  );
}

function GateScreen({ title, description, onSignOut, icon = "shield" }: { title: string; description: string; onSignOut: () => void; icon?: "shield" | "error" }) {
  return (
    <main className="gate-screen">
      <header className="gate-header"><Brand /><button className="button button--quiet" onClick={onSignOut}><LogOut size={16} /> Sign out</button></header>
      <section className="gate-card">
        <div className={`gate-icon${icon === "error" ? " gate-icon--error" : ""}`}>{icon === "error" ? <AlertCircle size={24} /> : <ShieldAlert size={24} />}</div>
        <p className="eyebrow">DISPATCH ACCESS CHECK</p>
        <h1>{title}</h1>
        <p className="muted">{description}</p>
        <div className="gate-footnote">This screen does not grant or change account roles. Ask a trusted project administrator to provision the correct role.</div>
      </section>
    </main>
  );
}

function IncidentCard({
  incident,
  units,
  selection,
  assigning,
  assignedName,
  onSelect,
  onRequestAssignment,
}: {
  incident: Incident;
  units: TacruProfile[];
  selection: string;
  assigning: boolean;
  assignedName?: string;
  onSelect: (value: string) => void;
  onRequestAssignment: (unit: TacruProfile) => void;
}) {
  const latitude = incident.latitude;
  const longitude = incident.longitude;
  const location = typeof latitude === "number" && Number.isFinite(latitude) &&
    typeof longitude === "number" && Number.isFinite(longitude)
    ? {
      coordinates: `${latitude.toFixed(4)}, ${longitude.toFixed(4)}`,
      mapHref: `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${latitude},${longitude}`)}`,
    }
    : null;

  return (
    <article className={`incident-card incident-card--${incident.status}`}>
      <div className={`incident-type-icon incident-type-icon--${incident.type}`}><TypeIcon type={incident.type} /></div>
      <div className="incident-card__content">
        <div className="incident-card__topline">
          <span className="incident-id">INCIDENT #{incident.id}</span>
          <span className="incident-time"><Clock3 size={13} /> {formatTimestamp(incident.created_at)}</span>
        </div>
        <div className="incident-card__title-row">
          <h3>{typeNames[incident.type]}</h3>
          <StatusBadge status={incident.status} />
        </div>
        <p className="incident-description">{incident.description?.trim() || "No additional details were provided."}</p>
        <div className="incident-card__details">
          <span className="requester-name">{incident.requester_name || "Requester not available"}</span>
          {incident.requester_phone && <a className="contact-link" href={`tel:${incident.requester_phone}`}>{incident.requester_phone}</a>}
          {location ? (
            <span className="location-readout"><MapPin size={14} /> {location.coordinates}</span>
          ) : (
            <span className="location-readout location-readout--missing"><MapPin size={14} /> No GPS pin shared</span>
          )}
          {location && incident.accuracy_m != null && <span className="accuracy-readout">Reported ±{Math.ceil(incident.accuracy_m)} m</span>}
          {location && <span className={`capture-readout${incident.location_recorded_at ? "" : " capture-readout--missing"}`}>
            {incident.location_recorded_at ? `Captured ${formatTimestamp(incident.location_recorded_at)}` : "Capture time unavailable; verify with requester"}
          </span>}
          {incident.location_notes?.trim() && <span className="location-note-readout">Location note: {incident.location_notes.trim()}</span>}
          {location && <a className="map-link" href={location.mapHref} target="_blank" rel="noreferrer">Open coordinates <ArrowUpRight size={13} /></a>}
        </div>
        {incident.status === "pending" ? (
          <div className="assignment-row">
            <label className="sr-only" htmlFor={`unit-${incident.id}`}>Select an available responder or driver for incident {incident.id}</label>
            <select id={`unit-${incident.id}`} value={selection} onChange={(event) => onSelect(event.target.value)} disabled={assigning || units.length === 0}>
              <option value="">{units.length ? "Choose an available unit" : "No available units"}</option>
              {units.map((unit) => <option key={unit.id} value={String(unit.id)}>{unit.name} · {unit.role}</option>)}
            </select>
            <button className="button button--assign" type="button" disabled={assigning || !selection} onClick={() => {
              const selected = units.find((unit) => String(unit.id) === selection);
              if (selected) onRequestAssignment(selected);
            }}>
              {assigning ? <LoaderCircle size={15} className="spin" /> : <Radio size={15} />}
              {assigning ? "Assigning…" : "Assign unit"}
            </button>
          </div>
        ) : (
          <div className="assignment-summary"><Users size={14} /> Assigned unit <strong>{assignedName || (incident.assigned_to ? `Unit #${incident.assigned_to}` : "Not available")}</strong></div>
        )}
      </div>
    </article>
  );
}

function ConfirmAssignment({
  incident,
  unit,
  busy,
  onCancel,
  onConfirm,
}: {
  incident: Incident;
  unit: TacruProfile;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onCancel(); }}>
      <section className="confirm-modal" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <button className="modal-close" type="button" aria-label="Close assignment confirmation" onClick={onCancel} disabled={busy}><X size={19} /></button>
        <div className="confirm-modal__icon"><Radio size={21} /></div>
        <p className="eyebrow">DISPATCH ACTION</p>
        <h2 id="confirm-title">Assign this incident?</h2>
        <p className="muted">This will write the assignment to the shared TACResQ Supabase database.</p>
        <div className="confirm-summary">
          <div><span>Incident</span><strong>#{incident.id} · {typeNames[incident.type]}</strong></div>
          <div><span>Unit</span><strong>{unit.name} · {unit.role}</strong></div>
        </div>
        <div className="confirm-actions">
          <button type="button" className="button button--quiet" onClick={onCancel} disabled={busy}>Go back</button>
          <button type="button" className="button button--primary" onClick={onConfirm} disabled={busy}>{busy ? <><LoaderCircle size={16} className="spin" /> Saving…</> : <>Confirm assignment <ArrowUpRight size={16} /></>}</button>
        </div>
      </section>
    </div>
  );
}

function DispatcherBoard({ session, profile, onSignOut }: { session: Session; profile: TacruProfile; onSignOut: () => void }) {
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [units, setUnits] = useState<TacruProfile[]>([]);
  const [queueLoading, setQueueLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [lastSyncedAt, setLastSyncedAt] = useState<Date | null>(null);
  const [filter, setFilter] = useState<QueueFilter>("all");
  const [search, setSearch] = useState("");
  const [unitSelection, setUnitSelection] = useState<Record<number, string>>({});
  const [assignmentTarget, setAssignmentTarget] = useState<{ incident: Incident; unit: TacruProfile } | null>(null);
  const [assigningIncident, setAssigningIncident] = useState<number | null>(null);
  const [assignmentBusy, setAssignmentBusy] = useState(false);
  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      const [nextIncidents, nextUnits] = await Promise.all([getAllIncidents(), getFieldUnits()]);
      setIncidents(nextIncidents);
      setUnits(nextUnits);
      setLastSyncedAt(new Date());
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not refresh the dispatch queue.");
    } finally {
      setQueueLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, 8_000);
    return () => window.clearInterval(timer);
  }, [refresh, session.user.id]);

  const pendingCount = incidents.filter((incident) => incident.status === "pending").length;
  const activeCount = incidents.filter((incident) => isActive(incident.status)).length;
  const resolvedCount = incidents.filter((incident) => incident.status === "resolved").length;
  const availableCount = units.filter((unit) => unit.duty_status === "available").length;

  const visibleIncidents = useMemo(() => {
    const normalizedQuery = search.trim().toLowerCase();
    return incidents.filter((incident) => {
      const matchesFilter = filter === "all"
        || (filter === "pending" && incident.status === "pending")
        || (filter === "active" && isActive(incident.status))
        || (filter === "resolved" && incident.status === "resolved");
      if (!matchesFilter) return false;
      if (!normalizedQuery) return true;
      const haystack = [incident.id, typeNames[incident.type], incident.status, incident.requester_name, incident.requester_phone, incident.description]
        .filter(Boolean).join(" ").toLowerCase();
      return haystack.includes(normalizedQuery);
    });
  }, [filter, incidents, search]);

  async function confirmAssignment() {
    if (!assignmentTarget || assignmentBusy) return;
    setAssignmentBusy(true);
    setAssigningIncident(assignmentTarget.incident.id);
    setError(null);
    setNotice(null);
    try {
      const updated = await assignIncident(assignmentTarget.incident.id, assignmentTarget.unit.id);
      setNotice(`Incident #${updated.id} assigned to ${assignmentTarget.unit.name}.`);
      setAssignmentTarget(null);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The assignment could not be saved.");
    } finally {
      setAssignmentBusy(false);
      setAssigningIncident(null);
    }
  }

  const filterItems: { id: QueueFilter; label: string; count: number }[] = [
    { id: "all", label: "All reports", count: incidents.length },
    { id: "pending", label: "Pending", count: pendingCount },
    { id: "active", label: "In progress", count: activeCount },
    { id: "resolved", label: "Resolved", count: resolvedCount },
  ];

  return (
    <div className="dashboard-shell">
      <aside className="sidebar">
        <Brand inverse />
        <div className="sidebar__section-label">OPERATIONS</div>
        <div className="sidebar-nav-item sidebar-nav-item--active"><Activity size={17} /><span>Dispatch board</span><span className="sidebar-nav-live">LIVE</span></div>
        <div className="sidebar__section-label sidebar__section-label--spaced">FIELD NETWORK</div>
        <div className="sidebar-info-row"><Users size={16} /><span>Field units</span><span>{units.length.toString().padStart(2, "0")}</span></div>
        <div className="sidebar-info-row"><CircleDot size={16} /><span>Awaiting dispatch</span><span className={pendingCount ? "sidebar-count sidebar-count--alert" : "sidebar-count"}>{pendingCount.toString().padStart(2, "0")}</span></div>
        <div className="sidebar__bottom">
          <div className="network-card"><span className="network-card__dot" /><div><strong>SUPABASE LINK</strong><small>{error ? "Sync needs attention" : "Authenticated channel"}</small></div></div>
          <div className="sidebar-version">TACRU · RESPONSE OPERATIONS</div>
        </div>
      </aside>

      <main className="dashboard-main">
        <header className="topbar">
          <div className="topbar__crumb"><span>TACTICAL ASSISTANCE</span><b>/</b><strong>DISPATCH</strong></div>
          <div className="topbar__right">
            <div className={`connection-pill${error ? " connection-pill--error" : ""}`}><span />{error ? "SYNC ISSUE" : "SUPABASE CONNECTED"}</div>
            <div className="topbar__user"><div className="avatar">{initials(profile.name)}</div><div><strong>{profile.name}</strong><small>Dispatcher</small></div></div>
            <button className="topbar-signout" onClick={onSignOut} title="Sign out"><LogOut size={16} /><span>Sign out</span></button>
          </div>
        </header>

        <div className="workspace">
          <div className="page-heading">
            <div>
              <p className="eyebrow">FIELD OPERATIONS <span>·</span> RESPONSE DESK</p>
              <h1>Dispatch board<span className="heading-period">.</span></h1>
              <p className="page-subtitle">A clear view of incoming reports and the units ready to respond.</p>
            </div>
            <div className="page-heading__status">
              <div className="sync-caption"><span>LAST SYNC</span><strong>{lastSyncedAt ? lastSyncedAt.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "Waiting"}</strong></div>
              <button className="button button--refresh" onClick={() => { void refresh(); }} disabled={refreshing} aria-label="Refresh incident queue">
                <RefreshCw size={16} className={refreshing ? "spin" : ""} /> <span>Refresh</span>
              </button>
            </div>
          </div>

          <section className="metric-strip" aria-label="Operational summary">
            <article className="metric-card metric-card--pending"><div className="metric-card__top"><span>AWAITING DISPATCH</span><CircleDot size={16} /></div><div className="metric-card__value">{pendingCount.toString().padStart(2, "0")}</div><div className="metric-card__foot">Pending reports</div></article>
            <article className="metric-card metric-card--active"><div className="metric-card__top"><span>ACTIVE RESPONSE</span><Activity size={16} /></div><div className="metric-card__value">{activeCount.toString().padStart(2, "0")}</div><div className="metric-card__foot">Units on an incident</div></article>
            <article className="metric-card metric-card--units"><div className="metric-card__top"><span>UNITS AVAILABLE</span><Users size={16} /></div><div className="metric-card__value">{availableCount.toString().padStart(2, "0")}<small> / {units.length.toString().padStart(2, "0")}</small></div><div className="metric-card__foot">Ready field units</div></article>
            <article className="metric-card metric-card--resolved"><div className="metric-card__top"><span>RESOLVED</span><CheckCircle2 size={16} /></div><div className="metric-card__value">{resolvedCount.toString().padStart(2, "0")}</div><div className="metric-card__foot">In current response history</div></article>
          </section>

          {(error || notice) && <div className={`message-banner${error ? " message-banner--error" : " message-banner--success"}`} role={error ? "alert" : "status"}>
            {error ? <AlertCircle size={17} /> : <CheckCircle2 size={17} />}<span>{error || notice}</span>
            <button type="button" aria-label="Dismiss message" onClick={() => { setError(null); setNotice(null); }}><X size={16} /></button>
          </div>}

          <div className="board-layout">
            <section className="queue-panel">
              <div className="panel-heading">
                <div><p className="eyebrow">RESPONSE QUEUE</p><h2>Incident reports <span>{incidents.length}</span></h2></div>
                <div className="queue-live"><span /> AUTO-REFRESH · 8 SEC</div>
              </div>
              <div className="queue-toolbar">
                <div className="filter-tabs" role="tablist" aria-label="Filter incident reports">
                  {filterItems.map((item) => <button key={item.id} type="button" role="tab" aria-selected={filter === item.id} className={filter === item.id ? "filter-tab filter-tab--selected" : "filter-tab"} onClick={() => setFilter(item.id)}>{item.label}<span>{item.count}</span></button>)}
                </div>
                <label className="search-field"><Search size={16} /><input type="search" placeholder="Search reports" value={search} onChange={(event) => setSearch(event.target.value)} aria-label="Search incident reports" />{search && <button type="button" aria-label="Clear search" onClick={() => setSearch("")}><X size={14} /></button>}</label>
              </div>

              <div className="incident-list" aria-live="polite">
                {queueLoading && incidents.length === 0 ? (
                  <div className="queue-loading"><LoaderCircle size={20} className="spin" /><span>Loading response queue…</span></div>
                ) : visibleIncidents.length === 0 ? (
                  <div className="empty-state"><div className="empty-state__icon"><CheckCircle2 size={23} /></div><p className="eyebrow">{search ? "NO MATCHES" : filter === "pending" ? "QUEUE CLEAR" : "NO REPORTS"}</p><h3>{search ? "No reports match this search." : filter === "pending" ? "Nothing waiting for dispatch." : "No incidents to show yet."}</h3><p>{search ? "Try a different name, incident number, or report detail." : "New reports submitted through the TACResQ app will appear here."}</p></div>
                ) : visibleIncidents.map((incident) => {
                  const assignedUnit = units.find((unit) => unit.id === incident.assigned_to);
                  return <IncidentCard key={incident.id} incident={incident} units={units.filter((unit) => unit.duty_status === "available")} selection={unitSelection[incident.id] ?? ""} assigning={assigningIncident === incident.id} assignedName={assignedUnit?.name} onSelect={(value) => setUnitSelection((current) => ({ ...current, [incident.id]: value }))} onRequestAssignment={(unit) => setAssignmentTarget({ incident, unit })} />;
                })}
              </div>
              <div className="queue-footer"><span><Activity size={14} /> Data protected by Supabase role checks</span><span>{visibleIncidents.length} shown</span></div>
            </section>

            <aside className="units-panel">
              <div className="panel-heading panel-heading--units"><div><p className="eyebrow">FIELD NETWORK</p><h2>Response units</h2></div><div className="units-total">{units.length.toString().padStart(2, "0")}</div></div>
              <div className="unit-status-summary"><span><i className="unit-dot unit-dot--available" /> Available <strong>{availableCount}</strong></span><span><i className="unit-dot unit-dot--busy" /> Engaged <strong>{units.filter((unit) => ["en_route", "on_scene", "transporting"].includes(unit.duty_status ?? "")).length}</strong></span></div>
              <div className="unit-list">
                {queueLoading && units.length === 0 ? <div className="unit-empty"><LoaderCircle size={18} className="spin" /> Loading units…</div> : units.length === 0 ? <div className="unit-empty"><Users size={19} /><span>No field units are provisioned yet.</span></div> : units.map((unit) => (
                  <article className="unit-card" key={unit.id}>
                    <div className={`unit-avatar${unit.duty_status === "available" ? " unit-avatar--available" : ""}`}>{initials(unit.name)}</div>
                    <div className="unit-card__identity"><strong>{unit.name}</strong><span>{unit.role === "driver" ? "Driver" : "Responder"}{unit.phone ? ` · ${unit.phone}` : ""}</span></div>
                    <span className={`unit-status unit-status--${unit.duty_status ?? "off_duty"}`}><i />{unit.duty_status?.replaceAll("_", " ") ?? "off duty"}</span>
                  </article>
                ))}
              </div>
              <div className="units-footnote"><MapPin size={15} /><p>Incident coordinates open in an external map.</p></div>
            </aside>
          </div>
          <footer className="workspace-footer"><span>TACRU · RESPONSE OPERATIONS</span><span>Supabase-secured dispatch workspace</span></footer>
        </div>
      </main>

      {assignmentTarget && <ConfirmAssignment incident={assignmentTarget.incident} unit={assignmentTarget.unit} busy={assignmentBusy} onCancel={() => setAssignmentTarget(null)} onConfirm={() => { void confirmAssignment(); }} />}
    </div>
  );
}

export default function Home({ session }: HomeProps) {
  const [profile, setProfile] = useState<TacruProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(Boolean(session));
  const [profileError, setProfileError] = useState<string | null>(null);

  useEffect(() => {
    if (!session) {
      setProfile(null);
      setProfileLoading(false);
      setProfileError(null);
      return;
    }
    let current = true;
    setProfile(null);
    setProfileError(null);
    setProfileLoading(true);
    void getMyProfile().then((result) => {
      if (current) setProfile(result);
    }).catch((cause: unknown) => {
      if (current) setProfileError(cause instanceof Error ? cause.message : "Could not verify this account's TACRU role.");
    }).finally(() => {
      if (current) setProfileLoading(false);
    });
    return () => { current = false; };
  }, [session?.user.id]);

  const handleSignOut = useCallback(() => {
    void getSupabase().auth.signOut();
  }, []);

  if (!session) return <LoginScreen />;
  if (profileLoading) return <main className="loading-screen"><Brand /><LoaderCircle size={22} className="spin" /><span>Checking dispatcher access…</span></main>;
  if (profileError) return <GateScreen title="We couldn't verify this profile." description={profileError} onSignOut={handleSignOut} icon="error" />;
  if (!profile || profile.role !== "dispatcher") return <GateScreen title="Dispatcher access is required." description="This account is signed in, but its TACResQ profile is not authorized to view the dispatch queue." onSignOut={handleSignOut} />;
  return <DispatcherBoard session={session} profile={profile} onSignOut={handleSignOut} />;
}
