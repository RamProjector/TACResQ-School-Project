#!/usr/bin/env python3
"""Regression contract for retiring the unbounded legacy Admin user-list RPC."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261122050823_restrict_legacy_admin_user_list_rpc.sql").read_text()
web_api = (ROOT / "backend/web/src/lib/supabase.ts").read_text()
admin_ui = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()

assert "REVOKE ALL ON FUNCTION public.api_admin_list_users() FROM PUBLIC, anon, authenticated" in migration
assert "api_admin_list_users_page" in migration
assert "GRANT EXECUTE ON FUNCTION public.api_admin_list_users()" not in migration
assert "api_admin_list_users:" not in web_api
assert "getAdminUsersPage(" in admin_ui
assert "api_admin_list_users(" not in admin_ui

print("PASS: the unbounded legacy Admin user-list RPC is no longer executable by client roles.")
print("PASS: the current Admin Console uses bounded, server-paginated user retrieval.")
