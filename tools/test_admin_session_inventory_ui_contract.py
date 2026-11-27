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
    'import AdminAccountSessions from "@/components/AdminAccountSessions";',
    "<AdminAccountSessions key={user.id} profileId={user.id} accountName={user.name} />",
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
    "Previous",
    "Next",
    "aria-busy={loading}",
):
    assert marker in component, f"Missing session inventory UI behavior: {marker}"

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
assert "grid-template-columns: minmax(0, 1fr)" in styles
assert "private.tacresq_require_admin_mfa()" in migration
assert "auth.sessions" in migration
assert "REVOKE ALL ON FUNCTION public.api_admin_list_auth_sessions_page" in migration
assert "GRANT EXECUTE ON FUNCTION public.api_admin_list_auth_sessions_page" in migration

print("PASS: Admin account session inventory is integrated, read-only, paginated, and privacy-limited.")
