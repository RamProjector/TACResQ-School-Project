#!/usr/bin/env python3
"""Offline contract test for least-privilege application RPC grants."""
from pathlib import Path
import re

SQL = Path("supabase/migrations/20261021103206_restrict_anonymous_rpc_execution.sql").read_text()

FUNCTIONS = {
    "api_all_incidents": r"\(\)",
    "api_assign_incident": r"\(bigint, bigint\)",
    "api_create_incident": r"\(text, text, double precision, double precision, real\)",
    "api_me": r"\(\)",
    "api_my_incidents": r"\(\)",
    "api_register_fcm_token": r"\(text\)",
    "api_set_duty_status": r"\(text\)",
    "api_unregister_fcm_token": r"\(text\)",
    "api_update_incident_status": r"\(bigint, text\)",
}

for name, signature in FUNCTIONS.items():
    revoke = rf"REVOKE\s+EXECUTE\s+ON\s+FUNCTION\s+public\.{name}{signature}\s+FROM\s+PUBLIC,\s*anon\s*;"
    grant = rf"GRANT\s+EXECUTE\s+ON\s+FUNCTION\s+public\.{name}{signature}\s+TO\s+authenticated\s*;"
    assert re.search(revoke, SQL, re.IGNORECASE), f"Missing PUBLIC/anon revoke for {name}"
    assert re.search(grant, SQL, re.IGNORECASE), f"Missing authenticated grant for {name}"


# Check the full current client RPC surface, not just the first batch of RPCs
# hardened in the 2026-10-21 migration. These functions are used by the current
# Supabase mobile/dispatcher flows and must remain unavailable to anon/PUBLIC.
MIGRATION_SQL = "\n".join(
    migration.read_text()
    for migration in sorted(Path("supabase/migrations").glob("*.sql"))
)

ACTIVE_CLIENT_RPCS = {
    "api_admin_list_account_lifecycle_events",
    "api_admin_list_investigation_events",
    "api_admin_list_users_page",
    "api_admin_set_account_status",
    "api_admin_set_user_role",
    "api_admin_list_audit_logs",
    "api_admin_list_audit_logs_page",
    "api_admin_analytics_summary",
    "api_active_responder_locations",
    "api_all_incidents",
    "api_assign_incident",
    "api_assign_incident_team",
    "api_create_incident",
    "api_create_incident_v2",
    "api_create_incident_v3",
    "api_create_incident_v4",
    "api_cancel_my_incident",
    "api_update_my_incident",
    "api_me",
    "api_my_incidents",
    "api_my_notification",
    "api_record_location_v2",
    "api_register_fcm_token",
    "api_set_duty_status",
    "api_units",
    "api_unregister_fcm_token",
    "api_update_incident_status",
}

for name in ACTIVE_CLIENT_RPCS:
    revoke = re.compile(
        rf"REVOKE\s+(?:EXECUTE|ALL(?:\s+PRIVILEGES)?)\s+ON\s+FUNCTION\s+public\.{name}\s*"
        rf"\([^;]*?\)\s+FROM\s+PUBLIC,\s*anon(?:,\s*authenticated)?\s*;",
        re.IGNORECASE | re.DOTALL,
    )
    grant = re.compile(
        rf"GRANT\s+EXECUTE\s+ON\s+FUNCTION\s+public\.{name}\s*"
        rf"\([^;]*?\)\s+TO\s+authenticated\s*;",
        re.IGNORECASE | re.DOTALL,
    )
    assert revoke.search(MIGRATION_SQL), (
        f"Missing explicit PUBLIC/anon EXECUTE revoke across migrations for {name}"
    )
    assert grant.search(MIGRATION_SQL), (
        f"Missing explicit authenticated EXECUTE grant across migrations for {name}"
    )

