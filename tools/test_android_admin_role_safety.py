#!/usr/bin/env python3
"""Guard Admin sessions from entering requester/field-unit/dispatcher Android workflows."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
admin_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt").read_text()
role_shell = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()
gps_map = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
fake_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()

checks = {
    "Admin role has an explicit API serialization name": '@SerializedName("admin") ADMIN("Administrator")' in models,
    "AppRoot routes Admin before operational role branches": "Role.ADMIN -> AdminAccessScreen(user = user, repository = container.repository, onSignOut = logout)" in root,
    "Admin landing has a sign-out action": 'TextButton(onClick = onSignOut)' in admin_screen and 'Text("Sign out")' in admin_screen,
    "Admin landing explains that admin controls are not implemented in Android yet": "User directory" in admin_screen,
    "fallback role section is also fail-closed for Admin": 'role == Role.ADMIN -> EmptySectionCard' in root and 'Role.ADMIN -> "Administrator access"' in root,
    "Admin landing does not instantiate operational screens or navigation": all(token not in admin_screen for token in ("RequesterScreen", "ResponderScreen", "DispatcherScreen", "GpsMapScreen", "RoleShell(")),
    "RoleShell remains exhaustive but is not the Admin route": 'Role.ADMIN -> listOf("Admin", "Audit", "Alerts", "Profile")' in role_shell and "Role.ADMIN -> AdminAccessScreen" in root,
    "GPS map provides no incident set for Admin": "Role.ADMIN -> emptyList()" in gps_map,
    "Demo incident filtering denies Admin operational incident visibility": "Role.ADMIN -> false" in fake_api,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Android Admin role-safety check(s) failed")
print(f"Android Admin role-safety source contract OK: {len(checks)} checks.")
