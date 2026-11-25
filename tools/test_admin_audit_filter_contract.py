from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FILTERS = (ROOT / "backend/web/src/lib/adminAuditFilters.ts").read_text(encoding="utf-8")
AUDIT_PAGE = (ROOT / "backend/web/src/pages/AuditLogsPage.tsx").read_text(encoding="utf-8")
ADMIN_CSS = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")

# The validator must reject malformed calendar dates and impossible dates,
# while allowing either boundary to be omitted.
assert r"/^\d{4}-\d{2}-\d{2}$/" in FILTERS
assert "getUTCFullYear() === year" in FILTERS
assert "getUTCMonth() === month - 1" in FILTERS
assert "getUTCDate() === day" in FILTERS
assert "The start date must be on or before the end date." in FILTERS

# Invalid ranges must stop before the protected RPC is called.
assert "getAuditDateRangeError(from, to)" in AUDIT_PAGE
assert "if (dateRangeError || idFilterError)" in AUDIT_PAGE
assert 'setError(dateRangeError || idFilterError || "Invalid audit filter.")' in AUDIT_PAGE
assert "Boolean(dateRangeError || idFilterError)" in AUDIT_PAGE
assert "getAdminInvestigationEventsPage(" in AUDIT_PAGE

# Keyboard focus and reduced-motion preferences must be explicitly respected.
assert ":focus-visible" in ADMIN_CSS
assert "prefers-reduced-motion: reduce" in ADMIN_CSS

print("Admin audit filter and accessibility source contract passed.")


# Quick ranges use the fixed Manila calendar and remain accessible as a button group.
assert 'function manilaDateOffset(daysAgo: number)' in AUDIT_PAGE
assert 'timeZone: "Asia/Manila"' in AUDIT_PAGE
assert 'function isDatePresetSelected(days: number, from: string, to: string)' in AUDIT_PAGE
assert 'role="group" aria-label="Quick date range"' in AUDIT_PAGE
assert 'aria-pressed={isDatePresetSelected(days, from, to)}' in AUDIT_PAGE
assert 'setFrom(manilaDateOffset(days - 1)); setTo(manilaDateOffset(0));' in AUDIT_PAGE
assert ".audit-page__date-presets button[aria-pressed=\"true\"]" in ADMIN_CSS
assert ".audit-page__date-presets button:focus-visible" in ADMIN_CSS

# Results and metadata are invalidated immediately, before the debounced request.
query_effect = AUDIT_PAGE.split("useEffect(() => {", 2)[1].split("}, [page, query, category, source, from, to, actorId, targetId, incidentId, actorFilterId, targetFilterId, incidentFilterId, idFilterError, refreshVersion, dateRangeError]);", 1)[0]
assert query_effect.index("setEvents([]);") < query_effect.index("window.setTimeout")
assert query_effect.index("setTotal(0);") < query_effect.index("window.setTimeout")
assert query_effect.index("setLoading(true);") < query_effect.index("window.setTimeout")

# The displayed refresh timestamp belongs only to the currently applied query.
assert "setLastSuccessfulRefresh(null);" in AUDIT_PAGE
assert "setLastSuccessfulRefresh(new Date().toISOString());" in AUDIT_PAGE


# Account lifecycle history can be refreshed independently, including after
# a successful read or an empty result; failures retain an explicit retry action.
assert "lifecycleRefreshVersion" in AUDIT_PAGE
assert "setLifecycleRefreshVersion((current) => current + 1)" in AUDIT_PAGE
assert 'lifecycleError ? "Retry history" : "Refresh history"' in AUDIT_PAGE
assert 'className="audit-page__lifecycle-actions"' in AUDIT_PAGE
assert "}, [lifecyclePage, refreshVersion, lifecycleRefreshVersion]);" in AUDIT_PAGE
assert ".audit-page__lifecycle-error" in ADMIN_CSS
assert ".audit-page__lifecycle-actions .admin-refresh:focus-visible" in ADMIN_CSS
assert ".audit-page__lifecycle-actions" in ADMIN_CSS


