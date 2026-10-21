#!/usr/bin/env python3
"""Offline contract test for the location RPC's SECURITY DEFINER search path."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = ROOT / "supabase/migrations/20261021170000_restrict_location_rpc_search_path.sql"
sql = migration.read_text().lower()

assert "alter function public.api_record_location_v2(" in sql
assert ") set search_path = pg_catalog;" in sql
assert "public, pg_temp" not in sql
assert "create or replace function" not in sql
assert "drop function" not in sql
print("Location RPC search-path migration contract passed.")
