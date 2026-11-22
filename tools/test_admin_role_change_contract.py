#!/usr/bin/env python3
"""Contract checks for atomic, incident-safe Admin role changes."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (
    ROOT / "supabase/migrations/20261112215653_admin_role_audit_foundation.sql"
).read_text().lower()

match = re.search(
    r"create or replace function public[.]api_admin_set_user_role[(].*?"
    r"as [$]function[$](.*?)[$]function[$];",
    migration,
    flags=re.DOTALL,
)
assert match, "Missing Admin role-change RPC implementation"
body = match.group(1)

# The target row must be serialized before its current role and eligibility
# are evaluated, preventing two concurrent Admin changes from racing.
lock = body.find("for update")
role_read = body.find("old_role := target_row.role")
assert lock >= 0 and role_read > lock, (
    "Target profile must be locked before its current role is evaluated"
)

# Admin creation/promotion/demotion remains an out-of-band trusted-operator task.
assert "p_role not in ('requester', 'responder', 'driver', 'dispatcher')" in body
assert "target_row.role = 'admin'" in body

# A field unit assigned to any active incident cannot be reclassified.
active_guard = body.find("status in ('assigned', 'en_route', 'on_scene', 'transporting')")
update = body.find("update public.profiles")
assert active_guard >= 0 and update > active_guard, (
    "Active-incident eligibility must be checked before changing the profile"
)
assert "public.incident_assignments" in body, (
    "Role changes must account for team assignment history as well as incidents.assigned_to"
)

# The profile update and append-only audit insert must stay in the same
# SECURITY DEFINER RPC transaction; do not move audit writing to the browser.
audit_insert = body.find("insert into public.admin_audit_logs")
assert update >= 0 and audit_insert > update, (
    "A successful role update must append its audit event in the same RPC"
)
assert "trim(p_reason)" in body
assert "user_role_changed" in body
assert "return jsonb_build_object" in body

print("Admin role-change locking, eligibility, role boundaries, and audit contracts passed.")