# Per-responder progress is intentionally retired: the dispatcher owns the
# shared incident milestone. Keep the historical RPC names revoked rather than
# treating them as active client APIs merely because older migrations granted them.
LEGACY_DISABLED_RPCS = {
    "public.api_update_my_assignment_status": r"\(bigint,\s*text\)",
    "private.tacresq_update_my_assignment_status": r"\(bigint,\s*text\)",
}

for name, signature in LEGACY_DISABLED_RPCS.items():
    revoke = re.compile(
        rf"REVOKE\s+ALL(?:\s+PRIVILEGES)?\s+ON\s+FUNCTION\s+{re.escape(name)}{signature}\s+"
        rf"FROM\s+PUBLIC,\s*anon,\s*authenticated\s*;",
        re.IGNORECASE | re.DOTALL,
    )
    assert revoke.search(MIGRATION_SQL), (
        f"Legacy per-responder RPC {name} must be explicitly revoked from authenticated clients"
    )


# The Render bridge is an additional network boundary: every RPC it exposes must
# belong to the reviewed client RPC inventory above, whose explicit anon/PUBLIC
# revokes and authenticated grants are checked by the loop above. This prevents a
# new bridge route from being exposed just by adding its name to the PHP allowlist.
BRIDGE_CONFIG = Path("backend/src/config/tacru.php").read_text()
bridge_block = re.search(
    r"'bridge_rpc_allowlist'\s*=>\s*\[(.*?)\]",
    BRIDGE_CONFIG,
    re.IGNORECASE | re.DOTALL,
)
assert bridge_block, "Missing explicit Render bridge RPC allowlist"
bridge_rpc_list = re.findall(r"'(api_[a-z0-9_]+)'", bridge_block.group(1), re.IGNORECASE)
bridge_rpcs = set(bridge_rpc_list)
assert bridge_rpcs, "Render bridge allowlist must not be empty"
assert len(bridge_rpc_list) == len(bridge_rpcs), "Duplicate RPC in Render bridge allowlist"
assert not (bridge_rpcs & {"api_admin_list_users", "api_admin_list_users_page", "api_admin_set_user_role", "api_admin_set_account_status", "api_admin_list_account_lifecycle_events", "api_admin_list_investigation_events", "api_admin_list_audit_logs", "api_admin_list_audit_logs_page", "api_admin_analytics_summary"}), "Admin RPCs must not be exposed through the separate Laravel/Sanctum bridge"
unknown_bridge_rpcs = bridge_rpcs - ACTIVE_CLIENT_RPCS
assert not unknown_bridge_rpcs, (
    "Render bridge exposes RPCs outside the reviewed/grant-checked inventory: "
    + ", ".join(sorted(unknown_bridge_rpcs))
)

assert "DROP FUNCTION" not in SQL.upper(), "Migration must not drop application RPCs"
assert "DELETE FROM" not in SQL.upper(), "Migration must not alter user data"

table_privileges_revoke = re.compile(
    r"REVOKE\s+TRUNCATE,\s*REFERENCES,\s*TRIGGER\s+ON\s+TABLE\s+"
    r"public\.profiles,\s*public\.incidents,\s*public\.location_logs,\s*"
    r"public\.incident_status_logs,\s*public\.fcm_tokens\s+FROM\s+"
    r"PUBLIC,\s*anon,\s*authenticated\s*;",
    re.IGNORECASE | re.DOTALL,
)
assert table_privileges_revoke.search(SQL), "Missing revoke for unnecessary client table privileges"

# The earlier migration revoked advanced table privileges but left direct
# SELECT/INSERT/UPDATE/DELETE and identity-sequence privileges for anon. This
# follow-up must remove those grants without revoking authenticated privileges.
ANON_PRIVILEGES_SQL = Path(
    "supabase/migrations/20261021113046_revoke_anon_table_privileges.sql"
).read_text()

