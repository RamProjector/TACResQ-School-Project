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
assert "for update of i;" in boundary, "location writer must lock the incident row while checking en-route status"
assert "pg_advisory_xact_lock(actor_id)" in boundary
assert "interval '8 seconds'" in boundary
assert "interval '15 minutes'" in boundary
assert "p_latitude < -90" in boundary and "p_longitude > 180" in boundary
print("Location RPC search-path and privilege-boundary contract passed.")

# New GPS isolation migrations are checked separately so this test remains useful
# even when the historical migration boundary changes.
writer = (ROOT / "supabase/migrations/20261025170000_isolate_per_responder_location_writer.sql").read_text().lower()
reader = (ROOT / "supabase/migrations/20261026010000_isolate_multi_responder_location_reader.sql").read_text().lower()
assert "add column if not exists received_at timestamptz" in writer
assert "alter column received_at set default pg_catalog.clock_timestamp()" in writer
assert "location_logs_received_rate_idx" in writer
assert "l.created_at" not in writer and "coalesce(l.received_at, l.recorded_at)" in writer
assert "for update of i, a;" in writer
assert "pg_catalog.pg_advisory_xact_lock(actor_id)" in writer
assert "revoke insert on table public.location_logs from public, anon, authenticated" in writer
assert "revoke all on function private.tacresq_record_location_v2(" in writer
assert "from public, anon" in writer
assert "grant execute on function private.tacresq_record_location_v2(" in writer
assert "grant execute on function public.api_record_location_v2(" in writer
assert "revoke all on function public.api_record_location_v2(" in writer
assert "grant execute on function public.api_active_responder_locations() to authenticated" in writer
assert "revoke all on function public.api_active_responder_locations() from public, anon" in writer
assert "create or replace function public.api_record_location_v2(" in writer
assert "security invoker" in writer and "set search_path = pg_catalog" in writer
assert "create or replace function private.tacresq_active_responder_locations()" in reader
assert "grant usage on schema private to authenticated" in writer
assert "grant usage on schema private to authenticated" in reader
assert "security definer" in reader and "set search_path = pg_catalog" in reader
assert "private.tacresq_actor_role() is distinct from 'dispatcher'" in reader
assert "a.responder_status = 'en_route'" in reader
assert "order by i.id, l.user_id, coalesce(l.received_at, l.recorded_at) desc" in reader
assert "create or replace function public.api_active_responder_locations()" in reader
assert "security invoker" in reader
assert "revoke all on function private.tacresq_active_responder_locations() from public, anon" in reader
assert "grant execute on function private.tacresq_active_responder_locations() to authenticated" in reader
assert "revoke all on function public.api_active_responder_locations() from public, anon" in reader
assert "grant execute on function public.api_active_responder_locations() to authenticated" in reader
assert "l.recorded_at >= pg_catalog.now() - interval '2 minutes'" in reader
assert "l.recorded_at <= pg_catalog.now()" in reader
print("PASS: isolated GPS writer/reader privilege boundaries and server receipt-time contracts.")
