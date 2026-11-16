#!/usr/bin/env python3
"""Regression contract for immutable profile identity metadata."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sql = (ROOT / "supabase/migrations/20261116210000_protect_profile_identity_metadata.sql").read_text()

required = (
    "CREATE OR REPLACE FUNCTION public.tacresq_guard_profile_update()",
    "NEW.id IS DISTINCT FROM OLD.id",
    "NEW.auth_user_id IS DISTINCT FROM OLD.auth_user_id",
    "NEW.created_at IS DISTINCT FROM OLD.created_at",
    "NEW.name IS DISTINCT FROM OLD.name",
    "NEW.email IS DISTINCT FROM OLD.email",
    "NEW.phone IS DISTINCT FROM OLD.phone",
    "NEW.role IS DISTINCT FROM OLD.role",
    "OLD.role = 'admin' OR NEW.role = 'admin'",
    "NEW.duty_status NOT IN ('available', 'off_duty')",
    "i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')",
    "NEW.updated_at := now()",
    "REVOKE ALL ON FUNCTION public.tacresq_guard_profile_update() FROM PUBLIC, anon, authenticated",
)
for item in required:
    assert item in sql, f"Missing immutable profile metadata guard: {item}"

identity_check = sql.index("IF NEW.id IS DISTINCT FROM OLD.id")
depth_check = sql.index("IF pg_trigger_depth() = 1 THEN")
role_check = sql.index("IF NEW.role IS DISTINCT FROM OLD.role")
assert identity_check < depth_check < role_check, "Identity immutability must run even for nested trigger updates"
assert "IF auth.uid() IS NOT NULL AND pg_trigger_depth() = 1" not in sql
assert "CREATE OR REPLACE FUNCTION public.tacresq_guard_profile_update()" in sql

print("PASS: authenticated field units cannot change profile IDs or creation timestamps.")
print("PASS: identity, role, duty-status, and active-assignment protections are preserved.")
