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
chat_idempotency_migration = (ROOT / "supabase/migrations/20261123180000_account_lifecycle_chat_idempotency_guards.sql").read_text().lower()
write_rpc_migration = (ROOT / "supabase/migrations/20261123190000_account_lifecycle_write_rpc_entry_guards.sql").read_text().lower()
audit_reader_migration = (ROOT / "supabase/migrations/20261123200000_admin_account_lifecycle_audit_reader.sql").read_text().lower()
website_lifecycle_migration = (ROOT / "supabase/migrations/20261125100000_admin_website_account_lifecycle_controls.sql").read_text().lower()
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
    "to authenticated",
    "revoke update (account_status, suspended_at, suspended_by, suspension_reason)",
):
    assert required in migration, f"Account lifecycle migration is missing: {required}"

assert "grant execute on function private.tacresq_current_account_active()" in migration
assert "to authenticated" in migration

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

for function_name in ("api_create_incident_v4", "api_cancel_my_incident"):
    function_body = write_rpc_migration.split(
        f"create or replace function public.{function_name}", 1
    )[1].split("$function$;", 1)[0]
    profile_state_check = function_body.find("account_status = 'active'")
    profile_lock = function_body.find("for update")
    assert profile_state_check >= 0 and profile_lock > profile_state_check, (
        f"{function_name} must lock only an active caller before returning incident state"
    )
claim_body = write_rpc_migration.split(
    "create or replace function public.api_claim_incident", 1
)[1].split("$function$;", 1)[0]
assert claim_body.count("account_status = 'active'") == 2, (
    "Incident claiming must check active state both before incident lookup and after its profile lock"
)
assert claim_body.find("from public.incidents") < claim_body.rfind("for update"), (
    "Claim flow must preserve incident-then-profile lock ordering"
)

# The runtime claim-queue test must use a suspended responder/driver. A requester
# naturally has no claimable queue, so that role would make the test vacuous.
responder_fixture = runtime_test.split(
    "do $suspended_responder_claim_contract$", 1
)[0].rsplit("insert into auth.users", 1)[-1]
assert "00000000-0000-4000-8000-000000000996" in responder_fixture
assert "account_status = 'suspended'" in responder_fixture
assert "role = 'responder'" in responder_fixture
responder_claim_test = runtime_test.split(
    "do $suspended_responder_claim_contract$", 1
)[1].split("$suspended_responder_claim_contract$;", 1)[0]
assert "public.api_claimable_incidents()" in responder_claim_test
assert "public.api_claim_incident(1)->>'code' is distinct from 'unauthorized'" in responder_claim_test
assert "request.jwt.claim.sub', '00000000-0000-4000-8000-000000000996'" in runtime_test, (
    "Suspended responder runtime checks must execute under the same synthetic Auth subject."
)

for function_name, replay_marker in (
    ("api_reserve_incident_image_upload", "from private.incident_chat_upload_reservations"),
    ("api_send_incident_message", "from public.incident_messages"),
    ("api_send_incident_image_message", "from public.incident_messages"),
):
    function_body = chat_idempotency_migration.split(
        f"create or replace function public.{function_name}", 1
    )[1].split("$function$;", 1)[0]
    identity_position = function_body.find("actor_profile_id := private.tacresq_auth_profile_id()")
    replay_position = function_body.find(replay_marker)
    assert identity_position >= 0 and replay_position > identity_position, (
        f"{function_name} must resolve active identity before idempotency/replay lookup"
    )

role_wrapper = transition_migration.split(
    "create or replace function public.api_admin_set_user_role(", 1
)[1].split("$function$;", 1)[0]
role_lock_position = role_wrapper.find("pg_advisory_xact_lock")
role_mfa_position = role_wrapper.find("tacresq_require_admin_mfa")
assert role_lock_position >= 0 and role_mfa_position > role_lock_position, (
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
    "administrator accounts cannot be changed through this workflow",
    "length(v_reason) not between 10 and 500",
    "insert into public.account_lifecycle_audit_logs",
    "account_suspended",
    "account_reactivated",
    "revoke all on function public.api_admin_set_account_status(bigint, text, text)",
):
    assert required in transition_migration, f"Admin account transition is missing: {required}"

for required in (
    "create or replace function private.tacresq_require_admin_mfa()",
    "p.account_status = 'active'",
    "create or replace function public.api_admin_list_users_page(",
    "api_admin_list_users_page_internal(p_limit, p_offset, p_query, p_role)",
    "'account_status', p.account_status",
    "grant execute on function public.api_admin_set_account_status(bigint, text, text)",
    "grant execute on function public.api_admin_list_users_page(integer, integer, text, text)",
):
    assert required in website_lifecycle_migration, f"Website lifecycle enablement is missing: {required}"

for required in (
    "create or replace function public.api_admin_list_account_lifecycle_events(",
    "p_limit integer default 25",
    "p_offset integer default 0",
    "private.tacresq_require_admin_mfa()",
    "p_limit > 100",
    "p_offset > 1000000",
    "from public.account_lifecycle_audit_logs",
    "revoke all on function public.api_admin_list_account_lifecycle_events(integer, integer)",
    "grant execute on function public.api_admin_list_account_lifecycle_events(integer, integer)",
):
    assert required in audit_reader_migration, f"Protected lifecycle audit reader is missing: {required}"

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
    "suspended caller unexpectedly replayed a text-message operation",
    "suspended caller unexpectedly replayed an image reservation",
    "suspended caller unexpectedly replayed an image-message operation",
    "suspended requester unexpectedly replayed or created an sos",
    "suspended requester unexpectedly accessed incident cancellation",
    "suspended caller must not enter the incident claim workflow",
    "suspended responder unexpectedly received claimable incident data",
    "test_account_lifecycle_definer_write",
    "security definer table write unexpectedly succeeded",
    "suspended admin unexpectedly passed the totp-protected directory rpc",
    "suspended admin unexpectedly read account lifecycle history",
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
    "administrator accounts must remain outside ordinary account suspension management",
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
assert "foundation migration" in design and "other storage buckets, remaining idempotent/read rpcs" in design

print("Account lifecycle foundation source contract passed.")
