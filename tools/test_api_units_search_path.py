#!/usr/bin/env python3
"""Guard the dispatcher unit RPC against search_path regression."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (
    ROOT / "supabase/migrations/20261122050807_pin_api_units_search_path.sql"
).read_text().lower()
definition = (
    ROOT / "supabase/migrations/20261023081706_dispatch_team_assignment.sql"
).read_text().lower()

assert "alter function public.api_units()" in migration
assert "set search_path = pg_catalog;" in migration
assert "create or replace function" not in migration
assert "drop function" not in migration
assert "revoke " not in migration and "grant " not in migration

# Preserve the established privileged behavior and the explicit dispatcher gate.
api_units_start = definition.index("create or replace function public.api_units()")
api_units_end = definition.index("create or replace function public.api_set_duty_status", api_units_start)
api_units = definition[api_units_start:api_units_end]
assert "security definer" in api_units
assert "auth.uid()" in api_units
assert "role = 'dispatcher'" in api_units
assert "from public.profiles" in api_units
assert "from public.incident_assignments" in api_units
assert "join public.incidents" in api_units

print("PASS: api_units keeps its dispatcher authorization and SECURITY DEFINER behavior while pinning lookup to pg_catalog.")