# Account lifecycle history freshness is scoped to its own successful retrieval.
assert "lifecycleLastCheckedAt" in AUDIT_PAGE
assert "setLifecycleEvents([]);" in AUDIT_PAGE
assert "setLifecycleTotal(0);" in AUDIT_PAGE
assert "stale lifecycle data is not shown" in AUDIT_PAGE
assert "setLifecycleLastCheckedAt(null);" in AUDIT_PAGE
assert "setLifecycleLastCheckedAt(new Date().toISOString());" in AUDIT_PAGE
assert 'Updated " + formatDate(lifecycleLastCheckedAt) + " · Asia/Manila' in AUDIT_PAGE
assert "setLifecycleRefreshVersion((current) => current + 1)" in AUDIT_PAGE

assert ".audit-page__lifecycle .audit-page__heading > span" in ADMIN_CSS
assert "text-align:left;" in ADMIN_CSS


# Clipboard success/error feedback is scoped to the active audit query.
query_effect = AUDIT_PAGE.split("useEffect(() => {", 2)[1].split("}, [page, query, category, source, from, to, actorId, targetId, incidentId, actorFilterId, targetFilterId, incidentFilterId, idFilterError, refreshVersion, dateRangeError]);", 1)[0]
assert query_effect.index("setCopiedEventId(null);") < query_effect.index("if (dateRangeError || idFilterError)")
assert query_effect.index("setCopyError(null);") < query_effect.index("if (dateRangeError || idFilterError)")


# An asynchronous clipboard result must not restore feedback for an event that
# disappeared from the active investigation result set while copying.
assert "const visibleEventIds = useRef<Set<string>>(new Set());" in AUDIT_PAGE
assert "visibleEventIds.current = new Set(events.map((event) => String(event.event_id)));" in AUDIT_PAGE
copy_handler = AUDIT_PAGE.split("async function copyEventId(eventId: string)", 1)[1].split("\n  }", 1)[0]
assert "if (visibleEventIds.current.has(eventId)) setCopiedEventId(eventId);" in copy_handler
assert "if (!visibleEventIds.current.has(eventId)) return;" in copy_handler


# The seventh, metadata-only app-action source must be described accurately in
# the UI and operator guide without implying comprehensive activity tracking.
assert 'app_action_audit_logs: "App actions"' in AUDIT_PAGE
assert "<strong>App actions</strong>" in AUDIT_PAGE
assert "chat-message sends, notification-read changes, and field-unit duty-status changes" in AUDIT_PAGE
assert "do not expose message content" in AUDIT_PAGE
assert 'Object.entries(value as Record<string, unknown>)' in AUDIT_PAGE
assert 'boundedAuditText(formatDetailLabel(key)) + ": " + formatDetailValue(item, depth + 1)' in AUDIT_PAGE


# Actor, target, and incident filters must reach the protected server query.
# Empty values mean "all"; malformed, non-positive, fractional, or unsafe IDs
# are rejected locally rather than silently becoming an unfiltered request.
assert "function parseOptionalPositiveId(value: string): number | null" in AUDIT_PAGE
assert r"/^[1-9]\d*$/.test(value)" in AUDIT_PAGE
assert "Number.isSafeInteger(parsed)" in AUDIT_PAGE
for marker in (
    'const [actorId, setActorId] = useState("")',
    'const [targetId, setTargetId] = useState("")',
    'const [incidentId, setIncidentId] = useState("")',
    "const actorFilterId = parseOptionalPositiveId(actorId)",
    "const targetFilterId = parseOptionalPositiveId(targetId)",
    "const incidentFilterId = parseOptionalPositiveId(incidentId)",
    "Actor, target, and incident filters must be positive whole numbers.",
    "actorFilterId,",
    "targetFilterId,",
    "incidentFilterId,",
    'Actor profile ID',
    'Target profile ID',
    'Incident ID',
    'setActorId("")',
    'setTargetId("")',
    'setIncidentId("")',
    "idFilterError",
):
    assert marker in AUDIT_PAGE, f"Missing server-backed audit identifier filter contract: {marker}"
