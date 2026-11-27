import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Clock3, LoaderCircle, RefreshCw, ShieldCheck } from "lucide-react";
import {
  getAdminAuthSessionsPage,
  type AdminAuthSessionCursor,
  type AdminAuthSessionPage,
} from "@/lib/supabase";
import "./AdminAccountSessions.css";

type Props = { profileId: number; accountName: string };

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

export default function AdminAccountSessions({ profileId, accountName }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [page, setPage] = useState<AdminAuthSessionPage | null>(null);
  const [cursors, setCursors] = useState<(AdminAuthSessionCursor | null)[]>([null]);
  const [pageIndex, setPageIndex] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
                <ShieldCheck size={14} /> Source: Supabase Auth · Retrieved {formatManilaDateTime(page.observed_at)} (Asia/Manila)
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
                          <strong>Session · {session.session_id.slice(-8)}</strong>
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
              <p className="admin-account-sessions__footnote"><Clock3 size={13} /> This view does not revoke sessions. Existing access tokens can remain valid until their expiry even after refresh-token revocation.</p>
            </>
          ) : (
            <p className="admin-account-sessions__message">Session records have not been loaded.</p>
          )}
        </div>
      )}
    </section>
  );
}
