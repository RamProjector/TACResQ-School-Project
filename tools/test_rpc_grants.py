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
print(f"PASS: least-privilege EXECUTE grant contract for {len(FUNCTIONS)} RPCs and restricted table grants")
