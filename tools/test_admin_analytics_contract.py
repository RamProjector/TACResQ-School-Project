#!/usr/bin/env python3
"""Guard the Admin analytics RPC's authorization, scope, and privacy contract."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (
    ROOT
    / "supabase/migrations/20261122050856_admin_analytics_summary.sql"
).read_text()
schema = (
    ROOT
    / "supabase/migrations/20261015080806_tacresq_core_schema.sql"
).read_text()
client = (ROOT / "backend/web/src/lib/supabase.ts").read_text()
page = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()

# The aggregate endpoint must verify the current database-backed admin role.
assert "CREATE OR REPLACE FUNCTION public.api_admin_analytics_summary(" in migration
assert "SECURITY DEFINER" in migration
assert "SET search_path = pg_catalog" in migration
assert "p.auth_user_id = auth.uid()" in migration
assert "p.role = 'admin'" in migration
assert "Administrator access required" in migration

# Bound query cost and use server timestamps for first assignment.
assert "p_to - p_from > interval '366 days'" in migration
assert "i.created_at >= p_from" in migration
assert "i.created_at < p_to" in migration
assert "min(l.created_at) AS assigned_at" in migration
assert "l.status = 'assigned'" in migration
assert "generate_series(" in migration

# Keep the analytics status buckets exhaustive when the canonical incident
# status constraint changes. Pending, the four in-progress states, and the
# two terminal states must partition every incident in the reporting period.
incident_statuses = (
    "pending",
    "assigned",
    "en_route",
    "on_scene",
    "transporting",
    "resolved",
    "cancelled",
)
status_constraint = (
    "status text not null check (status in "
    "('pending','assigned','en_route','on_scene','transporting','resolved','cancelled'))"
)
assert status_constraint in schema, "Update this contract when the canonical incident status constraint changes."
for status in incident_statuses:
    assert f"'{status}'" in status_constraint

for status in ("assigned", "en_route", "on_scene", "transporting"):
    assert f"'{status}'" in migration.split("'active',", 1)[1].split("),", 1)[0], (
        f"Active analytics bucket omits incident status: {status}"
    )
for status in ("pending", "resolved", "cancelled"):
    assert f"status = '{status}'" in migration, (
        f"Analytics status bucket omits incident status: {status}"
    )

# Expose only aggregate values; do not add requester-identifying fields.
for sensitive_field in ("requester_id", "description", "latitude", "longitude", "phone", "email"):
    assert f"'{sensitive_field}'" not in migration

# Restrict function execution to signed-in sessions; no anon or PUBLIC execution.
assert "REVOKE ALL ON FUNCTION public.api_admin_analytics_summary" in migration
assert "FROM PUBLIC, anon;" in migration
assert "FROM authenticated;" in migration
assert "GRANT EXECUTE ON FUNCTION public.api_admin_analytics_summary" in migration
assert "TO authenticated" in migration

# The browser calls the aggregate RPC and validates its returned counts.
assert 'rpc("api_admin_analytics_summary"' in client
assert "Number.isSafeInteger(value)" in client
assert "getAdminAnalyticsSummary" in page
assert "Time to first assignment" in page
assert "current status at query time" in page

print("PASS: Admin analytics requires a current admin profile and bounded period.")
print("PASS: response is aggregate-only and contains no requester PII or coordinates.")
print("PASS: all canonical incident statuses map to exactly the intended analytics buckets.")
print("PASS: browser consumes and validates the protected analytics RPC.")
