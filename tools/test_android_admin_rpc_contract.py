#!/usr/bin/env python3
"""Ensure Admin tooling is absent from Android while protected server RPCs remain available."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/TacApi.kt").read_text()
supabase = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
repository = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
admin_screen_path = ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt"
blocked_screen_path = ROOT / "app/src/main/java/com/tacresq/app/ui/auth/AdminMobileBlockedScreen.kt"
web_admin_console = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
server_admin_migration = (
    ROOT / "supabase/migrations/20261112215653_admin_role_audit_foundation.sql"
).read_text().lower()
server_pagination_migration = (
    ROOT / "supabase/migrations/20261112215712_admin_server_side_user_pagination.sql"
).read_text().lower()
server_contract = server_admin_migration + "\n" + server_pagination_migration
resume_start = root.index("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)")
resume_end = root.index("LaunchedEffect(sessionState)", resume_start)
resume_flow = root[resume_start:resume_end]
permission_start = root.index("val notificationPermissionLauncher")
permission_end = root.index("val logout: () -> Unit", permission_start)
permission_flow = root[permission_start:permission_end]

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
    "Admin console and blocked screen implementations are removed from Android": (
        not admin_screen_path.exists() and not blocked_screen_path.exists()
    ),
    "AppRoot automatically signs out Admin without an Admin-specific screen": (
        "if (user.role == Role.ADMIN)" in root
        and "logout()" in root
        and "AdminMobileBlockedScreen" not in root
        and "AdminAccessScreen" not in root
    ),
    "Admin cannot re-register an FCM token when the app resumes": (
        "sessionState.session.user.role != Role.ADMIN" in resume_flow
        and "pushTokenCoordinator.sync" in resume_flow
        and resume_flow.index("role != Role.ADMIN") < resume_flow.index("pushTokenCoordinator.sync")
    ),
    "Admin cannot register an FCM token after the notification permission callback": (
        "sessionState.session.user.role != Role.ADMIN" in permission_flow
        and "pushTokenCoordinator.sync" in permission_flow
        and permission_flow.index("role != Role.ADMIN") < permission_flow.index("pushTokenCoordinator.sync")
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
    "Existing web Admin console remains available": (
        "export default function AdminConsole" in web_admin_console
        and all(
            marker in web_admin_console
            for marker in (
                "getAdminUsersPage",
                "setAdminUserRole",
                "AuditLogsPage",
                'activeSection === "audit" && <AuditLogsPage profile={profile} />',
                'label: "Overview"',
                'label: "User management"',
                'label: "Security"',
                'label: "Analytics & reports"',
                'label: "Activity log"',
            )
        )
    ),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Admin client/server contract check(s) failed")
print(f"Android Admin removal and server preservation contract OK: {len(checks)} checks.")
