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
assert "20 public" in crosswalk
assert "52 authenticated-callable public security invoker functions" in crosswalk
assert "not an automated proof" in crosswalk or "not proof" in crosswalk
assert "call-graph" in review
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
dispatch_migration = (ROOT / "supabase/migrations/20261124070000_account_lifecycle_dispatch_progress_guard.sql").read_text(encoding="utf-8").lower()
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
assignment_migration = (ROOT / "supabase/migrations/20261124080000_account_lifecycle_team_assignment_guard.sql").read_text(encoding="utf-8").lower()
release_migration = (ROOT / "supabase/migrations/20261124090000_account_lifecycle_assignment_release_guard.sql").read_text(encoding="utf-8").lower()
location_migration = (ROOT / "supabase/migrations/20261124100000_account_lifecycle_location_writer_guard.sql").read_text(encoding="utf-8").lower()
for source, required in (
    (assignment_migration, (
        "alter function public.api_assign_incident_team(bigint, bigint[]) set schema private",
        "tacresq_assign_incident_team_internal",
        "account_status = 'active'",
        "for share",
        "only active dispatchers can assign incident teams",
        "create or replace function public.api_assign_incident",
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
    "tacresq_assign_incident_team_internal",
    "tacresq_release_incident_assignment_internal",
    "tacresq_record_location_v2_internal",
):
    assert item in runtime_contract, f"Assignment/location replay contract is missing: {item}"


# Dispatcher roster and incident-queue reads must use the active lifecycle
# boundary. The roster SECURITY DEFINER body is preserved privately, while its
# public wrapper owns the active-profile share lock.
dispatcher_read_migration = (ROOT / "supabase/migrations/20261124110000_account_lifecycle_dispatcher_directory_guard.sql").read_text(encoding="utf-8").lower()
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
