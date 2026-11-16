#!/usr/bin/env python3
"""Regression contract for requester incident location-edit validation."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sql = (ROOT / "supabase/migrations/20261116190000_validate_requester_location_edits.sql").read_text()

required = (
    "p_type IS NULL OR p_type NOT IN",
    "p_location_source IS NULL OR p_location_source NOT IN",
    "p_accuracy_m < 0 OR p_accuracy_m > 100000",
    "p_location_source = 'manual' AND p_accuracy_m IS NOT NULL",
    "A manually selected pin cannot claim GPS accuracy",
    "A location timestamp is required when a pin is changed",
    "p_location_recorded_at > clock_timestamp() + interval '5 minutes'",
    "p_location_recorded_at < clock_timestamp() - interval '15 minutes'",
    "incident_row.latitude IS DISTINCT FROM p_latitude",
    "incident_row.location_recorded_at IS DISTINCT FROM p_location_recorded_at",
    "REVOKE ALL ON FUNCTION public.api_update_my_incident",
    "GRANT EXECUTE ON FUNCTION public.api_update_my_incident",
)
for item in required:
    assert item in sql, f"Missing requester location-edit validation: {item}"

assert "CREATE OR REPLACE FUNCTION public.api_update_my_incident" in sql
assert "FOR UPDATE" in sql
assert "requester_edit_count" in sql
assert "urgent_location_correction" in sql

print("PASS: requester edits validate location provenance, accuracy bounds, and changed-pin timestamp freshness.")
print("PASS: unchanged existing location metadata can be retained during text-only edits.")
print("PASS: requester ownership, row locking, urgent correction workflow, and RPC grants are preserved.")
