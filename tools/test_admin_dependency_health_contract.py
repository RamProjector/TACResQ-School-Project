#!/usr/bin/env python3
"""Static safety contract for the administrator dependency-health view."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
client = (root / "backend/web/src/lib/supabase.ts").read_text()
console = (root / "backend/web/src/pages/AdminConsole.tsx").read_text()
home = (root / "backend/web/src/pages/Home.tsx").read_text()
bootstrap = (root / "backend/src/bootstrap/app.php").read_text()

assert 'profile.role === "admin"' in home and "<AdminConsole profile={profile}" in home, (
    "Dependency health must remain inside the verified administrator-only console"
)
for required in (
    '"/up"',
    '"/auth/v1/health"',
    '"/rest/v1/rpc/api_dependency_health"',
    'method: "POST"',
    'cache: "no-store"',
    'setTimeout(() => controller.abort(), 5000)',
    'status: healthy ? "healthy" : "unavailable"',
    '"tacresq_database_ok"',
):
    assert required in client, f"Missing bounded read-only health probe behavior: {required}"

assert "supabaseUrl.replace(/\\/$/, '')" in client, (
    "Supabase health endpoint URLs must normalize a trailing slash without producing a malformed URL"
)
assert client.count('credentials: "omit"') == 3, (
    "All health probes must omit ambient browser credentials"
)
assert "SUPABASE_SECRET_KEY" not in client
assert "service_role" not in client
assert "api_admin_list_users_page" not in client[client.index("export async function getAdminDependencyHealth"):]
assert "api_dependency_health" in client[client.index("export async function getAdminDependencyHealth"):]
assert "health: '/up'" in bootstrap
assert "Laravel backend" in console
assert "Dependency health" in console
assert 'role="status" aria-label={label + " status: " + result.status}' in console
assert "do not verify incident workflows" in console
assert "getAdminDependencyHealth()" in console

print("PASS: admin dependency health is role-gated, bounded, data-free, and uses no privileged key.")