direct_table_revoke = re.compile(
    r"REVOKE\s+ALL\s+PRIVILEGES\s+ON\s+TABLE\s+"
    r"public\.profiles,\s*public\.incidents,\s*public\.location_logs,\s*"
    r"public\.incident_status_logs,\s*public\.fcm_tokens\s+FROM\s+"
    r"PUBLIC,\s*anon\s*;",
    re.IGNORECASE | re.DOTALL,
)
assert direct_table_revoke.search(ANON_PRIVILEGES_SQL), (
    "Missing revoke of direct anonymous/PUBLIC table privileges"
)

sequence_revoke = re.compile(
    r"REVOKE\s+ALL\s+PRIVILEGES\s+ON\s+SEQUENCE\s+"
    r"public\.profiles_id_seq,\s*public\.incidents_id_seq,\s*"
    r"public\.location_logs_id_seq,\s*public\.incident_status_logs_id_seq,\s*"
    r"public\.fcm_tokens_id_seq\s+FROM\s+PUBLIC,\s*anon\s*;",
    re.IGNORECASE | re.DOTALL,
)
assert sequence_revoke.search(ANON_PRIVILEGES_SQL), (
    "Missing revoke of direct anonymous/PUBLIC sequence privileges"
)
assert not re.search(
    r"FROM\s+PUBLIC,\s*anon,\s*authenticated\b",
    ANON_PRIVILEGES_SQL,
    re.IGNORECASE,
), "Follow-up migration must not revoke authenticated grants"


# Runtime SQL contracts share one disposable database with the migration replay.
# Their auth.users fixtures must therefore use disjoint UUID namespaces; otherwise
# the second script fails before its authorization assertions execute.
FIXTURE_UUID_PATTERN = r"00000000-0000-4000-8000-000000000\d{3}"
replay_fixtures = set(
    re.findall(FIXTURE_UUID_PATTERN, Path("tools/test_supabase_migration_replay.sh").read_text())
)
gps_fixtures = set(
    re.findall(FIXTURE_UUID_PATTERN, Path("tools/test_location_rpc_security.sql").read_text())
)
overlapping_fixtures = replay_fixtures & gps_fixtures
assert not overlapping_fixtures, (
    "Migration replay and GPS RPC runtime tests share auth.users fixture UUIDs: "
    + ", ".join(sorted(overlapping_fixtures))
)

print(
    f"PASS: least-privilege EXECUTE grants for {len(FUNCTIONS)} RPCs, "
    "restricted advanced table grants, and full anon/PUBLIC table/sequence revokes"
)


# incident_assignments is a client read-only table. The new migration must
# remove every anon/PUBLIC privilege and every authenticated write/DDL privilege.
ASSIGNMENT_PRIVILEGE_SQL = Path(
    "supabase/migrations/20261122050839_restrict_incident_assignment_table_privileges.sql"
).read_text()
assert re.search(
    r"REVOKE\s+ALL\s+PRIVILEGES\s+ON\s+TABLE\s+public\.incident_assignments\s+"
    r"FROM\s+PUBLIC,\s*anon,\s*authenticated\s*;",
    ASSIGNMENT_PRIVILEGE_SQL, re.IGNORECASE | re.DOTALL
), "incident_assignments must revoke all inherited/direct client privileges"
assert re.search(
    r"GRANT\s+SELECT\s+ON\s+TABLE\s+public\.incident_assignments\s+TO\s+authenticated\s*;",
    ASSIGNMENT_PRIVILEGE_SQL, re.IGNORECASE | re.DOTALL
), "authenticated users must retain RLS-filtered assignment reads"
assert "TRUNCATE" in ASSIGNMENT_PRIVILEGE_SQL.upper(), "Document the RLS-bypassing TRUNCATE risk"


