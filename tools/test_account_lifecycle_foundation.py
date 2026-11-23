#!/usr/bin/env python3
"""Static contract for the staged account-lifecycle foundation."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261123090000_account_lifecycle_foundation.sql").read_text().lower()
runtime_test = (ROOT / "tools/test_account_lifecycle_foundation.sql").read_text().lower()
replay = (ROOT / "tools/test_supabase_migration_replay.sh").read_text()
design = (ROOT / "docs/security/admin-account-suspension-design.md").read_text().lower()

for required in (
    "add column account_status text not null default 'active'",
    "add column suspended_at timestamptz",
    "add column suspended_by bigint",
    "add column suspension_reason text",
    "account_status in ('active', 'suspended')",
    "length(btrim(suspension_reason)) between 10 and 500",
    "create table public.account_lifecycle_audit_logs",
    "'account_suspended', 'account_reactivated'",
    "account_lifecycle_audit_transition_check",
    "enable row level security",
    "revoke all privileges on table public.account_lifecycle_audit_logs",
    "revoke all privileges on sequence public.account_lifecycle_audit_logs_id_seq",
    "before update or delete on public.account_lifecycle_audit_logs",\n    "account lifecycle audit history is append-only",
    "create or replace function private.tacresq_current_account_active()",
    "security definer",
    "set search_path = pg_catalog",
    "where p.auth_user_id = auth.uid()",
    "and p.account_status = 'active'",
    "grant execute on function private.tacresq_current_account_active() to authenticated",
    "revoke update (account_status, suspended_at, suspended_by, suspension_reason)",
):
    assert required in migration, f"Account lifecycle migration is missing: {required}"

helper = migration.split(
    "create or replace function private.tacresq_current_account_active()", 1
)[1].split("$function$;", 1)[0]
assert "auth.jwt()" not in helper and "raw_user_meta_data" not in helper, (
    "Account state must be read from the current database profile, not JWT metadata"
)
assert "create function public.api_admin_suspend" not in migration
assert "create or replace function public.api_admin_suspend" not in migration
assert "create or replace function public.api_admin_reactivate" not in migration
assert "create policy" not in migration, (
    "Do not enable partial account enforcement before all RPC/RLS/Storage paths are covered"
)

for required in (
    "same valid jwt",
    "private.tacresq_current_account_active()",
    "account_status = 'suspended'",
    "account_status = 'active'",
    "account lifecycle audit history is append-only",
    "authenticated client unexpectedly inserted an account audit row",
):
    assert required in runtime_test, f"Runtime account lifecycle test is missing: {required}"

assert "tools/test_account_lifecycle_foundation.sql" in replay, (
    "Clean migration replay must execute the account lifecycle SQL contract"
)
assert "status:** design and repository inventory required" not in design
assert "foundation migration" in design and "not yet enforced" in design

print("Account lifecycle foundation source contract passed.")
