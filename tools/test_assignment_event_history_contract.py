#!/usr/bin/env python3
"""Guard the assignment event ledger's append-only and dispatcher-only read boundary."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (
    ROOT
    / "supabase/migrations/20261122050834_incident_assignment_event_history.sql"
).read_text()

# The ledger records membership changes and incident closure as distinct events.
assert "CREATE TABLE IF NOT EXISTS public.incident_assignment_events" in migration
assert "('assigned', 'released', 'incident_closed')" in migration
assert "('assignment_insert', 'assignment_delete', 'incident_status')" in migration
assert "occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()" in migration

# Clients receive read access only; RLS limits that read to dispatch/admin profiles.
assert "ALTER TABLE public.incident_assignment_events ENABLE ROW LEVEL SECURITY" in migration
assert "FOR SELECT TO authenticated" in migration
assert "p.role IN ('dispatcher', 'admin')" in migration
assert "REVOKE ALL ON TABLE public.incident_assignment_events FROM PUBLIC, anon, authenticated" in migration
assert "GRANT SELECT ON TABLE public.incident_assignment_events TO authenticated" in migration
assert "FOR INSERT" not in migration
assert "FOR UPDATE" not in migration
assert "FOR DELETE" not in migration

# Only private SECURITY DEFINER triggers write the event stream.
for function_name in (
    "private.tacresq_log_assignment_membership_event()",
    "private.tacresq_log_incident_team_closure()",
):
    assert f"CREATE OR REPLACE FUNCTION {function_name}" in migration
    assert "SECURITY DEFINER" in migration
    assert "SET search_path = pg_catalog" in migration
    assert f"REVOKE ALL ON FUNCTION {function_name}" in migration

assert "AFTER INSERT OR DELETE ON public.incident_assignments" in migration
assert "AFTER UPDATE OF status ON public.incidents" in migration
assert "NEW.assigned_by" in migration
assert "SELECT p.id INTO actor_id" in migration

print("PASS: assignment history is trigger-written and append-only to clients.")
print("PASS: only authenticated dispatch/admin profiles can read the ledger.")
