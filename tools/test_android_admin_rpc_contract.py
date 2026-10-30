#!/usr/bin/env python3
"""Contract checks for the Android Supabase-only Admin RPC transport and console."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/TacApi.kt").read_text()
supabase = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
repository = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt").read_text()
root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
bridge_start = supabase.index("private interface SupabaseBridgeRpcWire")
bridge_end = supabase.index("private interface SupabaseRpcWire", bridge_start)
bridge = supabase[bridge_start:bridge_end]
rpc = supabase[bridge_end:]

checks = {
    "Admin operations are an explicit optional capability": "interface TacAdminApi" in api,
    "Android Supabase client implements the Admin capability": "TacApi, TacNotificationApi, TacChatApi, TacAdminApi" in supabase,
    "User directory uses the bounded pagination RPC": 'rest/v1/rpc/api_admin_list_users_page' in rpc,
    "Audit reads use the protected audit RPC": 'rest/v1/rpc/api_admin_list_audit_logs' in rpc,
    "Role changes use the protected role RPC": 'rest/v1/rpc/api_admin_set_user_role' in rpc,
    "Admin RPCs are not exposed through the Laravel bridge": not any(name in bridge for name in (
        "api_admin_list_users", "api_admin_list_users_page", "api_admin_list_audit_logs", "api_admin_set_user_role"
    )),
    "Page size, offset, and search length are bounded client-side": (
        "limit in 1..100" in supabase and "offset in 0..1_000_000" in supabase
        and "query.length <= 120" in supabase
    ),
    "Role mutation cannot request Admin promotion or demotion": (
        'setOf("requester", "responder", "driver", "dispatcher")' in supabase
    ),
    "Role mutation requires a bounded audit reason": "normalizedReason.length in 10..500" in supabase,
    "Repository exposes admin operations through the protected capability": all(name in repository for name in (
        "TacAdminApi", "adminUsersPage", "adminAuditLogs", "setAdminUserRole"
    )),
    "Admin route receives the authenticated repository": "AdminAccessScreen(user = user, repository = container.repository" in root,
    "Admin screen supports paginated user search and audit history": all(name in screen for name in (
        "loadUsers(reset = false)", "Search name, email, phone, or ID", "Recent administrative actions", "it.length <= 120", "event.action.replace"
    )),
    "Admin role changes require a reason and exclude Admin promotion": (
        "reason.trim().length in 10..500" in screen and "adminRoles" in screen
        and "Administrator role is operator-managed" in screen
    ),
    "RPC argument names match the database contract": all(name in supabase for name in (
        'SerializedName("p_limit")', 'SerializedName("p_offset")',
        'SerializedName("p_user_id")', 'SerializedName("p_role")', 'SerializedName("p_reason")'
    )),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Android Admin RPC contract check(s) failed")
print(f"Android Admin RPC transport contract OK: {len(checks)} checks.")
