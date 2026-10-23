#!/usr/bin/env python3
"""Contract checks for independent per-unit response progress."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sql = (ROOT / "supabase/migrations/20261023084000_per_responder_assignment_status.sql").read_text()

for required in (
    "ADD COLUMN IF NOT EXISTS responder_status text",
    "status_updated_at timestamptz",
    "CREATE OR REPLACE FUNCTION private.tacresq_update_my_assignment_status",
    "WHERE a.incident_id = p_incident_id AND a.user_id = actor_id",
    "Invalid response status transition for this unit",
    "bool_and(a.responder_status = 'resolved')",
    "bool_and(a.responder_status IN ('on_scene','transporting','resolved'))",
    "assigned_user_statuses",
    "SECURITY INVOKER",
    "REVOKE ALL ON FUNCTION public.api_update_my_assignment_status(bigint, text) FROM PUBLIC, anon",
    "GRANT EXECUTE ON FUNCTION public.api_update_my_assignment_status(bigint, text) TO authenticated",
):
    assert required in sql, f"Missing per-responder status safety contract: {required}"

assert "UPDATE public.profiles" not in sql, "This migration must not bypass profile duty-status safeguards."
print("PASS: individual response status is stored per assignment with server-side transition checks.")
print("PASS: incident summary advances conservatively and retains a per-unit status payload.")
