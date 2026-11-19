#!/usr/bin/env python3
"""Static safety contract for the administrator dependency-health view."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
client = (root / "backend/web/src/lib/supabase.ts").read_text()
console = (root / "backend/web/src/pages/AdminConsole.tsx").read_text()
home = (root / "backend/web/src/pages/Home.tsx").read_text()

assert 'profile.role === "admin"' in home and "<AdminConsole profile={profile}" in home, (
    "Dependency health must remain inside the verified administrator-only console"
)
for required in (
    '"/auth/v1/health"',
    '"/rest/v1/rpc/api_dependency_health"',
    'method: "POST"',
    'cache: "no-store"',
    'setTimeout(() => controller.abort(), 5000)',
    'status: healthy ? "healthy" : "unavailable"',
    '"tacresq_database_ok"',
):
    assert required in client, f"Missing bounded read-only health probe behavior: {required}"

assert "SUPABASE_SECRET_KEY" not in client
assert "service_role" not in client
assert "api_admin_list_users_page" not in client[client.index("export async function getAdminDependencyHealth"):]
assert "api_dependency_health" in client[client.index("export async function getAdminDependencyHealth"):]
assert "Dependency health" in console
assert "does not verify incident workflows" in console
assert "getAdminDependencyHealth()" in console
assert "setUsers([])" not in console[console.index("setDependencyHealth(await"):] if "setDependencyHealth(await" in console else True

print("PASS: admin dependency health is role-gated, bounded, data-free, and uses no privileged key.")
