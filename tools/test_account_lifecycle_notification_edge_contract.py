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
recipient_lookup = source.index("const { data: activeRecipients", source.index("let targetProfileIds"))
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
assert 'if (dispatcherError) return json({ error: "Could not resolve notification recipients" }, 500);' in source
assert 'if (targetProfileIds.length === 0) return json({ sent: 0, reason: "No active target accounts are provisioned" });' in source
assert 'if (!registrations?.length) return json({ sent: 0, reason: "Recipients have not registered an FCM token" });' in source

# Each event must establish its own role/ownership relationship before any
# recipient lookup; an active account alone is not sufficient authorization.
created_branch = source.index('if (event === "incident_created")')
assigned_branch = source.index('} else if (event === "incident_assigned")', created_branch)
status_branch = source.index('} else {', assigned_branch)
for marker, branch_start, branch_end in (
    ('actor.role !== "requester" || actor.id !== incident.requester_id', created_branch, assigned_branch),
    ('actor.role !== "dispatcher" || incident.status !== "assigned" || !incident.assigned_to', assigned_branch, status_branch),
    ('!["responder", "driver"].includes(actor.role) || actor.id !== incident.assigned_to', status_branch, recipient_lookup),
):
    position = source.index(marker, branch_start, branch_end)
    assert branch_start < position < recipient_lookup, (
        f"Event authorization must precede recipient resolution: {marker}"
    )

# The service-role and FCM credentials are server-only. Configuration or
# recipient failures must return before Google OAuth/FCM network side effects.
assert 'Deno.env.get("SUPABASE_URL")' in source
assert 'Deno.env.get(serviceRoleEnvName)' in source
assert 'Deno.env.get("FCM_SERVICE_ACCOUNT_JSON")' in source
fcm_secret_lookup = source.index('Deno.env.get("FCM_SERVICE_ACCOUNT_JSON")')
assert token_lookup < fcm_secret_lookup < oauth_call, (
    "FCM configuration must not be disclosed before an authenticated, authorized recipient path reaches delivery."
)
assert "request.json()" in source
assert "payload.profile_id" not in source and "payload.user_id" not in source, (
    "Notification identity and recipient selection must not trust client-supplied profile IDs."
)
secret_validation = source.index('if (!googleAccount.client_email || !googleAccount.private_key || !googleAccount.project_id || !googleAccount.token_uri)')
assert token_lookup < secret_validation < oauth_call < fcm_send, (
    "Validate server-side FCM configuration before OAuth or delivery."
)
assert "json({ error: serviceKey" not in source
assert "json({ error: serviceAccountSecret" not in source
# Provider network failures must be converted into sanitized failures; a
# single FCM transport failure must not abort delivery to later recipients.
oauth_failure_guard = source.index('return json({ error: "Notification provider authentication failed" }, 502)')
fcm_network_guard = source.index('console.error(`FCM network request failed for registration ${registration.id}`)')
fcm_loop = source.index('for (const registration of registrations)')
assert oauth_call < oauth_failure_guard < fcm_loop < fcm_network_guard, (
    "OAuth and per-recipient FCM transport failures must be handled without exposing provider details."
)
assert 'catch {\n    console.error("Google OAuth token request failed.");' in source
assert 'catch {\n      console.error(`FCM network request failed for registration ${registration.id}`);' in source

assert "console.error" in source and "private_key" not in source.split("console.error", 1)[1], (
    "Provider failures must not log service-account private-key material."
)


