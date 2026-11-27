import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Clock3, Copy, LoaderCircle, RefreshCw, Search, ShieldCheck } from "lucide-react";
import {
  getAdminAuthSessionsPage,
  type AdminAuthSessionCursor,
  type AdminAuthSessionPage,
} from "@/lib/supabase";
import "./AdminAccountSessions.css";

type Props = { profileId: number; accountName: string; onFindSessionActivity: (sessionId: string) => void };

function formatManilaDateTime(value: string | null) {
  if (!value) return "Not recorded";
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "Time unavailable";
  return new Intl.DateTimeFormat("en-PH", {
    timeZone: "Asia/Manila",
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

export default function AdminAccountSessions({ profileId, accountName, onFindSessionActivity }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [page, setPage] = useState<AdminAuthSessionPage | null>(null);
  const [cursors, setCursors] = useState<(AdminAuthSessionCursor | null)[]>([null]);
  const [pageIndex, setPageIndex] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copiedSessionId, setCopiedSessionId] = useState<string | null>(null);
  const [copyError, setCopyError] = useState<string | null>(null);
  const requestVersion = useRef(0);

  useEffect(() => () => {
    requestVersion.current += 1;
  }, []);

  const loadPage = useCallback(async (cursor: AdminAuthSessionCursor | null) => {
    const requestId = ++requestVersion.current;
    setLoading(true);
    setError(null);
    setPage(null);
    try {
      const result = await getAdminAuthSessionsPage(profileId, 10, cursor);
      if (requestId !== requestVersion.current) return;
      setPage(result);
    } catch (cause) {
      if (requestId !== requestVersion.current) return;
      setError(cause instanceof Error ? cause.message : "Session records could not be retrieved.");
    } finally {
      if (requestId === requestVersion.current) setLoading(false);
    }
  }, [profileId]);

  function toggleInventory() {
    if (expanded) {
      requestVersion.current += 1;
      setExpanded(false);
      setPage(null);
      setError(null);
      setLoading(false);
      setCursors([null]);
      setPageIndex(0);
      return;
    }
    setExpanded(true);
    setCursors([null]);
    setPageIndex(0);
    void loadPage(null);
  }

  function nextPage() {
    if (!page?.has_more || !page.next_cursor || loading) return;
    const nextCursors = [...cursors.slice(0, pageIndex + 1), page.next_cursor];
    setCursors(nextCursors);
    setPageIndex(nextCursors.length - 1);
    void loadPage(page.next_cursor);
  }

  function previousPage() {
    if (pageIndex <= 0 || loading) return;
    const previousIndex = pageIndex - 1;
    setPageIndex(previousIndex);
    void loadPage(cursors[previousIndex] ?? null);
  }

  async function copySessionId(sessionId: string) {
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error("Clipboard access is unavailable.");
      }
      await navigator.clipboard.writeText(sessionId);
      setCopiedSessionId(sessionId);
      setCopyError(null);
    } catch {
      setCopyError("Clipboard access is unavailable in this browser context.");
    }
  }

  return (
    <section className="admin-account-sessions" aria-labelledby={"admin-account-sessions-title-" + profileId}>
      <div className="admin-account-sessions__heading">
        <div>
          <h4 id={"admin-account-sessions-title-" + profileId}>Authentication sessions</h4>
          <p>Read-only Supabase Auth session records for {accountName}. This is separate from Laravel Sanctum sessions.</p>
        </div>
        <button
          className="admin-account-details"
          type="button"
          aria-expanded={expanded}
          onClick={toggleInventory}
          disabled={loading}
        >
          {expanded ? "Hide sessions" : "View sessions"}
        </button>
      </div>

      {expanded && (
        <div className="admin-account-sessions__body" aria-live="polite" aria-busy={loading}>
          {loading ? (
            <p className="admin-account-sessions__message" role="status"><LoaderCircle size={15} className="admin-spin" /> Loading protected session records…</p>
          ) : error ? (
            <div className="admin-account-sessions__message" role="alert">
              <p>{error}</p>
              <button className="admin-account-details" type="button" onClick={() => { void loadPage(cursors[pageIndex] ?? null); }}>
                <RefreshCw size={14} /> Retry
              </button>
            </div>
          ) : page ? (
            <>
              <p className="admin-account-sessions__source">
                <ShieldCheck size={14} /> Source: Supabase Auth · Status: {page.source_status} · Retrieved {formatManilaDateTime(page.observed_at)} (Asia/Manila)
              </p>
              <p className="admin-account-sessions__semantics">{page.status_semantics}</p>
              {page.items.length === 0 ? (
                <p className="admin-account-sessions__message">No session records were returned for this account by the selected source.</p>
              ) : (
                <ol className="admin-account-sessions__list">
                  {page.items.map((session) => (
                    <li key={session.session_id}>
                      <div className="admin-account-sessions__record">
                        <div className="admin-account-sessions__record-title">
                          <strong title={session.session_id}>Session · {session.session_id.slice(-8)}</strong>
                          <button
                            className="admin-account-details"
                            type="button"
                            onClick={() => { void copySessionId(session.session_id); }}
                            aria-label={`Copy full Auth session ID ${session.session_id}`}
                            title="Copy full Auth session ID"
                          >
                            <Copy size={13} /> {copiedSessionId === session.session_id ? "Copied" : "Copy ID"}
                          </button>
                          <button
                            className="admin-account-details"
                            type="button"
                            onClick={() => onFindSessionActivity(session.session_id)}
                            aria-label={`Find activity for Auth session ${session.session_id}`}
                            title="Search linked activity"
                          >
                            <Search size={13} /> Find activity
                          </button>
                          <span>Record present</span>
                        </div>
                        <dl>
                          <div><dt>Created</dt><dd>{formatManilaDateTime(session.created_at)}</dd></div>
                          <div><dt>Last refreshed</dt><dd>{formatManilaDateTime(session.last_refreshed_at)}</dd></div>
                          <div><dt>Last updated</dt><dd>{formatManilaDateTime(session.updated_at)}</dd></div>
                          <div><dt>Assurance level</dt><dd>{session.aal ?? "Not recorded"}</dd></div>
                          <div><dt>Not-after limit</dt><dd>{formatManilaDateTime(session.not_after)}</dd></div>
                          <div><dt>Validity</dt><dd>Not evaluated</dd></div>
                        </dl>
                      </div>
                    </li>
                  ))}
                </ol>
              )}
              <div className="admin-account-sessions__pagination">
                <span>Page {pageIndex + 1}</span>
                <div>
                  <button type="button" onClick={previousPage} disabled={pageIndex === 0 || loading}><ChevronLeft size={15} /> Previous</button>
                  <button type="button" onClick={nextPage} disabled={!page.has_more || !page.next_cursor || loading}>Next <ChevronRight size={15} /></button>
                </div>
              </div>
              {copyError && <p className="admin-account-sessions__message" role="status">{copyError}</p>}
              <p className="admin-account-sessions__footnote"><Clock3 size={13} /> Read-only inventory; it does not revoke sessions or evaluate each listed session's timeout validity. An already-issued access JWT may remain accepted by ordinary endpoints until expiry after sign-out, while protected TACResQ Admin operations and selected sensitive RLS reads also require the current Auth session record. Use Find activity to open the Audit history filtered to this session, or copy the ID for external correlation.</p>
            </>
          ) : (
            <p className="admin-account-sessions__message">Session records have not been loaded.</p>
          )}
        </div>
      )}
    </section>
  );
}
