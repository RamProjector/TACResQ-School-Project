# Research: safe routing rollout controls

**Date:** 2026-11-07  
**Scope:** TACResQ target 2 production-readiness work  
**Status:** Research-only prerequisite for the routing rollout implementation

## Question

What control is needed before enabling TACResQ's in-app route preview for any live or QA traffic, and how can routing be withdrawn without shipping a new Android APK?

## Existing architecture

TACResQ already has three relevant boundaries:

1. Android route preview is disabled by default and requires an explicit build-time opt-in.
2. The Android client calls the authenticated TACResQ route-preview proxy rather than contacting Geoapify or OSRM directly.
3. The backend selects exactly one provider, discloses that provider before consent, verifies the responder/driver assignment, and does not silently fail over to another provider.

The existing design protects provider credentials and limits the coordinates sent upstream, but the backend has no independent runtime kill switch. A previously enabled APK could continue requesting routes after a provider incident, privacy decision, or deployment mistake unless the backend itself rejected the requests.

## Safety and operational findings

A backend rollout gate should be evaluated before authentication and before any Supabase or routing-provider call. This gives operators a deterministic rollback path and prevents disabled traffic from disclosing tokens, account state, incident coordinates, or provider details. The disabled response should be stable and sanitized so clients can present a generic unavailable state.

The gate must be independent from Android build flags. Build flags control which APKs are capable of requesting route previews; the backend flag controls whether the service will honor those requests. Both should be required for enablement. The provider remains manually selected, and routing failures must continue to use the deliberate external-Maps fallback rather than automatic cross-provider failover.

The default must remain disabled. Enabling requires provider configuration, privacy review, synthetic-coordinate checks, monitoring, and physical-device QA. Rollback should be a configuration change followed by a deployment and a synthetic verification that both route-preview endpoints return the disabled response without contacting Supabase or the provider.

## Proposed implementation boundary

This research supports a narrowly scoped implementation:

- Add a server-only `ROUTE_PREVIEW_ENABLED` environment setting with a false default.
- Guard provider disclosure and route-preview endpoints with the setting.
- Return a sanitized `503` while disabled, before bearer-token processing and upstream calls.
- Add automated tests for both disabled endpoints and preserve all existing enabled-provider tests.
- Document enablement and rollback; do not change live environment variables or provider credentials.

This does **not** authorize enabling routing, running a live provider request, changing Render configuration, or claiming physical-device validation.

## Acceptance criteria for the implementation

- Disabled by default in the checked-in environment template.
- Both endpoints fail closed with no upstream HTTP call when disabled.
- Existing authorization, provider disclosure, rate limiting, coordinate minimization, and no-failover behavior remain intact when enabled.
- CI runs backend feature tests and the existing routing/security contracts.
- Live provider behavior, deployment state, and physical-device behavior remain explicitly unverified until separately tested with synthetic QA data.

## References

- `docs/IN_APP_ROUTING_FOUNDATION.md`
- `docs/ROUTING_PROVIDER_SELECTION.md`
- `docs/RENDER_OSRM_DEPLOYMENT.md`
- `docs/APP_FEATURE_GAPS_AND_ROADMAP.md`
- `docs/POST_IMPLEMENTATION_AUDIT_2026-10.md`
- `backend/src/routes/api.php`
- `app/build.gradle.kts`
