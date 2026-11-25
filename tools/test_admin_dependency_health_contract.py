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
    "Promise.race([request(controller.signal), aborted])",
    "removeEventListener(\"abort\", abortHandler)",
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
assert "Dependency reachability" in console
assert 'className="admin-health-summary" role="status" aria-live="polite"' in console
assert '<article className="admin-health-item" key={label}>' in console
assert 'className={"admin-health-status admin-health-status--" + result.status}' in console
assert "do not verify incident creation" in console
assert "void getAdminDependencyHealth()" in console
assert "exceeded five seconds" in console
assert "dependencyHealthLoading" in console
assert '"Checked "' in console and 'Asia/Manila' in console, (\n    "Dependency freshness must use the shared checked label and explicit Manila timezone"\n)
assert '"Checking…"' in console
assert 'status: controller.signal.aborted ? "slow" : "unavailable"' in client


assert '"All three dependencies responded within the probe threshold"' in console
assert 'reachableDependencyCount + " of " + dependencyProbes.length + " dependencies responded within the probe threshold"' in console
assert 'result.status === "slow" ? "timed out" : "unavailable"' in console
assert "not complete emergency-service readiness" in console

print("PASS: admin dependency health is role-gated, bounded, data-free, and reports probe scope clearly.")

# Unprobed capabilities must be labeled as outside this page's monitoring scope,
# never as healthy or failed provider states.
for required in (
    "Not covered by these probes",
    "Not monitored here",
    "Push notification delivery",
    "Maps and route calculation",
    "Incident attachments and Storage",
    "End-to-end emergency workflow",
    "Persistent monitoring and alerting",
    "It does not mean the provider or workflow is down, healthy, or configured.",
):
    assert required in console, f"Missing monitoring-boundary disclosure: {required}"
