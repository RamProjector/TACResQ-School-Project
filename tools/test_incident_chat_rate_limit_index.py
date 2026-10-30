#!/usr/bin/env python3
"""Regression contract for the incident-chat sender rate-limit index."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT_SQL = (ROOT / "supabase/migrations/20261026200000_incident_scoped_chat.sql").read_text()
INDEX_SQL = (ROOT / "supabase/migrations/20261030130000_incident_message_rate_limit_index.sql").read_text()

checks = {
    "rate limit filters by sender": "WHERE m.sender_id = actor_profile_id" in CHAT_SQL,
    "rate limit uses a rolling one-minute window": "m.created_at >= now() - interval '1 minute'" in CHAT_SQL,
    "concurrent sends are serialized before counting": "pg_advisory_xact_lock(actor_profile_id)" in CHAT_SQL,
    "index uses sender as its leading key": "ON public.incident_messages (sender_id, created_at DESC)" in INDEX_SQL,
    "index migration is repeatable": "CREATE INDEX IF NOT EXISTS incident_messages_sender_created_at_idx" in INDEX_SQL,
}
for label, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {label}")
    assert passed, f"Incident-chat rate-limit index contract failed: {label}"

print(f"Incident-chat rate-limit index contract OK: {len(checks)} checks.")
