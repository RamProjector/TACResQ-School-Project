#!/usr/bin/env python3
"""Guard the notification Edge Function across the account-state rollout.

Before the lifecycle column exists, this test prevents an unsafe partial
implementation. Once a migration adds the column, it requires actor and
recipient enforcement to arrive in the same change.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
FUNCTION = ROOT / "supabase/functions/tacresq-notify/index.ts"
MIGRATIONS = ROOT / "supabase/migrations"
CONTRACT = ROOT / "docs/security/admin-account-suspension-notification-edge-test-contract.md"

source = FUNCTION.read_text()
migration_text = "\n".join(path.read_text() for path in sorted(MIGRATIONS.glob("*.sql")))
has_account_state = bool(re.search(r"\baccount_status\b", migration_text, re.IGNORECASE))

# Existing authentication and event-specific authorization must not regress.
assert "admin.auth.getUser(userToken)" in source, "Notification function must validate the bearer token with Auth."
assert '["incident_created", "incident_assigned", "incident_status"]' in source, "Unexpected notification event surface."
assert 'actor.role !== "requester" || actor.id !== incident.requester_id' in source
assert 'actor.role !== "dispatcher"' in source
assert '!["responder", "driver"].includes(actor.role)' in source
assert "admin.from(\"fcm_tokens\").select" in source, "FCM registration lookup disappeared."

if has_account_state:
    # The lifecycle schema and its service-role consumer must ship atomically.
    actor_query = re.search(r'from\(["\']profiles["\']\).*?select\(["\']([^"\']*)["\']\)', source, re.DOTALL)
    assert actor_query and "account_status" in actor_query.group(1), (
        "account_status migration exists but notification actor lookup does not read it."
    )
    assert re.search(r"actor\.account_status\s*!==?\s*[\"']active[\"']", source), (
        "Notification function must fail closed unless the actor is active."
    )
    assert re.search(r"account_status.*active|active.*account_status", source, re.IGNORECASE), (
        "Recipient selection must explicitly filter active accounts before FCM token lookup."
    )
else:
    assert "does not establish account lifecycle state" in CONTRACT.read_text()
    assert "no account lifecycle column" in CONTRACT.read_text()
    assert "account_status" not in source, (
        "Do not query a lifecycle column before its migration is present."
    )

print(
    "PASS: notification authorization baseline retained; "
    + ("account-state actor/recipient enforcement detected." if has_account_state
       else "runtime suspension remains explicitly gated on the schema migration.")
)
