#!/usr/bin/env python3
"""Static contract for the Admin Security authentication activity preview."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
console = (root / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
client = (root / "backend/web/src/lib/supabase.ts").read_text(encoding="utf-8")

# The preview must use the existing protected investigation API, narrowly
# scoped to authentication events, and must not imply complete session history.
assert 'getAdminInvestigationEventsPage(5, 0, "", "authentication", "all"' in console
assert "getAdminInvestigationEventsPage(" in client
assert 'api_admin_list_investigation_events' in client
assert 'Recent authentication and device-security events' in console
assert 'Latest five authentication and device-security events returned by the protected investigation feed' in console
assert 'the preview is not a complete sign-in or session history' in console
assert 'This does not prove that no sign-ins occurred.' in console
assert '<th scope="col">Auth session</th>' in console
assert '<th scope="col">Severity</th>' in console
assert 'event.details.severity' in console
assert 'severity.toUpperCase()' in console
assert 'event.details.auth_session_id' in console
assert 'Session · {authSessionId.slice(-8)}' in console
assert 'the device-security writer is not implemented' in console
assert 'use Activity log to search linked events' in console

# Prevent stale responses from replacing a newer refresh or a different page.
assert "authenticationEventsRequest.current" in console
assert "requestId !== authenticationEventsRequest.current" in console
assert 'if (activeSection !== "security") return;' in console

# Loading, failure, retry, empty, refresh timestamp, and full-log navigation
# must remain represented in the UI.
for marker in (
    'aria-busy={authenticationEventsLoading}',
    'authenticationEventsError ?',
    'Loading authentication activity…',
    'No authentication events were returned',
    'void refreshAuthenticationEvents();',
    'formatManilaDateTime(authenticationEventsLoadedAt)',
    'aria-live="polite"',
    'authenticationEventsError ? "Unavailable" : authenticationEventsLoading ? "Refreshing…"',

    'navigateToSection("audit")',
):
    assert marker in console, f"Missing authentication preview behavior: {marker}"

print("PASS: Admin Security authentication preview is scoped, stale-safe, and explicit about source coverage.")
