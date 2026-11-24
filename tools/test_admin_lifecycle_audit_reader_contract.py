#!/usr/bin/env python3
"""Protect the Admin account-lifecycle audit reader's authorization and paging contract."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261123200000_admin_account_lifecycle_audit_reader.sql").read_text(encoding="utf-8").lower()
client = (ROOT / "backend/web/src/lib/supabase.ts").read_text(encoding="utf-8")
page = client[client.index("export async function getAdminAccountLifecycleEvents"):]

# The website account directory must display current lifecycle state and use
# only the protected Admin transition RPC for changes.
website_migration = (ROOT / "supabase/migrations/20261125110000_admin_account_lifecycle_compare_and_set.sql").read_text(encoding="utf-8").lower()
admin_page = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
assert 'account_status: "active" | "suspended"' in client
assert 'setadminaccountstatus' in client.lower()
assert 'api_admin_set_account_status' in client
assert 'account_status' in admin_page
assert 'changeaccountstatus(user)' in admin_page.lower()
assert 'suspend' in admin_page.lower() and 'reactivate' in admin_page.lower()
lifecycle_handler = admin_page[
    admin_page.index("async function changeAccountStatus"):
    admin_page.index("async function confirmRoleChange")
]
assert "window.prompt" not in lifecycle_handler
assert "window.confirm" not in lifecycle_handler
assert 'admin-lifecycle-dialog-title' in admin_page
assert 'p_expected_account_status' in client
assert '40001' in client
assert 'getAdminAccountLifecycleEvents(10, 0)' in admin_page
assert 'Recent suspension activity' in admin_page
assert 'p.account_status = \'active\'' in website_migration
assert 'grant execute on function public.api_admin_set_account_status(bigint, text, text, text)' in website_migration
assert 'from public, anon' in website_migration
assert "set account_status = 'suspended'" in website_migration
assert "set account_status = 'active'" in website_migration
assert "v_target.account_status is distinct from p_expected_account_status" in website_migration


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
