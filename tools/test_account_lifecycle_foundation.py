#!/usr/bin/env python3
"""Static contract for the staged account-lifecycle foundation."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261123090000_account_lifecycle_foundation.sql").read_text().lower()
rls_migration = (ROOT / "supabase/migrations/20261123100000_account_lifecycle_restrictive_rls.sql").read_text().lower()
identity_migration = (ROOT / "supabase/migrations/20261123110000_account_lifecycle_identity_helpers.sql").read_text().lower()
write_migration = (ROOT / "supabase/migrations/20261123120000_account_lifecycle_write_guard_triggers.sql").read_text().lower()
admin_mfa_migration = (ROOT / "supabase/migrations/20261123130000_account_lifecycle_admin_mfa_guard.sql").read_text().lower()
read_rpc_migration = (ROOT / "supabase/migrations/20261123140000_account_lifecycle_read_rpc_guards.sql").read_text().lower()
assignment_helper_migration = (ROOT / "supabase/migrations/20261123150000_account_lifecycle_assignment_helper_guard.sql").read_text().lower()
volatility_migration = (ROOT / "supabase/migrations/20261123160000_account_lifecycle_helper_volatility.sql").read_text().lower()
transition_migration = (ROOT / "supabase/migrations/20261123170000_account_lifecycle_admin_transition_rpc.sql").read_text().lower()
runtime_test = (ROOT / "tools/test_account_lifecycle_foundation.sql").read_text().lower()
storage_test = (ROOT / "tools/test_incident_chat_storage_policies.sql").read_text().lower()
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
    "before update or delete on public.account_lifecycle_audit_logs",
    "account lifecycle audit history is append-only",
    "create or replace function private.tacresq_current_account_active()",
    "security definer",
    "set search_path = pg_catalog",
    "where p.auth_user_id = auth.uid()",
    "select p.account_status = 'active'",
    "for share",
    "volatile",
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
    "create or replace function private.tacresq_actor_role()",
    "create or replace function private.tacresq_auth_profile_id()",
    "account_status = 'active'",
    "for share",
    "volatile",
    "set search_path = pg_catalog",
    "grant execute on function private.tacresq_actor_role()",
    "grant execute on function private.tacresq_auth_profile_id()",
):
    assert required in identity_migration, f"Identity helper migration is missing: {required}"

for required in (
    "create or replace function public.api_my_notifications",
    "actor_profile_id := private.tacresq_auth_profile_id()",
    "create or replace function public.api_my_notification",
    "create or replace function public.api_my_incident_messages",
    "create or replace function public.api_claimable_incidents",
    "account_status = 'active'",
    "for share",
    "language plpgsql\nvolatile",
    "grant execute on function public.api_my_notifications(integer) to authenticated",
    "grant execute on function public.api_my_notification(bigint) to authenticated",
    "grant execute on function public.api_my_incident_messages(bigint, integer, bigint) to authenticated",
    "grant execute on function public.api_claimable_incidents() to authenticated",
):
    assert required in read_rpc_migration, f"Read-RPC account-state guard is missing: {required}"

role_wrapper = transition_migration.split(
    "create or replace function public.api_admin_set_user_role(", 1
)[1].split("$function$;", 1)[0]
assert role_wrapper.find("pg_advisory_xact_lock") < role_wrapper.find("tacresq_require_admin_mfa"), (
    "Admin role changes must serialize before locking the actor profile"
)
for required in (
    "create or replace function public.api_admin_set_account_status(",
    "pg_advisory_xact_lock(hashtext('tacresq-admin-account-lifecycle'))",
    "private.tacresq_require_admin_mfa()",
    "p.role = 'admin'",
    "p.account_status = 'active'",
    "for share",
    "for update",
    "p_target_profile_id = v_actor_id",
    "the last active administrator cannot be suspended",
    "length(v_reason) not between 10 and 500",
    "insert into public.account_lifecycle_audit_logs",
    "account_suspended",
    "account_reactivated",
    "revoke all on function public.api_admin_set_account_status(bigint, text, text)",
):
    assert required in transition_migration, f"Admin account transition is missing: {required}"

for required in (
    "create or replace function private.tacresq_auth_can_access_incident_chat(",
    "language plpgsql\nvolatile",
    "private.tacresq_auth_profile_id()",
    "create or replace function public.api_units()",
    "security invoker",
    "private.tacresq_actor_role() is distinct from 'dispatcher'",
    "p.account_status = 'active'",
):
    assert required in volatility_migration, f"Account-aware helper volatility guard is missing: {required}"

for required in (
    "create or replace function private.tacresq_is_incident_assignee(p_incident_id bigint)",
    "account_status = 'active'",
    "for share",
    "volatile",
    "set search_path = pg_catalog",
    "grant execute on function private.tacresq_is_incident_assignee(bigint)",
):
    assert required in assignment_helper_migration, f"Assignment helper account-state guard is missing: {required}"

for required in (
    "create or replace function private.tacresq_require_admin_mfa()",
    "account_status = 'active'",
    "for share",
    "volatile",
    "auth.jwt() ->> 'aal'",
    "method ->> 'method'",
):
    assert required in admin_mfa_migration, f"Admin MFA account-state guard is missing: {required}"

for required in (
    "create or replace function private.tacresq_reject_suspended_account_write()",
    "before insert or update or delete on %i.%i",
    "auth.uid() is not null",
    "not private.tacresq_current_account_active()",
    "using errcode = '42501'",
    "create trigger tacresq_active_account_write_guard",
    "revoke all on function private.tacresq_reject_suspended_account_write()",
):
    assert required in write_migration, f"Write-guard migration is missing: {required}"

for required in (
    "create policy tacresq_active_account_required",
    "as restrictive for all to authenticated",
    "using ((select private.tacresq_current_account_active()))",
    "with check ((select private.tacresq_current_account_active()))",
    "has_any_column_privilege('authenticated'",
    "and c.relrowsecurity",
):
    assert required in rls_migration, f"Direct-table RLS migration is missing: {required}"

for required in (
    "same valid jwt",
    "private.tacresq_current_account_active()",
    "account_status = 'suspended'",
    "account_status = 'active'",
    "restrictive rls must hide the suspended caller profile",
    "a suspended profile must fail all active identity helpers",
    "suspended caller unexpectedly read notification history",
    "suspended caller unexpectedly read a notification detail",
    "suspended caller unexpectedly read incident chat history",
    "suspended field-unit caller must not receive claimable incident data",
    "test_account_lifecycle_definer_write",
    "security definer table write unexpectedly succeeded",
    "suspended admin unexpectedly passed the totp-protected directory rpc",
    "suspended dispatcher unexpectedly passed api_units",
    "tacresq_active_account_write_guard",
    "authenticated client unexpectedly inserted an account audit row",
    "active-account restrictive rls must preserve the caller profile read",
    "suspension must preserve the target field unit duty status",
    "repeated suspension must be idempotent",
    "reactivation must clear current suspension metadata",
    "synthetic audit failure did not abort the transition",
    "account state must roll back when its audit insert fails",
    "an admin must not suspend their own account",
):
    assert required in runtime_test, f"Runtime account lifecycle test is missing: {required}"

assert "tools/test_account_lifecycle_foundation.sql" in replay, (
    "Clean migration replay must execute the account lifecycle SQL contract"
)

for required in (
    "a requester with the same still-valid jwt",
    "suspended requester must not download previously uploaded incident images",
    "suspended requester must not upload new incident images",
    "suspended requester must not read incident chat history",
    "suspended dispatcher must not send incident chat messages",
):
    assert required in storage_test, f"Suspended Storage/chat runtime test is missing: {required}"
assert "tools/test_incident_chat_storage_policies.sql" in replay, (
    "Clean migration replay must execute the Storage same-valid-JWT denial test"
)
assert "status:** design and repository inventory required" not in design
assert "foundation migration" in design and "other read-only security definer rpcs still require explicit guards" in design

print("Account lifecycle foundation source contract passed.")
