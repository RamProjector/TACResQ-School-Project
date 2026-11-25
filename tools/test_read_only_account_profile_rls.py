#!/usr/bin/env python3
"""Static contract for read-only-safe account lifecycle RLS predicates."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261125120000_read_only_account_profile_rls.sql").read_text().lower()
runtime = (ROOT / "tools/test_read_only_account_profile_rls.sql").read_text().lower()

for required in (
    "create or replace function private.tacresq_current_account_active_read()",
    "language sql",
    "stable",
    "security definer",
    "set search_path = pg_catalog",
    "where p.auth_user_id = auth.uid()",
    "grant execute on function private.tacresq_current_account_active_read()",
    "using ((select private.tacresq_current_account_active_read()))",
    "with check ((select private.tacresq_current_account_active_read()))",
    "create policy tacresq_fcm_select_requires_active",
    "create policy tacresq_fcm_insert_requires_active",
    "create policy tacresq_fcm_update_requires_active",
    "c.relname <> 'fcm_tokens'",
):
    assert required in migration, f"Read-only account RLS migration missing: {required}"

helper = migration.split(
    "create or replace function private.tacresq_current_account_active_read()", 1
)[1].split("$function$;", 1)[0]
assert "for share" not in helper and "for update" not in helper, (
    "Read-only RLS helper must never acquire a row lock"
)
assert "private.tacresq_current_account_active()" in migration, (
    "Existing locking helper must remain available to mutation boundaries"
)

for required in (
    "begin read only",
    "private.tacresq_current_account_active_read()",
    "public.api_me()",
    "suspended profile must not verify through api_me",
    "same still-valid jwt",
):
    assert required in runtime, f"Read-only profile runtime regression test missing: {required}"

print("Read-only account profile RLS source contract passed.")
