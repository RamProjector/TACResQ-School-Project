from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
client = (ROOT / "backend/web/src/lib/supabase.ts").read_text()
page = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
component = (ROOT / "backend/web/src/components/AdminAccountSessions.tsx").read_text()
styles = (ROOT / "backend/web/src/components/AdminAccountSessions.css").read_text()
migration = (ROOT / "supabase/migrations/20261127020000_admin_session_inventory_read_rpc.sql").read_text()

for marker in (
    "export interface AdminAuthSession",
    "export interface AdminAuthSessionCursor",
    "export interface AdminAuthSessionPage",
    "api_admin_list_auth_sessions_page:",
    "export async function getAdminAuthSessionsPage(",
    'getSupabase().rpc("api_admin_list_auth_sessions_page"',
    'if (error?.code === "PGRST202")',
    "The Admin session inventory RPC is not visible in this database's API schema cache.",
    "p_target_profile_id: profileId",
    "p_before_created_at: cursor?.created_at ?? null",
    "p_before_session_id: cursor?.session_id ?? null",
    "page.source !== \"supabase_auth_sessions\"",
    "page.source_status !== \"available\"",
    'item.validity_assessment !== "not_evaluated"',
    "page.has_more && page.next_cursor === null",
    "page.items[page.items.length - 1]?.session_id !== page.next_cursor?.session_id",
):
    assert marker in client, f"Missing client validation: {marker}"

for marker in (
    "function navigateToSection(section: AdminSection, auditQuery?: string)",
    'url.searchParams.set("auditQuery", auditQuery)',
    'url.searchParams.delete("auditQuery")',
):
    assert marker in page, f"Missing session-to-audit navigation behavior: {marker}"

for marker in (
    'import AdminAccountSessions from "@/components/AdminAccountSessions";',
    '<AdminAccountSessions key={user.id} profileId={user.id} accountName={user.name} onFindSessionActivity={(sessionId) => navigateToSection("audit", sessionId)} />',
):
    assert marker in page, f"Session inventory is not integrated in account details: {marker}"

for marker in (
    'getAdminAuthSessionsPage(profileId, 10, cursor)',
    "requestVersion = useRef(0)",
    "useEffect(() => () =>",
    "requestVersion.current += 1",
    "if (requestId !== requestVersion.current) return",
    "page.status_semantics",
    "page.source_status",
    "session.session_id.slice(-8)",
    "Record present",
    "Validity",
    "Not evaluated",
    "it does not revoke sessions",
    "onFindSessionActivity: (sessionId: string) => void",
    "onClick={() => onFindSessionActivity(session.session_id)}",
    "Find activity",
    "Use Find activity to open the Audit history filtered to this session",
    "Previous",
    "Next",
    "aria-busy={loading}",
):
    assert marker in component, f"Missing session inventory UI behavior: {marker}"

# The inventory can always be collapsed, including while its request is pending.
# Collapsing invalidates that request so a late response cannot reopen or repopulate it.
toggle_start = component.index('aria-expanded={expanded}')
toggle_end = component.index('</button>', toggle_start)
toggle_control = component[toggle_start:toggle_end]
assert 'onClick={toggleInventory}' in toggle_control
assert 'disabled={loading}' not in toggle_control
assert 'if (expanded):' not in component
assert 'requestVersion.current += 1' in component
assert 'setExpanded(false)' in component
assert 'setLoading(false)' in component

for forbidden in (
    "signOut(",
    "revokeSession(",
    "deleteSession(",
    "auth.admin",
    "refresh_token",
    "user_agent",
    "session.ip",
):
    assert forbidden not in component, f"Session inventory must remain read-only/minimized: {forbidden}"

assert "@media (max-width: 560px)" in styles
assert "flex-wrap: wrap" in styles
assert ".admin-account-sessions__record-title > strong" in styles
assert "gap: 0;" in styles
assert "border-radius: 0;" in styles
assert "background: transparent;" in styles
assert "grid-template-columns: minmax(0, 1fr)" in styles
assert "private.tacresq_require_admin_mfa()" in migration
assert "auth.sessions" in migration
assert "REVOKE ALL ON FUNCTION public.api_admin_list_auth_sessions_page" in migration
assert "GRANT EXECUTE ON FUNCTION public.api_admin_list_auth_sessions_page" in migration

print("PASS: Admin account session inventory is integrated, read-only, paginated, and privacy-limited.")
