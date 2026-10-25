#!/usr/bin/env python3
"""Offline contract test for the location RPC privilege boundary and search paths."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = ROOT / "supabase/migrations/20261021103137_restrict_location_rpc_search_path.sql"
sql = migration.read_text().lower()

assert "alter function public.api_record_location_v2(" in sql
assert ") set search_path = pg_catalog;" in sql
assert "public, pg_temp" not in sql
assert "create or replace function" not in sql
assert "drop function" not in sql

# Historical isolation migration remains part of the migration history, but its
# lead-unit-only authorization must not be restored as the current implementation.
boundary = (ROOT / "supabase/migrations/20261021103213_isolate_location_writer.sql").read_text().lower()
assert "create or replace function private.tacresq_record_location_v2(" in boundary
assert "language plpgsql security definer set search_path = pg_catalog" in boundary
assert "create or replace function public.api_record_location_v2(" in boundary
assert "language sql security invoker set search_path = pg_catalog" in boundary
assert "select private.tacresq_record_location_v2(" in boundary
assert "revoke all on function private.tacresq_record_location_v2(" in boundary
assert "grant execute on function private.tacresq_record_location_v2(" in boundary
assert "revoke all on function public.api_record_location_v2(" in boundary
assert "grant execute on function public.api_record_location_v2(" in boundary
assert "auth.uid()" in boundary
assert "for update of i;" in boundary
assert "pg_advisory_xact_lock(actor_id)" in boundary
assert "interval '8 seconds'" in boundary
assert "interval '15 minutes'" in boundary
assert "p_latitude < -90" in boundary and "p_longitude > 180" in boundary

# The latest implementation must preserve per-responder authorization while
# moving privileged writes out of the API-exposed public schema.
current = (
    ROOT
    / "supabase/migrations/20261025170000_isolate_per_responder_location_writer.sql"
).read_text().lower()
assert "create or replace function private.tacresq_record_location_v2(" in current
assert "language plpgsql" in current and "security definer" in current
assert "set search_path = pg_catalog" in current
assert "create or replace function public.api_record_location_v2(" in current
assert "language sql" in current and "security invoker" in current
assert "set search_path = pg_catalog" in current
assert "select private.tacresq_record_location_v2(" in current
assert "p.auth_user_id = auth.uid()" in current
assert "p.role in ('responder', 'driver')" in current
assert "a.user_id = p.id" in current
assert "assignment_status is distinct from 'en_route'" in current
assert "incident_status is distinct from 'en_route'" in current
assert "for update of i;" in current
assert "pg_catalog.pg_advisory_xact_lock(actor_id)" in current
assert "interval '8 seconds'" in current
assert "interval '15 minutes'" in current
assert "interval '5 minutes'" in current
assert "p_latitude < -90" in current and "p_longitude > 180" in current
assert "revoke all on function private.tacresq_record_location_v2(" in current
assert "grant execute on function private.tacresq_record_location_v2(" in current
assert "revoke all on function public.api_record_location_v2(" in current
assert "grant execute on function public.api_record_location_v2(" in current
assert "public, pg_temp" not in current
assert "set search_path = public" not in current

print("Location RPC search-path and privilege-boundary contract passed.")
