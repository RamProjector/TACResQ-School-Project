#!/usr/bin/env python3
"""Keep Admin accounts out of Android workflows without shipping Admin-specific UI."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
auth_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/auth/AuthScreen.kt").read_text()
role_shell = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()
gps_map = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
fake_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()
admin_screen_path = ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt"
blocked_screen_path = ROOT / "app/src/main/java/com/tacresq/app/ui/auth/AdminMobileBlockedScreen.kt"

login_gate_start = root.index("if (loggedInState.session.user.role == Role.ADMIN)")
login_gate_end = root.index("container.pushTokenCoordinator.sync", login_gate_start)
login_gate = root[login_gate_start:login_gate_end]
logout_start = root.index("val logout: () -> Unit")
logout_end = root.index("LaunchedEffect(sessionState)", logout_start)
logout_flow = root[logout_start:logout_end]
ui_sources = "\n".join(
    path.read_text() for path in (ROOT / "app/src/main/java/com/tacresq/app/ui").rglob("*.kt")
)

checks = {
    "Admin role remains decodable for server-authenticated profiles": '@SerializedName("admin") ADMIN("Administrator")' in models,
    "AppRoot signs out Admin before creating operational ViewModels": (
        "if (user.role == Role.ADMIN)" in root
        and root.index("if (user.role == Role.ADMIN)") < root.index("val notificationsVm")
        and "logout()" in login_gate
        and "return@LaunchedEffect" in login_gate
    ),
    "Admin transition uses only generic sign-out and sign-in messaging": (
        '"Signing out…"' in root
        and "This account type isn't supported in the mobile app." in root
        and "AdminMobileBlockedScreen" not in ui_sources
    ),
    "No Admin-specific screen remains in Android": not admin_screen_path.exists() and not blocked_screen_path.exists(),
    "Android UI has no Admin console, blocked-page, or Admin-map copy": all(
        phrase not in ui_sources.lower()
        for phrase in (
            "admin console",
            "admin accounts can't use",
            "admin map is read-only",
            "administrator accounts are provisioned",
            "mobile access unavailable",
        )
    ),
    "Sign-out unregisters legacy push tokens before clearing the session": (
        "pushTokenCoordinator.unregister" in logout_flow
        and logout_flow.index("pushTokenCoordinator.unregister") < logout_flow.index("container.repository.logout()")
    ),
    "Admin login never syncs tokens or requests notification permission": (
        "logout()" in login_gate
        and "return@LaunchedEffect" in login_gate
        and "RequestPermission()" in root
    ),
    "RoleApp independently denies Admin before navigation": "if (role == Role.ADMIN) return" in root,
    "RoleSectionScreen independently returns without Admin content": "if (user.role == Role.ADMIN) return" in root,
    "RoleShell returns before rendering any Admin navigation": (
        "if (role == Role.ADMIN) return" in role_shell
        and "Role.ADMIN -> return emptyList()" in role_shell
        and 'listOf("Admin", "Audit", "Messages", "Profile")' not in role_shell
    ),
    "GPS map remains fail-closed for Admin incident visibility": "Role.ADMIN -> emptyList()" in gps_map,
    "Demo API denies Admin operational incident visibility": "Role.ADMIN -> false" in fake_api,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Android Admin role-safety check(s) failed")
print(f"Android Admin role-safety source contract OK: {len(checks)} checks.")