# Token, status-history, and location tables are read-only to client roles.
# Their writes must pass through the reviewed RPC or server-only worker paths.
OPERATIONAL_PRIVILEGE_SQL = Path(
    "supabase/migrations/20261122050841_restrict_operational_table_writes.sql"
).read_text()
for table in ("fcm_tokens", "incident_status_logs", "location_logs"):
    assert re.search(
        rf"REVOKE\s+ALL\s+PRIVILEGES\s+ON\s+TABLE\s+public\.fcm_tokens,\s*"
        rf"public\.incident_status_logs,\s*public\.location_logs\s+"
        rf"FROM\s+PUBLIC,\s*anon,\s*authenticated\s*;",
        OPERATIONAL_PRIVILEGE_SQL, re.IGNORECASE | re.DOTALL
    ), f"Missing direct client privilege revoke for {table}"
assert re.search(
    r"GRANT\s+SELECT\s+ON\s+TABLE\s+public\.fcm_tokens,\s*"
    r"public\.incident_status_logs,\s*public\.location_logs\s+TO\s+authenticated\s*;",
    OPERATIONAL_PRIVILEGE_SQL, re.IGNORECASE | re.DOTALL
), "Authenticated RLS-filtered reads must remain available on operational tables"


# Core profile/incident tables retain the current RLS-backed create/update
# workflows, but authenticated clients must not directly delete or truncate.
CORE_TABLE_PRIVILEGE_SQL = Path(
    "supabase/migrations/20261122050843_restrict_core_table_destructive_privileges.sql"
).read_text()
assert re.search(
    r"REVOKE\s+DELETE,\s*TRUNCATE\s+ON\s+TABLE\s+"
    r"public\.profiles,\s*public\.incidents\s+FROM\s+"
    r"PUBLIC,\s*anon,\s*authenticated\s*;",
    CORE_TABLE_PRIVILEGE_SQL, re.IGNORECASE | re.DOTALL
), "profiles/incidents must revoke direct client DELETE and TRUNCATE"



# The Admin website uses authenticated Admin JWTs, not the server service_role
# identity. Keep service_role away from the append-only audit relation, its
# identity sequence, and Admin-only RPCs. The disposable replay adds catalog
# assertions for the effective privileges and preserves authenticated grants.
SERVICE_ROLE_SEQUENCE_MIGRATION = Path(
    "supabase/migrations/20261126180000_restrict_device_registry_sequences.sql"
).read_text()
RPC_OWNED_SEQUENCES = (
    "fcm_delivery_attempts_id_seq",
    "fcm_tokens_id_seq",
    "incident_assignment_events_id_seq",
    "incident_claimability_logs_id_seq",
    "incident_messages_id_seq",
    "incident_request_edit_logs_id_seq",
    "incident_status_logs_id_seq",
    "profiles_id_seq",
    "user_notifications_id_seq",
)
sequence_revokes = re.findall(
    r"REVOKE\s+ALL\s+PRIVILEGES\s+ON\s+SEQUENCE\s+(.*?)\s+"
    r"FROM\s+PUBLIC,\s*anon,\s*authenticated,\s*service_role\s*;",
    SERVICE_ROLE_SEQUENCE_MIGRATION,
    re.IGNORECASE | re.DOTALL,
)
revoked_sequence_names = " ".join(sequence_revokes)
assert sequence_revokes, "Missing explicit RPC/trigger-owned sequence revoke"
for sequence_name in RPC_OWNED_SEQUENCES:
    assert f"public.{sequence_name}" in revoked_sequence_names, (
        f"Missing sequence privilege reduction for {sequence_name}"
    )
