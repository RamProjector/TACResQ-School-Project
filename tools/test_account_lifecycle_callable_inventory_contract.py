#!/usr/bin/env python3
"""Ensure the read-only authorization catalog does not omit PostgreSQL bypass surfaces."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
inventory = (ROOT / "tools/check_account_suspension_authorization_surface.sql").read_text(encoding="utf-8").lower()
crosswalk = (ROOT / "docs/security/admin-account-suspension-callable-definer-crosswalk.md").read_text(encoding="utf-8").lower()
review = (ROOT / "docs/security/admin-account-suspension-rpc-review-batch-20-invoker-and-private-callables.md").read_text(encoding="utf-8").lower()

# Client-callable routines must be visible in both exposed and private schemas,
# with their effective execution mode and grants.
assert "authenticated-callable public functions" in inventory
assert "authenticated-callable private functions" in inventory
assert "has_function_privilege('authenticated', p.oid, 'execute')" in inventory
assert "p.prosecdef" in inventory
assert "p.prosrc" in inventory

# A complete authorization review cannot stop at RPCs: views, triggers,
# column grants, sequences, schema grants, default ACLs and role inheritance
# can all create alternate access paths or future regressions.
for required in (
    "authenticated-readable public views and materialized views",
    "enabled user-defined triggers on public tables",
    "auth-schema triggers",
    "effective column-level privileges",
    "effective client privileges on public sequences",
    "client schema usage privileges",
    "global and schema-scoped default privileges",
    "client membership in superuser or bypassrls roles",
    "all private security definer functions",
):
    assert required in inventory, f"Authorization catalog omits: {required}"

assert "pg_get_viewdef" in inventory
assert "pg_get_triggerdef" in inventory
assert "has_column_privilege" in inventory
assert "has_sequence_privilege" in inventory
assert "pg_has_role" in inventory
assert "pg_default_acl" in inventory

# The crosswalk is deliberately honest about the difference between listing
# known SECURITY DEFINER signatures and reviewing every nested call path.
assert "24 authenticated-callable public security definer functions" in crosswalk
assert "api_assign_incident_team(bigint,bigint[])" in crosswalk
assert "api_unregister_fcm_token(text)" in crosswalk
assert "api_admin_set_account_status" in crosswalk
assert "not client-callable" in crosswalk
assert "api_admin_list_account_lifecycle_events(integer,integer)" in crosswalk
assert "13 authenticated-callable private security definer helpers" in crosswalk
assert "14 authenticated-callable application api invoker functions" in crosswalk
assert "38 authenticated-callable public api functions" in crosswalk
assert "not an automated proof" in crosswalk or "not proof" in crosswalk
assert "call-graph" in review
assert (ROOT / "docs/security/admin-account-suspension-rpc-review-batch-23-private-callable-definer-source-trace.md").is_file()
assert (ROOT / "docs/security/admin-account-suspension-rpc-review-batch-24-chat-storage-authentication-chain.md").is_file()
assert (ROOT / "tools/test_account_suspension_definer_inventory.sql").is_file()
assert (ROOT / "tools/test_account_suspension_private_definer_inventory.sql").is_file()
print("Account-lifecycle callable inventory contract passed.")


# RLS inventory must show who owns each relation and whether FORCE ROW LEVEL
# SECURITY is active. Ordinary RLS status alone does not constrain table owners.
assert "relation_owner" in inventory
assert "relforcerowsecurity" in inventory
assert "owner_is_privileged_service_role" in inventory
assert "join pg_catalog.pg_roles as table_owner" in inventory


# The directly callable private dispatcher implementation is an authorization
# boundary in its own right, even though the REST-facing wrapper is SECURITY
# INVOKER. Keep its active-profile lock contract tied to the replayed migration.
dispatch_migration = (ROOT / "supabase/migrations/20261125020856_account_lifecycle_dispatch_progress_guard.sql").read_text(encoding="utf-8").lower()
runtime_contract = (ROOT / "tools/test_account_lifecycle_foundation.sql").read_text(encoding="utf-8").lower()
for required in (
    "create or replace function private.tacresq_dispatch_update_incident_status",
    "account_status = 'active'",
    "for share",
    "only active dispatchers can update incident progress",
    "language plpgsql",
    "volatile",
    "security definer",
    "set search_path = pg_catalog",
    "grant execute on function private.tacresq_dispatch_update_incident_status",
):
    assert required in dispatch_migration, f"Dispatcher progress guard is missing: {required}"
for required in (
    "dispatcher_progress_contract",
    "routine_volatility is distinct from 'v'",
    "routine_source not ilike '%account_status = ''active''%'",
    "routine_source not ilike '%for share%'",
    "has_function_privilege('authenticated', routine_oid, 'execute')",
):
    assert required in runtime_contract, f"Dispatcher progress replay contract is missing: {required}"


# Guard the dispatcher team-assignment, release, and GPS location write paths.
# These checks prevent accidental restoration of direct client EXECUTE on the
# preserved internal implementations or removal of the active-profile locks.
assignment_migration = (ROOT / "supabase/migrations/20261125020857_account_lifecycle_team_assignment_guard.sql").read_text(encoding="utf-8").lower()
release_migration = (ROOT / "supabase/migrations/20261125020859_account_lifecycle_assignment_release_guard.sql").read_text(encoding="utf-8").lower()
location_migration = (ROOT / "supabase/migrations/20261125020901_account_lifecycle_location_writer_guard.sql").read_text(encoding="utf-8").lower()
for source, required in (
    (assignment_migration, (
        "create or replace function private.tacresq_assign_incident_team_guarded",
        "private.tacresq_assign_incident_team(",
        "account_status = 'active'",
        "for share",
        "only active dispatchers can assign incident teams",
        "create or replace function public.api_assign_incident",
        "security invoker",
    )),
    (release_migration, (
        "tacresq_release_incident_assignment_internal",
        "account_status = 'active'",
        "for share",
        "only active dispatchers can release incident assignments",
        "revoke all on function private.tacresq_release_incident_assignment_internal",
    )),
    (location_migration, (
        "tacresq_record_location_v2_internal",
        "account_status = 'active'",
        "for share",
        "only active responders and drivers can share incident locations",
        "revoke all on function private.tacresq_record_location_v2_internal",
    )),
):
    for item in required:
        assert item in source, f"Assignment/location lifecycle guard is missing: {item}"

for item in (
    "active_assignment_location_entry_contract",
    "public.api_assign_incident_team(bigint,bigint[])",
    "private.tacresq_release_incident_assignment(bigint,bigint)",
    "private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)",
    "tacresq_assign_incident_team_guarded",
    "tacresq_release_incident_assignment_internal",
    "tacresq_record_location_v2_internal",
):
    assert item in runtime_contract, f"Assignment/location replay contract is missing: {item}"


# Dispatcher roster and incident-queue reads must use the active lifecycle
# boundary. The roster SECURITY DEFINER body is preserved privately, while its
# public wrapper owns the active-profile share lock.
dispatcher_read_migration = (ROOT / "supabase/migrations/20261125020903_account_lifecycle_dispatcher_directory_guard.sql").read_text(encoding="utf-8").lower()
for required in (
    "alter function public.api_units() set schema private",
    "tacresq_api_units_internal",
    "account_status = 'active'",
    "for share",
    "only active dispatchers can view field units",
    "revoke all on function private.tacresq_api_units_internal()",
    "private.tacresq_actor_role() is distinct from 'dispatcher'",
    "only active dispatchers can view the queue",
    "security invoker",
):
    assert required in dispatcher_read_migration, f"Dispatcher read guard is missing: {required}"

for required in (
    "active_dispatcher_read_contract",
    "public.api_units()",
    "private.tacresq_api_units_internal()",
    "public.api_all_incidents()",
    "tacresq_actor_role()",
):
    assert required in runtime_contract, f"Dispatcher read replay contract is missing: {required}"


# Keep the callable crosswalk synchronized with the November 24 guarded
# dispatcher mutations and reads. A migration without a corresponding review
# entry would otherwise look like a complete inventory while hiding a new
# callable boundary from reviewers.
for migration, section in (
    ("20261125020857_account_lifecycle_team_assignment_guard.sql", "guarded dispatcher team assignment"),
    ("20261125020859_account_lifecycle_assignment_release_guard.sql", "guarded assignment release"),
    ("20261125020901_account_lifecycle_location_writer_guard.sql", "guarded responder gps writer"),
    ("20261125020903_account_lifecycle_dispatcher_directory_guard.sql", "guarded dispatcher directory and queue"),
):
    assert (ROOT / "supabase/migrations" / migration).is_file(), (
        f"Crosswalk references a missing lifecycle migration: {migration}"
    )
    assert section in crosswalk, (
        f"Callable crosswalk is missing its implementation review section: {section}"
    )

assert "updated release-gate status after the november 24 callable guards" in crosswalk
assert "same-valid-jwt" in crosswalk
assert "no connected supabase project was queried or modified" in crosswalk


# Keep the expanded PostgreSQL callable replay assertions tied to the exact
# migrations that introduced the guarded notification, assignee, chat, SOS,
# and FCM entry points. These source checks prevent a replay block from silently
# outliving or drifting away from its implementation migration.
foundation_replay = (ROOT / "tools/test_account_lifecycle_foundation.sql").read_text(encoding="utf-8").lower()
for block in (
    "notification_claimable_read_catalog_contract",
    "incident_assignee_helper_catalog_contract",
    "chat_idempotency_catalog_contract",
    "requester_sos_write_catalog_contract",
    "fcm_token_lifecycle_catalog_contract",
):
    assert block in foundation_replay, f"PostgreSQL replay is missing callable contract: {block}"

for migration, expected in (
    ("20261125020833_account_lifecycle_read_rpc_guards.sql", (
        "public.api_my_notifications", "public.api_my_notification",
        "public.api_my_incident_messages", "public.api_claimable_incidents",
    )),
    ("20261125020835_account_lifecycle_assignment_helper_guard.sql", (
        "private.tacresq_is_incident_assignee", "account_status = 'active'", "for share",
    )),
    ("20261125020840_account_lifecycle_chat_idempotency_guards.sql", (
        "public.api_reserve_incident_image_upload", "public.api_send_incident_message",
        "public.api_send_incident_image_message", "tacresq_auth_profile_id()",
    )),
    ("20261125020842_account_lifecycle_write_rpc_entry_guards.sql", (
        "public.api_create_incident_v4", "public.api_cancel_my_incident",
        "account_status = 'active'", "for update",
    )),
    ("20261125020846_account_lifecycle_fcm_token_guards.sql", (
        "public.api_register_fcm_token", "public.api_unregister_fcm_token",
        "tacresq_auth_profile_id_for_token_cleanup",
    )),
):
    migration_source = (ROOT / "supabase/migrations" / migration).read_text(encoding="utf-8").lower()
    for item in expected:
        assert item in migration_source, f"{migration} no longer contains expected guard: {item}"

assert "has_function_privilege('anon', routine_oid, 'execute')" in foundation_replay
print("Expanded account-lifecycle callable replay source contract passed.")

# Anonymous-callable routines are a distinct exposure surface. The catalog
# must report effective anon EXECUTE (including inherited/PUBLIC grants) for
# both exposed public RPCs and private routines, with owner and execution mode.
for required in (
    "anon-callable public routines",
    "anon-callable private routines",
    "has_function_privilege('anon', p.oid, 'execute')",
    "owner_role.rolname as routine_owner",
    "p.prokind in ('f', 'p')",
    "has_function_privilege('authenticated', p.oid, 'execute') as authenticated_execute",
):
    assert required in inventory, f"Anonymous callable inventory is incomplete: {required}"

assert inventory.count("has_function_privilege('anon', p.oid, 'execute')") >= 2
print("Anonymous callable inventory source contract passed.")

# Keep the per-signature review register complete and honest about evidence.
# The matrix separates known catalog signatures from unresolved signatures and
# distinguishes source guard evidence from runtime test execution.
matrix_files = {
    "index": ROOT / "docs/security/admin-account-suspension-signature-matrix-index.md",
    "requester": ROOT / "docs/security/admin-account-suspension-signature-matrix-batch-23-requester.md",
    "field": ROOT / "docs/security/admin-account-suspension-signature-matrix-batch-24-field-operations.md",
    "messaging": ROOT / "docs/security/admin-account-suspension-signature-matrix-batch-25-messaging-notifications.md",
    "admin": ROOT / "docs/security/admin-account-suspension-signature-matrix-batch-26-admin-legacy.md",
}
for name, path in matrix_files.items():
    assert path.is_file(), f"Per-signature authorization matrix is missing: {name}"

matrix_index = matrix_files["index"].read_text(encoding="utf-8").lower()
requester_matrix = matrix_files["requester"].read_text(encoding="utf-8").lower()
field_matrix = matrix_files["field"].read_text(encoding="utf-8").lower()
messaging_matrix = matrix_files["messaging"].read_text(encoding="utf-8").lower()
admin_matrix = matrix_files["admin"].read_text(encoding="utf-8").lower()

for required in (
    "batch 23", "batch 24", "batch 25", "batch 26",
    "a signature is a separate review unit",
    "source contract does not prove runtime denial",
    "no live supabase project is modified",
):
    assert required in matrix_index, f"Matrix index is missing review rule: {required}"

for required in (
    "api_create_incident_v4(text,text,double precision,double precision,real,text,timestamptz,text,uuid)",
    "api_cancel_my_incident(bigint)",
    "api_update_my_incident(bigint,text,text,double precision,double precision,real,text,timestamptz,text,boolean)",
    "same still-valid jwt",
):
    assert required in requester_matrix, f"Requester matrix is missing callable/test: {required}"

for required in (
    "api_assign_incident_team(bigint,bigint[])",
    "api_assign_incident(bigint,bigint)",
    "api_units()",
    "api_all_incidents()",
    "api_update_incident_status(bigint,text)",
    "exact signature intentionally unresolved",
):
    assert required in field_matrix, f"Field operations matrix is missing callable/review caveat: {required}"

for required in (
    "api_my_incident_messages(bigint,integer,bigint)",
    "api_send_incident_message(bigint,text,uuid)",
    "api_register_fcm_token(text)",
    "incident-chat-attachments",
    "zero provider calls",
):
    assert required in messaging_matrix, f"Messaging matrix is missing callable/test: {required}"

for required in (
    "api_admin_list_users_page(integer,integer,text,text)",
    "api_admin_set_user_role(bigint,text,text)",
    "api_admin_list_audit_logs_page(integer,integer,text,text,timestamptz,timestamptz,bigint,bigint)",
    "never join by email",
):
    assert required in admin_matrix, f"Admin/legacy matrix is missing callable/boundary: {required}"

print("Per-signature account-lifecycle authorization matrix source contract passed.")

# Requester incident editing is a SECURITY DEFINER entry point. Keep the
# active-account gate at the public boundary and preserve the existing edit
# validation/cooldown/audit implementation behind an inaccessible private call.
requester_edit_migration = (
    ROOT / "supabase/migrations/20261125020905_account_lifecycle_requester_edit_guard.sql"
).read_text(encoding="utf-8").lower()
requester_edit_runtime = (ROOT / "tools/test_account_lifecycle_foundation.sql").read_text(encoding="utf-8").lower()
for required in (
    "alter function public.api_update_my_incident(",
    "set schema private",
    "rename to tacresq_update_my_incident_internal",
    "revoke all on function private.tacresq_update_my_incident_internal(",
    "account_status = 'active'",
    "actor_role is distinct from 'requester'",
    "for share",
    "private.tacresq_update_my_incident_internal(",
    "grant execute on function public.api_update_my_incident(",
):
    assert required in requester_edit_migration, f"Requester edit lifecycle migration is missing: {required}"

for required in (
    "active_requester_edit_entry_contract",
    "public.api_update_my_incident(bigint,text,text,double precision,double precision,real,text,timestamp with time zone,text,boolean)",
    "private.tacresq_update_my_incident_internal(bigint,text,text,double precision,double precision,real,text,timestamp with time zone,text,boolean)",
    "requester_edit_count",
    "incident_request_edit_logs",
    "requester_last_edited_at",
):
    assert required in requester_edit_runtime, f"Requester edit replay contract is missing: {required}"

assert "same-valid-jwt" in requester_edit_migration or "same-valid-jwt" in crosswalk
print("Active requester edit lifecycle source contract passed.")

# The disposable replay must exercise the new requester-edit guard with the
# same still-valid JWT after account suspension, not merely inspect its source.
requester_edit_replay = requester_edit_runtime
suspended_block_start = requester_edit_replay.index("do $still_valid_jwt_denial$")
suspended_block_end = requester_edit_replay.index("$still_valid_jwt_denial$;", suspended_block_start)
suspended_block = requester_edit_replay[suspended_block_start:suspended_block_end]
assert "public.api_update_my_incident(" in suspended_block
assert "suspended requester unexpectedly edited an incident" in suspended_block
assert "exception when insufficient_privilege" in suspended_block
assert "set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000991', false)" in requester_edit_replay

# Older Android clients may still call the compatibility incident-create RPCs.
# Their INSERTs must remain covered by the generic active-account table trigger,
# even though these older functions do not contain the newer explicit profile
# lifecycle check in their own bodies.
legacy_create_source = (
    ROOT / "supabase/migrations/20261019214218_incident_one_time_location.sql"
).read_text(encoding="utf-8").lower()
write_guard_source = (
    ROOT / "supabase/migrations/20261125020825_account_lifecycle_write_guard_triggers.sql"
).read_text(encoding="utf-8").lower()
for signature in (
    "function public.api_create_incident(",
    "function public.api_create_incident_v2(",
):
    start = legacy_create_source.index(signature)
    end = legacy_create_source.find("$$;", start)
    assert end > start, f"Legacy requester creation body not found: {signature}"
    body = legacy_create_source[start:end]
    assert "security invoker" in body
    assert "insert into public.incidents" in body

assert "before insert or update or delete on %i.%i" in write_guard_source
assert "private.tacresq_current_account_active()" in write_guard_source
assert "account_status" not in legacy_create_source[
    legacy_create_source.index("function public.api_create_incident("):
    legacy_create_source.index("function public.api_my_incidents")
], "Compatibility create source unexpectedly changed; review its lifecycle path."

assert "legacy and current incident creation paths must be covered" in requester_edit_runtime


# Claimability and responder-coordinate reads are direct callable boundaries.
# Keep their new lifecycle migration tied to the clean-replay catalog assertions.
claimability_reader_migration = (ROOT / "supabase/migrations/20261125020907_account_lifecycle_claimability_and_responder_read_guard.sql").read_text(encoding="utf-8").lower()
for required in (
    "create or replace function public.api_set_incident_claimable",
    "v_actor.account_status is distinct from 'active'",
    "for update",
    "incident_claimability_logs",
    "only active dispatchers can toggle field claiming",
    "alter function private.tacresq_active_responder_locations()",
    "tacresq_active_responder_locations_internal",
    "create or replace function private.tacresq_active_responder_locations()",
    "account_status = 'active'",
    "for share",
    "only active dispatchers can view responder locations",
    "revoke all on function private.tacresq_active_responder_locations_internal()",
):
    assert required in claimability_reader_migration, f"Claimability/GPS lifecycle guard is missing: {required}"

foundation_replay = (ROOT / "tools/test_account_lifecycle_foundation.sql").read_text(encoding="utf-8").lower()
for required in (
    "dispatcher_claimability_lifecycle_contract",
    "v_actor.account_status is distinct from ''active''",
    "incident_claimability_logs",
    "active_responder_location_read_contract",
    "tacresq_active_responder_locations_internal",
    "has_function_privilege('authenticated', internal_oid, 'execute')",
):
    assert required in foundation_replay, f"Claimability/GPS replay contract is missing: {required}"


# The field-unit duty-status RPC is a direct authenticated mutation. Its
# profile lock must serialize with lifecycle suspension and reject suspended
# responders/drivers without silently changing their operational duty state.
duty_guard_migration = (ROOT / "supabase/migrations/20261125020913_account_lifecycle_duty_status_guard.sql").read_text(encoding="utf-8").lower()
for required in (
    "create or replace function public.api_set_duty_status(p_duty_status text)",
    "p.account_status is distinct from 'active'",
    "p.role not in ('responder', 'driver')",
    "for update",
    "finish your active incident first",
    "revoke all on function public.api_set_duty_status(text) from public, anon",
    "grant execute on function public.api_set_duty_status(text) to authenticated",
):
    assert required in duty_guard_migration, f"Duty-status lifecycle guard is missing: {required}"

duty_replay = (ROOT / "tools/test_account_lifecycle_foundation.sql").read_text(encoding="utf-8").lower()
for required in (
    "active_duty_status_callable_contract",
    "p.account_status is distinct from ''active''",
    "finish your active incident first",
    "has_function_privilege('authenticated', routine_oid, 'execute')",
):
    assert required in duty_replay, f"Duty-status replay assertion is missing: {required}"


# The exact application RPC register is independent of where PostgreSQL
# installs extensions (Supabase commonly uses the extensions schema; a plain
# replay may install pgcrypto into public). Do not count extension functions as
# TACResQ API entry points.
definer_inventory = (ROOT / "tools/test_account_suspension_definer_inventory.sql").read_text(encoding="utf-8").lower()
for required in (
    "expected_invoker_signatures text[]",
    "public.api_active_responder_locations()",
    "public.api_assign_incident_team(bigint,bigint[])",
    "public.api_dependency_health()",
    "public.api_set_duty_status(text)",
    "left(p.proname, 4) = 'api_'",
    "api_callable_count <> 38",
    "api_invoker_count <> 14",
):
    assert required in definer_inventory, f"Application callable inventory is incomplete: {required}"

trigger_grant_migration = (
    ROOT / "supabase/migrations/20261126100000_revoke_incident_trigger_function_client_execute.sql"
).read_text(encoding="utf-8").lower()
for required in (
    "revoke all on function public.tacresq_require_incident_location()",
    "from public, anon, authenticated, service_role",
    "trigger-only incident input invariant",
):
    assert required in trigger_grant_migration, f"Trigger-only function exposure guard is missing: {required}"

foundation_sql = (ROOT / "tools/test_account_lifecycle_foundation.sql").read_text(encoding="utf-8").lower()
for required in (
    "incidents_require_location_before_write",
    "public.tacresq_require_incident_location()",
    "has_function_privilege('anon', 'public.tacresq_require_incident_location()', 'execute')",
    "has_function_privilege('authenticated', 'public.tacresq_require_incident_location()', 'execute')",
    "has_function_privilege('service_role', 'public.tacresq_require_incident_location()', 'execute')",
):
    assert required in foundation_sql, f"Trigger-only function replay contract is missing: {required}"
