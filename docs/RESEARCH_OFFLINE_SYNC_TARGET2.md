# Research: offline synchronization for target 2

**Date:** 2026-11-07  
**Scope:** TACResQ production-readiness target 2  
**Status:** Research-only prerequisite; implementation follows in a separate commit

## Decision

TACResQ should implement offline synchronization in two explicitly separated lanes:

1. **Automatic, read-only reconciliation:** refresh server-confirmed requester incident status and notification state when the device has a validated network and the app is active or a constrained background task runs. The local encrypted snapshot remains the read model when the network is unavailable.
2. **Explicit emergency writes:** SOS creation, retry of an uncertain SOS, cancellation, status changes, location writes, chat sends, and notification-read mutations remain foreground user actions. They must not be silently queued and submitted when connectivity returns.

This is the only safe target-2 slice that improves recovery without turning an old emergency request into an unreviewed dispatch action.

## Existing evidence

The repository already has encrypted, owner-scoped DataStore persistence for requester drafts, unresolved SOS envelopes, and a last-confirmed requester incident-status snapshot. The snapshot is intentionally read-only, expires after 24 hours, and is cleared on sign-out. The requester screen falls back to it after a failed refresh and distinguishes cached status from server-confirmed state.

The repository also has authenticated repository methods for incident and notification reads. The app already declares `ACCESS_NETWORK_STATE`, but it does not yet have a single synchronization coordinator or a durable, unique background read-refresh policy.

## Research findings

Android offline-first guidance recommends a local source of truth for reads, with network synchronization in the data layer. Android persistent background work guidance recommends WorkManager for deferrable work that survives process death and can retry under a network constraint. Neither mechanism guarantees immediate delivery, so neither may be used to claim that an SOS reached dispatch.

Automatic synchronization must be owner-scoped and token-aware. A worker must stop without mutating local state when the session is logged out, expired, or belongs to another account. Successful server reads may replace the cache; failed reads must preserve the last-confirmed snapshot and expose its age. The worker must not call create, retry, cancel, status-update, location-write, chat-send, or notification-mark-read methods.

## Implementation boundary

The first implementation commit will add a pure, testable synchronization policy and wire it to the existing read model. It will cover:

- validated-network and authenticated-session preconditions;
- unique read-only refresh decisions;
- bounded retry/backoff classification;
- preservation of cached data on transient failure;
- explicit rejection of all emergency or other mutation operations from automatic sync.

The next implementation slice can add the platform worker once the repository’s dependency and lifecycle checks are green. No live backend or device behavior will be claimed from source tests alone.

## Acceptance criteria

- Research is committed before implementation.
- Automatic sync has no path to submit an SOS or other dispatch-critical write.
- Existing cached status remains available offline and is visibly marked with its sync age.
- User-initiated writes remain explicit and preserve current idempotency and confirmation rules.
- Unit/source tests cover logged-out, no-network, fresh-read, transient-failure, stale-cache, and forbidden-mutation cases.
- CI remains the build/test gate; physical-device airplane-mode and process-destart tests remain outstanding.

## References

- `docs/RESEARCH_OFFLINE_SOS_RESILIENCE.md`
- `docs/APP_FEATURE_GAPS_AND_ROADMAP.md`
- `app/src/main/java/com/tacresq/app/data/local/SessionStore.kt`
- `app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt`
- Android offline-first architecture: https://developer.android.com/topic/architecture/data-layer/offline-first
- Android persistent background work: https://developer.android.com/develop/background-work/background-tasks/persistent
