#!/usr/bin/env python3
"""Offline contract test for least-privilege application RPC grants."""
from pathlib import Path
import re

SQL = Path("supabase/migrations/20261021190000_restrict_anonymous_rpc_execution.sql").read_text()

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
print(f"PASS: least-privilege EXECUTE grant contract for {len(FUNCTIONS)} RPCs")
