#!/usr/bin/env python3
"""Offline contract test for SECURITY DEFINER trigger search paths."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sql = (ROOT / "supabase/migrations/20261021103144_restrict_trigger_search_paths.sql").read_text().lower()
helper_sql = (ROOT / "supabase/migrations/20261021103158_restrict_actor_helper_search_path.sql").read_text().lower()

for signature in (
    "alter function public.tacresq_guard_profile_update()",
    "alter function public.tacresq_log_incident_status()",
    "alter function public.tacresq_profile_on_signup()",
):
    assert signature in sql, f"missing hardened trigger function: {signature}"
assert sql.count("set search_path = pg_catalog;") == 3
assert "pg_temp" not in sql
assert "alter function private.tacresq_actor_role()" in helper_sql
assert "set search_path = pg_catalog;" in helper_sql
assert "pg_temp" not in helper_sql
print("SECURITY DEFINER trigger and actor-helper search-path contract passed.")