# Request/authentication preflight must reject malformed callers before any
# profile, incident, recipient-token, OAuth, or FCM work can occur.
method_guard = source.index('if (request.method !== "POST")');
bearer_guard = source.index('if (!userToken)', method_guard);
auth_lookup = source.index("admin.auth.getUser(userToken)");
invalid_auth_guard = source.index("if (authError || !userResult.user)", auth_lookup);
body_parse = source.index("payload = await request.json()", invalid_auth_guard);
event_validation = source.index('if (!["incident_created", "incident_assigned", "incident_status"].includes(event))', body_parse);
assert method_guard < bearer_guard < auth_lookup < invalid_auth_guard < body_parse < event_validation < actor_lookup, (
    "HTTP method, bearer authentication, and event validation must precede account and incident lookups."
);
assert invalid_auth_guard < actor_lookup < incident_lookup < recipient_lookup < token_lookup < oauth_call < fcm_send, (
    "Unauthenticated requests must not reach profile/incident/token reads or provider side effects."
)
assert 'return json({ error: "Invalid or expired session" }, 401)' in source
assert 'return json({ error: "POST required" }, 405)' in source
assert 'return json({ error: "Authorization bearer token required" }, 401)' in source

# Malformed payloads and unsupported events must not reach actor/incident
# reads, recipient resolution, or any provider network call.
json_error = source.index('catch { return json({ error: "Invalid JSON body" }, 400); }')
object_shape_guard = source.index('if (!payload || typeof payload !== "object" || Array.isArray(payload))')
required_fields = source.index('if (typeof event !== "string" || !Number.isSafeInteger(incidentId) || incidentId <= 0)')
unsupported_event = source.index('if (!["incident_created", "incident_assigned", "incident_status"].includes(event))')
assert body_parse < json_error < object_shape_guard < required_fields < unsupported_event < actor_lookup
assert 'typeof rawIncidentId === "string" && /^\\\\d+$/.test(rawIncidentId)' in source
assert unsupported_event < recipient_lookup < oauth_call < fcm_send, (
    "Invalid payloads and unsupported event types must be rejected before delivery."
)

# Never let secret-provided OAuth endpoints become arbitrary outbound URLs,
# and reject malformed Firebase project identifiers before constructing FCM URLs.
token_uri_validation = source.index('const tokenUri = new URL(googleAccount.token_uri);')
project_id_validation = source.index('if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(googleAccount.project_id))')
oauth_invocation = source.index('getGoogleAccessToken(googleAccount)', token_uri_validation)
assert token_uri_validation < project_id_validation < oauth_invocation
assert 'tokenUri.protocol !== "https:"' in source
assert 'tokenUri.hostname !== "oauth2.googleapis.com"' in source
assert 'tokenUri.pathname !== "/token"' in source
assert 'tokenUri.search || tokenUri.hash' in source
assert 'tokenUri.port || tokenUri.username || tokenUri.password' in source

# Do not delete a registration merely because the FCM endpoint returned
# HTTP 404: project/configuration errors can also produce 404 responses.
assert 'function isUnregisteredFcmError(payload: unknown): boolean {' in source
assert 'item["@type"] === "type.googleapis.com/google.firebase.fcm.v1.FcmError"' in source
assert 'item.errorCode === "UNREGISTERED"' in source
assert 'if (errorObject.status === "UNREGISTERED") return true;' in source
assert 'if (isUnregisteredFcmError(errorPayload)) invalidTokenIds.push(registration.id as number);' in source
assert 'errorBody.includes("UNREGISTERED")' not in source
assert 'response.status === 404' not in source

# Stale-token cleanup is best-effort after delivery. Surface cleanup failure
# without converting already-attempted sends into a retryable delivery error.
cleanup_block = source.index('let cleanupPending = false;')
cleanup_delete = source.index('.from("fcm_tokens").delete()', cleanup_block)
cleanup_failure = source.index('cleanupPending = true;', cleanup_delete)
cleanup_response = source.index('cleanup_pending: true', cleanup_failure)
assert fcm_loop < cleanup_block < cleanup_delete < cleanup_failure < cleanup_response
assert 'try {\n      const { error: cleanupError } = await admin.from("fcm_tokens").delete().in("id", invalidTokenIds);' in source
assert 'if (cleanupError) cleanupPending = true;' in source
assert '    } catch {\n      cleanupPending = true;\n    }' in source
assert 'if (cleanupPending) console.error("Invalid FCM registration cleanup failed.");' in source
assert 'return json({ sent, attempted: registrations.length, ...(cleanupPending ? { cleanup_pending: true } : {}) });' in source

print("Notification Edge Function authorization-order source contract passed.")
