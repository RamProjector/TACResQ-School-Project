#!/usr/bin/env python3
"""Source contract for the fail-closed Supabase Storage bucket inventory."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
inventory = (ROOT / "tools/check_storage_bucket_inventory.sql").read_text(encoding="utf-8").lower()
storage_test = (ROOT / "tools/test_incident_chat_storage_policies.sql").read_text(encoding="utf-8").lower()
policy_migration = (ROOT / "supabase/migrations/20261123180000_account_lifecycle_chat_idempotency_guards.sql").read_text(encoding="utf-8").lower()

# A new bucket must fail the release contract until its ownership, policy,
# retention, and suspended-session behavior are explicitly reviewed.
assert "from storage.buckets" in inventory
assert "where id <> 'incident-chat-attachments'" in inventory
assert "unreviewed storage bucket(s) detected" in inventory
assert "expected the reviewed private incident-chat bucket" in inventory

# The reviewed bucket is private and bounded to the MIME types/size the app
# currently supports. Do not weaken the check to a bucket-name-only assertion.
assert "public is false" in inventory
assert "file_size_limit = 5242880" in inventory
assert "image/jpeg" in inventory and "image/png" in inventory and "image/webp" in inventory

# Both object operations must remain explicitly inventoried and tied to
# current active profile + incident membership authorization.
for policy in (
    "tacresq_incident_chat_attachment_insert",
    "tacresq_incident_chat_attachment_read",
):
    assert policy in inventory, f"Missing reviewed Storage object policy: {policy}"
assert "tacresq_auth_profile_id" in inventory
assert "tacresq_auth_can_access_incident_chat" in inventory
assert "pg_get_expr(pol.polwithcheck" in inventory
assert "pg_get_expr(pol.polqual" in inventory

# The runtime SQL test must exercise suspended-session denial for both reads
# and writes, not only inspect policy text.
for marker in (
    "suspended",
    "upload",
    "download",
    "incident-chat-attachments",
):
    assert marker in storage_test, f"Storage replay test lacks {marker!r} coverage"

# Active-aware chat helpers are part of the effective Storage policy boundary.
assert "tacresq_auth_profile_id" in policy_migration
assert "account_status = 'active'" in policy_migration or "tacresq_current_account_active" in policy_migration

# Inventory output must not disclose object paths or signed URLs.
assert "metadata-only report" in inventory
assert "signed urls" in inventory
assert "object paths" in inventory
print("Supabase Storage bucket inventory contract passed.")