assert re.search(
    r"GRANT\s+USAGE,\s*SELECT\s+ON\s+SEQUENCE\s+"
    r"public\.device_security_events_id_seq,\s*"
    r"public\.device_integrity_checks_id_seq\s+TO\s+service_role\s*;",
    SERVICE_ROLE_SEQUENCE_MIGRATION,
    re.IGNORECASE | re.DOTALL,
), "Trusted device registry writers must retain their minimum sequence grants"
assert re.search(
    r"GRANT\s+USAGE,\s*SELECT\s+ON\s+SEQUENCE\s+"
    r"public\.api_operation_logs_id_seq,\s*public\.incidents_id_seq,\s*"
    r"public\.location_logs_id_seq\s+TO\s+service_role\s*;",
    SERVICE_ROLE_SEQUENCE_MIGRATION,
    re.IGNORECASE | re.DOTALL,
), "Logger and legacy service-role incident/location RPCs must retain minimum sequence access"
assert re.search(
    r"REVOKE\s+ALL\s+PRIVILEGES\s+ON\s+SEQUENCE\s+"
    r"public\.incidents_id_seq,\s*public\.location_logs_id_seq\s+"
    r"FROM\s+PUBLIC,\s*anon\s*;",
    SERVICE_ROLE_SEQUENCE_MIGRATION,
    re.IGNORECASE | re.DOTALL,
), "Legacy incident/location sequences must revoke PUBLIC and anon access"
assert re.search(
    r"GRANT\s+USAGE,\s*SELECT\s+ON\s+SEQUENCE\s+"
    r"public\.incidents_id_seq\s+TO\s+authenticated\s*;",
    SERVICE_ROLE_SEQUENCE_MIGRATION,
    re.IGNORECASE | re.DOTALL,
), "Authenticated legacy incident-create RPCs must retain required sequence access"
assert re.search(
    r"REVOKE\s+ALL\s+PRIVILEGES\s+ON\s+SEQUENCE\s+"
    r"public\.location_logs_id_seq\s+FROM\s+authenticated\s*;",
    SERVICE_ROLE_SEQUENCE_MIGRATION,
    re.IGNORECASE | re.DOTALL,
), "Authenticated clients must not retain location-log sequence access"
assert re.search(
    r"REVOKE\s+UPDATE\s+ON\s+SEQUENCE\s+"
    r"public\.incidents_id_seq,\s*public\.location_logs_id_seq\s+"
    r"FROM\s+authenticated,\s*service_role\s*;",
    SERVICE_ROLE_SEQUENCE_MIGRATION,
    re.IGNORECASE | re.DOTALL,
), "Legacy sequence paths must not retain setval capability"

PG17_MAINTAIN_MIGRATION = Path(
    "supabase/migrations/20261126170000_restrict_profile_update_to_duty_status.sql"
).read_text()
assert "server_version_num" in PG17_MAINTAIN_MIGRATION
assert re.search(
    r"EXECUTE\s+'REVOKE MAINTAIN ON TABLE public\.profiles, public\.incidents "
    r"FROM PUBLIC, anon, authenticated, service_role'",
    PG17_MAINTAIN_MIGRATION,
    re.IGNORECASE | re.DOTALL,
), "PG17 MAINTAIN revoke must remain version-gated dynamic SQL"

SERVICE_ROLE_ADMIN_MIGRATION = Path(
    "supabase/migrations/20261126160000_revoke_service_role_admin_audit_access.sql"
).read_text()
assert re.search(
    r"REVOKE\s+ALL\s+PRIVILEGES\s+ON\s+TABLE\s+"
    r"public\.admin_audit_logs\s+FROM\s+service_role\s*;",
    SERVICE_ROLE_ADMIN_MIGRATION, re.IGNORECASE | re.DOTALL
), "service_role must have no direct admin_audit_logs table privileges"
assert re.search(
    r"REVOKE\s+ALL\s+PRIVILEGES\s+ON\s+SEQUENCE\s+"
    r"public\.admin_audit_logs_id_seq\s+FROM\s+PUBLIC,\s*anon,\s*authenticated,\s*service_role\s*;",
    SERVICE_ROLE_ADMIN_MIGRATION, re.IGNORECASE | re.DOTALL
), "service_role must have no direct admin_audit_logs identity-sequence privileges"

