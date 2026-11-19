# TACResQ API availability and degraded-mode safeguards

**Scope:** Laravel-to-Supabase bridge resilience. This document does not claim that the mobile app has an offline dispatch service or that an outage can be handled entirely on-device.

## Implemented safeguards

- Supabase Auth user verification has a 2-second connection timeout and 5-second total request timeout.
- Supabase RPC forwarding has a 2-second connection timeout and 8-second total request timeout.
- Upstream failures are sanitized; Auth 5xx/429 and transport failures do not become false invalid-session responses.
- RPC names remain restricted to the reviewed allowlist and use the caller's validated Supabase JWT.
- RPC request bodies are limited to 32 KiB before token verification/upstream work.
- RPC arguments must be a JSON object. A genuinely empty object is allowed for no-argument RPCs; non-empty top-level arrays are rejected.
- Per-IP and per-authenticated-user request ceilings remain in place.
- API responses use the existing no-store privacy headers.

## Client retry and idempotency rules

1. A timeout is an unknown outcome, not proof that the server did nothing.
2. For incident creation, retain and reuse the same client-generated request UUID for every retry of the same logical submission. Do not create a new UUID merely because the response was lost.
3. Never blindly retry assignment, cancellation, status transition, or other state-changing RPCs. Refresh authoritative incident state first; only retry after determining the prior operation did not commit.
4. Apply bounded exponential backoff with jitter for safe reads and retryable upstream failures. Respect server Retry-After when supplied. Do not retry validation, authorization, or other 4xx errors.
5. Keep emergency reporting available when device integrity or device registration services are unavailable; do not silently mark an unconfirmed incident as submitted.
6. If connectivity is lost, clearly distinguish locally queued, sending, confirmed by server, and failed states. Do not present a local queue item as a dispatched incident.

## Operational response

- Monitor /up for Laravel process health, but do not treat it as proof that Supabase Auth, PostgREST, database RPCs, FCM, or routing providers are healthy.
- Synthetic checks should separately verify Auth validation, a harmless allowlisted read RPC, and notification/routing dependencies without creating real incidents.
- Alert on sustained upstream timeouts/5xx/429, rising 502/503 rates, and latency. Avoid logging bearer tokens, request bodies, coordinates, or personal data.
- During an upstream outage, preserve sanitized error responses and correlation IDs. Avoid automatic retry storms or failing open on authentication/authorization.
- Recovery verification should include duplicate-SOS replay, incident state consistency, notification delivery status, and audit continuity.

## Not yet implemented / not claimed

- No circuit breaker is enabled. The Render service is currently a single Free web service and its file cache is not a reliable distributed coordination store.
- No durable offline incident queue or guaranteed background delivery is claimed by this backend change.
- No alerting destination, external uptime monitor, or on-call escalation channel has been configured.
- No automatic failover database or alternate Auth provider exists.
- No production configuration, deployment, or migration was changed by this work.

## Follow-up prerequisites

Before adding circuit breaking or background retries, select a shared atomic cache/queue backend and prove its availability characteristics on the chosen hosting tier. Before enabling automatic retry in the Android client, verify every mutation's idempotency contract and test process death, airplane mode, captive portals, duplicate taps, and lost responses.
