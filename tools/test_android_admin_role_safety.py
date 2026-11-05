#!/usr/bin/env python3
"""Guard Admin accounts from every operational Android app route."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
blocked = (ROOT / "app/src/main/java/com/tacresq/app/ui/auth/AdminMobileBlockedScreen.kt").read_text()
role_shell = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()
gps_map = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
fake_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()
admin_screen_path = ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt"

checks = {
    "Admin role remains represented for authenticated server profiles": '@SerializedName("admin") ADMIN("Administrator")' in models,
    "AppRoot blocks Admin before creating operational view models": (
        "if (user.role == Role.ADMIN)" in root
        and root.index("if (user.role == Role.ADMIN)") < root.index("val notificationsVm")
        and "AdminMobileBlockedScreen(onSignOut = logout)" in root
    ),
    "Blocked screen explains mobile restriction and offers sign-out": (
        "Admin accounts can't use TACResQ Android" in blocked
        and "approved admin console" in blocked
        and "Button(" in blocked
        and 'Text("Sign out")' in blocked
    ),
    "Admin push registrations are removed and not synced": (
        "if (loggedInState.session.user.role == Role.ADMIN)" in root
        and root.index("pushTokenCoordinator.unregister", root.index("if (loggedInState.session.user.role == Role.ADMIN)"))
        < root.index("pushTokenCoordinator.sync", root.index("if (loggedInState.session.user.role == Role.ADMIN)"))
    ),
    "RoleApp independently blocks Admin before navigation": "if (role == Role.ADMIN)" in root and "AdminMobileBlockedScreen(onSignOut = logout)" in root,
    "RoleShell returns before rendering Admin navigation": (
        "if (role == Role.ADMIN) return" in role_shell
        and "Role.ADMIN -> return emptyList()" in role_shell
        and 'listOf("Admin", "Audit", "Messages", "Profile")' not in role_shell
    ),
    "The former Android Admin console is deleted": not admin_screen_path.exists(),
    "GPS map remains fail-closed for Admin incident visibility": "Role.ADMIN -> emptyList()" in gps_map,
    "Demo API denies Admin operational incident visibility": "Role.ADMIN -> false" in fake_api,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Android Admin role-safety check(s) failed")
print(f"Android Admin role-safety source contract OK: {len(checks)} checks.")
