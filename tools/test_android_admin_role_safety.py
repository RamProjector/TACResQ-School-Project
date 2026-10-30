#!/usr/bin/env python3
"""Guard Android Admin routing and its protected Supabase RPC boundary."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
admin_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt").read_text()
role_shell = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()
gps_map = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
fake_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
repository = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
admin_models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/AdminModels.kt").read_text()
role_sql = (ROOT / "supabase/migrations/20261026161500_admin_role_audit_foundation.sql").read_text()
page_sql = (ROOT / "supabase/migrations/20261026170000_admin_server_side_user_pagination.sql").read_text()

checks = {
    "Admin role has an explicit API serialization name": '@SerializedName("admin") ADMIN("Administrator")' in models,
    "AppRoot routes Admin to the dedicated console and repository": "Role.ADMIN -> AdminAccessScreen(user = user, repository = container.repository, onSignOut = logout)" in root,
    "Admin never enters requester, field-unit, dispatcher, or GPS navigation": all(token not in admin_screen for token in ("RequesterScreen", "ResponderScreen", "DispatcherScreen", "GpsMapScreen", "RoleShell(")),
    "Admin console revalidates the live profile before loading privileged data": "repository.me()" in admin_screen and "currentUser.role != Role.ADMIN" in admin_screen,
    "A transient profile network failure does not automatically sign out": "current.isSuccess && currentUser?.role != Role.ADMIN" in admin_screen,
    "Admin data is cleared after protected RPC failures": "users = emptyList()" in admin_screen and "audit = emptyList()" in admin_screen,
    "User directory is bounded and paginated": "pageSize = 25" in admin_screen and "adminUsersPage(pageSize, page * pageSize" in admin_screen,
    "Audit view is bounded": "adminAuditLogs(100)" in admin_screen,
    "Role change requires a 10-500 character reason": "reason.trim().length in 10..500" in admin_screen,
    "Admin role is excluded from Android-managed target roles": 'listOf("requester", "responder", "driver", "dispatcher")' in api,
    "Android uses the protected user-page, audit and role-change RPCs": all(name in api for name in ("api_admin_list_users_page", "api_admin_list_audit_logs", "api_admin_set_user_role")),
    "Admin RPCs are not added to the Render bridge allowlist": "adminUsersPage" not in api[api.find("private interface SupabaseBridgeRpcWire"):api.find("private interface SupabaseRpcWire")],
    "Repository fails closed when admin capability is unavailable": "Administrator tools are unavailable with the configured backend." in repository,
    "Admin response DTOs map database JSON fields": all(field in admin_models for field in ('@SerializedName("total_count")', '@SerializedName("actor_identity")', '@SerializedName("old_role")')),
    "Role mutation is SECURITY DEFINER and checks the authenticated admin profile": "SECURITY DEFINER" in role_sql and "p.auth_user_id = auth.uid() AND p.role = 'admin'" in role_sql,
    "Database rejects admin role promotion and demotion through this RPC": "p_role NOT IN ('requester', 'responder', 'driver', 'dispatcher')" in role_sql and "Administrator accounts must be changed by a trusted operator" in role_sql,
    "Audit log is written in the same role-change transaction": "INSERT INTO public.admin_audit_logs" in role_sql,
    "Paginated directory independently verifies administrator access": "p.auth_user_id = auth.uid() AND p.role = 'admin'" in page_sql and "p_limit > 100" in page_sql,
    "Fallback role section and map remain fail-closed for Admin": 'role == Role.ADMIN -> EmptySectionCard' in root and "Role.ADMIN -> emptyList()" in gps_map,
    "Demo incident filtering denies Admin operational incident visibility": "Role.ADMIN -> false" in fake_api,
    "RoleShell remains exhaustive but is not the Admin route": 'Role.ADMIN -> listOf("Admin", "Audit", "Alerts", "Profile")' in role_shell,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Android Admin role-safety check(s) failed")
print(f"Android Admin role-safety source contract OK: {len(checks)} checks.")
