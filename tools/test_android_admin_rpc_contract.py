#!/usr/bin/env python3
"""Ensure Admin tooling is absent from Android while protected server RPCs remain available."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/TacApi.kt").read_text()
supabase = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
repository = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
admin_screen_path = ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt"
web_admin_console = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
server_admin_migration = (
    ROOT / "supabase/migrations/20261026161500_admin_role_audit_foundation.sql"
).read_text().lower()
server_pagination_migration = (
    ROOT / "supabase/migrations/20261026170000_admin_server_side_user_pagination.sql"
).read_text().lower()
server_contract = server_admin_migration + "\n" + server_pagination_migration
mobile_client = "\n".join((api, supabase, repository))

checks = {
    "Android has no privileged Admin API interface or DTOs": all(
        name not in api
        for name in ("TacAdminApi", "AdminUser", "AdminUsersPage", "AdminAuditEvent", "AdminRoleChangeResult")
    ),
    "Android Supabase transport exposes no Admin RPC endpoints": all(
        name not in supabase
        for name in (
            "api_admin_list_users_page",
            "api_admin_list_audit_logs",
            "api_admin_set_user_role",
            "adminUsersPage",
            "adminAuditLogs",
            "setAdminUserRole",
        )
    ),
    "TacRepository exposes no Admin operations": all(
        name not in repository for name in ("TacAdminApi", "adminUsersPage", "adminAuditLogs", "setAdminUserRole")
    ),
    "Admin console implementation is removed from Android": not admin_screen_path.exists(),
    "AppRoot uses the blocked Admin screen instead of an Admin console": (
        "AdminMobileBlockedScreen(onSignOut = logout)" in root and "AdminAccessScreen" not in root
    ),
    "Server-side Admin RPCs remain in Supabase migrations": all(
        f"create or replace function public.{name}" in server_contract
        for name in (
            "api_admin_list_users_page",
            "api_admin_list_audit_logs",
            "api_admin_set_user_role",
        )
    ),
    "Server-side Admin RPCs remain unavailable to anon and public roles": all(
        "revoke all on function public." + name in server_contract
        for name in (
            "api_admin_list_users_page",
            "api_admin_list_audit_logs",
            "api_admin_set_user_role",
        )
    ),
    "Existing web Admin console remains available": all(
        label in web_admin_console
        for label in ("Admin console", "Change role", "Administrative audit log")
    ),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Admin client/server contract check(s) failed")
print(f"Android Admin removal and server preservation contract OK: {len(checks)} checks.")
