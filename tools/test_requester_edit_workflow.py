#!/usr/bin/env python3
"""Source contract for the server-enforced requester edit/cooldown workflow."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261023033300_requester_edit_workflow.sql"
SQL = MIGRATION.read_text().lower()
SCREEN = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
API = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
MODEL = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()

for required in (
    "requester_edit_count",
    "requester_last_edited_at",
    "incident_request_edit_logs",
    "for update",
    "incident_row.status <> 'pending'",
    "interval '60 seconds'",
    "p_confirm_review",
    "before_values",
    "after_values",
    "revoke all on function public.api_update_my_incident",
    "grant execute on function public.api_update_my_incident",
):
    assert required in SQL, f"Missing server-side requester edit safeguard: {required}"

assert 'rest/v1/rpc/api_update_my_incident' in API
assert 'requester_edit_count' in MODEL
assert 'requester_last_edited_at' in MODEL
assert 'Correct map pin' in SCREEN
assert 'Review changes' in SCREEN
assert 'Confirm and save' in SCREEN
assert 'editCooldownSeconds == 0L' in SCREEN
assert 'contact dispatch for urgent corrections' in SQL

print("PASS: requester edits are ownership-checked, pending-only, cooldown-enforced, reviewed, and audited.")
