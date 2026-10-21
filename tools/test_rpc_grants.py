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
    "supabase/migrations/20261021120000_revoke_anon_table_privileges.sql"
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
assert "authenticated" not in ANON_PRIVILEGES_SQL.lower(), (
    "Follow-up migration must preserve authenticated grants"
)

print(
    f"PASS: least-privilege EXECUTE grants for {len(FUNCTIONS)} RPCs, "
    "restricted advanced table grants, and full anon/PUBLIC table/sequence revokes"
)
