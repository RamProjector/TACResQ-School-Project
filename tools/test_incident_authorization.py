#!/usr/bin/env python3
"""Static regression checks for incident report field authorization."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261021160000_protect_incident_report_fields.sql"
sql = MIGRATION.read_text().lower()

protected_fields = (
    "requester_id",
    "type",
    "description",
    "latitude",
    "longitude",
    "accuracy_m",
    "location_notes",
    "location_recorded_at",
    "created_at",
)
checks = {
    "guard only applies report immutability to assigned mobile roles":
        "actor_role in ('responder', 'driver')" in sql,
    "all original report fields are compared with old row values":
        all(f"new.{field} is distinct from old.{field}" in sql for field in protected_fields),
    "field tampering is rejected as an authorization error":
        "cannot modify the original emergency report" in sql and "using errcode = '42501'" in sql,
    "dispatcher assignment checks remain present":
        "actor_role <> 'dispatcher'" in sql and "unit_status <> 'available'" in sql,
    "incident status transition validation remains present":
        "invalid incident status transition" in sql and "actor_id <> old.assigned_to" in sql,
    "function uses a controlled search path":
        "set search_path to 'pg_catalog'" in sql,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} incident authorization check(s) failed")
print(f"Incident authorization source contract OK: {len(checks)} checks.")