ADMIN_SERVICE_ROLE_FUNCTIONS = {
    "api_admin_analytics_summary": r"\(timestamptz,\s*timestamptz\)",
    "api_admin_analytics_summary_internal": r"\(timestamptz,\s*timestamptz\)",
    "api_admin_list_account_lifecycle_events": r"\(integer,\s*integer\)",
    "api_admin_list_audit_logs": r"\(integer\)",
    "api_admin_list_audit_logs_internal": r"\(integer\)",
    "api_admin_list_audit_logs_page": r"\(integer,\s*integer,\s*text,\s*text,\s*timestamptz,\s*timestamptz,\s*bigint,\s*bigint\)",
    "api_admin_list_investigation_events": r"\(integer,\s*integer,\s*text,\s*text,\s*text,\s*timestamptz,\s*timestamptz,\s*bigint,\s*bigint,\s*bigint\)",
    "api_admin_list_users": r"\(\)",
    "api_admin_list_users_page": r"\(integer,\s*integer,\s*text,\s*text\)",
    "api_admin_list_users_page_internal": r"\(integer,\s*integer,\s*text,\s*text\)",
    "api_admin_set_account_status": r"\(bigint,\s*text,\s*text,\s*text\)",
    "api_admin_set_user_role": r"\(bigint,\s*text,\s*text\)",
    "api_admin_set_user_role_internal": r"\(bigint,\s*text,\s*text\)",
}
for function_name, signature in ADMIN_SERVICE_ROLE_FUNCTIONS.items():
    revoke = re.compile(
        rf"REVOKE\s+EXECUTE\s+ON\s+FUNCTION\s+public\.{function_name}{signature}\s+FROM\s+service_role\s*;",
        re.IGNORECASE | re.DOTALL,
    )
    assert revoke.search(SERVICE_ROLE_ADMIN_MIGRATION), (
        f"service_role must not retain direct EXECUTE on {function_name}"
    )
assert "GRANT " not in SERVICE_ROLE_ADMIN_MIGRATION.upper(), (
    "The targeted service-role reduction must not add new grants"
)
assert Path("tools/test_service_role_admin_audit_privileges.sql").is_file(), (
    "Missing runtime catalog contract for the service-role Admin audit reduction"
)
assert "test_service_role_admin_audit_privileges.sql" in Path(
    "tools/test_supabase_migration_replay.sh"
).read_text(), "Migration replay must execute the service-role Admin audit contract"
assert (
    "ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public"
    in Path("tools/test_supabase_migration_replay.sh").read_text()
), "Migration replay must model the hosted postgres/public service_role function default ACL"
PROFILE_UPDATE_MIGRATION = Path(
    "supabase/migrations/20261126170000_restrict_profile_update_to_duty_status.sql"
).read_text()
assert re.search(
    r"REVOKE\s+UPDATE\s+ON\s+TABLE\s+public\.profiles\s+FROM\s+authenticated\s*;",
    PROFILE_UPDATE_MIGRATION, re.IGNORECASE | re.DOTALL
), "Profile update must revoke the broad authenticated table-level UPDATE grant"
assert re.search(
    r"GRANT\s+UPDATE\s*\(\s*duty_status\s*,\s*updated_at\s*\)\s+"
    r"ON\s+TABLE\s+public\.profiles\s+TO\s+authenticated\s*;",
    PROFILE_UPDATE_MIGRATION, re.IGNORECASE | re.DOTALL
), "Only duty_status and updated_at may retain authenticated profile UPDATE"
REPLAY_FIXTURE = Path("tools/test_supabase_migration_replay.sh").read_text()
for default_grant in (
    "GRANT ALL PRIVILEGES ON TABLES TO anon, authenticated, service_role",
    "GRANT ALL PRIVILEGES ON SEQUENCES TO anon, authenticated, service_role",
    "GRANT EXECUTE ON FUNCTIONS TO anon, authenticated, service_role",
):
    assert default_grant in REPLAY_FIXTURE, (
        f"Migration replay is missing hosted public default ACL fixture: {default_grant}"
    )
