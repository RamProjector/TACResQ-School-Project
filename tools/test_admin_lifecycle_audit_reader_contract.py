#!/usr/bin/env python3
"""Protect the Admin account-lifecycle audit reader's authorization and paging contract."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261123200000_admin_account_lifecycle_audit_reader.sql").read_text(encoding="utf-8").lower()
client = (ROOT / "backend/web/src/lib/supabase.ts").read_text(encoding="utf-8")
page = client[client.index("export async function getAdminAccountLifecycleEvents"):]

# The RPC must authorize every call and reject unbounded/invalid pagination.
assert "perform private.tacresq_require_admin_mfa();" in migration
assert "p_limit < 1 or p_limit > 100" in migration
assert "p_offset < 0 or p_offset > 1000000" in migration

# Count and page rows must share one statement snapshot. In particular, do not
# regress to a count statement followed by a separate page-fetch statement.
assert "with total as (" in migration
assert "lifecycle_page as (" in migration
assert "from total" in migration
assert "left join lifecycle_page on true" in migration
assert "group by total.total_count" in migration
assert "filter (where lifecycle_page.id is not null)" in migration
assert "select count(*)::bigint as total_count" in migration
assert "select count(*) into v_total" not in migration
assert migration.index("with total as (") < migration.index("select\n    total.total_count")
assert "order by a.created_at desc, a.id desc" in migration
assert "order by lifecycle_page.created_at desc, lifecycle_page.id desc" in migration

# Preserve the least-privilege RPC boundary and deployment-aware UI behavior.
assert "revoke all on function public.api_admin_list_account_lifecycle_events(integer, integer)" in migration
assert "from public, anon" in migration
assert "grant execute on function public.api_admin_list_account_lifecycle_events(integer, integer)" in migration
assert "to authenticated" in migration
assert "if (error?.code === \"pgrst202\")" in page.lower()
assert "history will appear after the reviewed database migration is applied" in page.lower()
assert "number.issafeinteger(page.total_count)" in page.lower()
assert "page.total_count < offset + page.events.length" in page.lower()

print("Admin account-lifecycle audit reader authorization and snapshot-consistent pagination contract passed.")
