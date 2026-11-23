#!/usr/bin/env python3
"""Offline authorization-order contract for the legacy notification Edge Function.

This does not emulate Supabase Auth, Deno networking, Google OAuth, or FCM.
Those remain isolated-project integration tests and are explicitly not claimed
as executed by this source-level guard.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "supabase/functions/tacresq-notify/index.ts").read_text(encoding="utf-8")

actor_lookup = source.index('.from("profiles").select("id,role,account_status")')
actor_denial = source.index('actor.account_status !== "active"', actor_lookup)
incident_lookup = source.index('.from("incidents")', actor_denial)
recipient_lookup = source.index('.from("profiles")', source.index("let targetProfileIds"))
recipient_active_filter = source.index('.eq("account_status", "active")', recipient_lookup)
token_lookup = source.index('.from("fcm_tokens")', recipient_active_filter)
oauth_call = source.index("getGoogleAccessToken(googleAccount)", token_lookup)
fcm_send = source.index('https://fcm.googleapis.com/v1/projects/', oauth_call)

assert actor_lookup < actor_denial < incident_lookup, (
    "Actor lifecycle must be checked before incident lookup."
)
assert recipient_lookup < recipient_active_filter < token_lookup, (
    "Recipient lifecycle must be filtered before FCM token lookup."
)
assert token_lookup < oauth_call < fcm_send, (
    "Google OAuth and FCM calls must occur only after actor and recipient authorization."
)
assert "actorError || !actor || actor.account_status !== \"active\"" in source, (
    "Missing/failed actor profile lookup must fail closed."
)
assert 'if (recipientError) return json({ error: "Could not verify notification recipients" }, 500);' in source
assert 'if (targetProfileIds.length === 0) return json({ sent: 0, reason: "No active target accounts are provisioned" });' in source
assert 'if (!registrations?.length) return json({ sent: 0, reason: "Recipients have not registered an FCM token" });' in source
print("Notification Edge Function authorization-order source contract passed.")
