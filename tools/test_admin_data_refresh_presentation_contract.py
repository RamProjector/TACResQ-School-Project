#!/usr/bin/env python3
"""Static contract for consistent, dataset-scoped Admin refresh feedback."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
console = (root / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
audit = (root / "backend/web/src/pages/AuditLogsPage.tsx").read_text(encoding="utf-8")
styles = (root / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")

# A shared visual convention must not turn independent reads into one global
# refresh operation. Each control remains attached to its own request handler.
for marker in (
    'onClick={() => void retryDependencyHealth()}',
    'onClick={() => reloadAnalyticsReport()}',
    'onClick={() => { void refreshAuthenticationEvents(); }}',
    'setRefreshVersion((current) => current + 1)',
    'setLifecycleRefreshVersion((current) => current + 1)',
):
    assert marker in console + audit, f"Missing dataset-specific refresh handler: {marker}"

# All data workspaces communicate loading, failure, successful retrieval, and
# not-yet-retrieved states without presenting an old timestamp as current.
for marker in (
    'data-state={dependencyHealthError ? "error" : dependencyHealthLoading ? "loading"',
    'data-state={analyticsError ? "error" : analyticsLoading ? "loading"',
    'data-state={authenticationEventsError ? "error" : authenticationEventsLoading ? "loading"',
    'data-state={lifecycleError ? "error" : lifecycleLoading ? "loading"',
    'data-state={error ? "error" : loading ? "loading"',
    '"Unavailable"',
    '"Not yet retrieved"',
):
    assert marker in console + audit, f"Missing freshness state: {marker}"

# Shared presentation is neutral and does not alter query scopes or timestamps.
for marker in (
    ".admin-data-freshness",
    '.admin-data-freshness[data-state="loading"]',
    '.admin-data-freshness[data-state="error"]',
    '.admin-data-freshness[data-state="empty"]',
):
    assert marker in styles, f"Missing shared freshness style: {marker}"

assert 'aria-live="polite"' in console
assert 'aria-live="polite"' in audit
assert "formatManilaDateTime(analyticsLoadedAt)" in console
assert "formatDate(lastSuccessfulRefresh)" in audit

print("PASS: Admin refresh feedback is consistent while dataset refreshes remain independent.")
