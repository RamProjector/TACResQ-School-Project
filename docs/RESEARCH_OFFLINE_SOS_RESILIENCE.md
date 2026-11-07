# Research: safe requester SOS resilience

**Date:** 2026-11-07  
**Status:** Research and implementation decision; no product code changed by this document.

## Question

What is the safest missing capability to implement next for TACResQ when a requester loses connectivity, the app is backgrounded, or the process is restarted during an SOS attempt?

## Current repository behavior

TACResQ already persists an unresolved SOS envelope in encrypted Android DataStore. The envelope contains the original request payload, a client request ID used for idempotency, a fingerprint, and the authenticated requester owner ID. The repository keeps the envelope after ambiguous failures and only clears it after a confirmed successful server response. It also refuses to let a different requester account retry or discard the saved request.

This is a good safety foundation, but the user-facing flow does not yet provide a complete, durable local-draft experience. The roadmap correctly identifies the remaining gap as explicit states such as saved on this phone, sending, confirmed by server, and could not confirm. General offline write synchronization is not implemented and should not be added by silently submitting an old emergency request after connectivity returns.

## Official Android guidance reviewed

### Offline-first architecture

Android’s offline-first guidance says an app with network-backed repositories should have a local data source and a network data source. The local data source is the canonical source of truth for higher layers, and reads should remain usable without a network. Network synchronization belongs in the repository/data layer rather than the UI. The guidance presents Room, DataStore, or files as possible disk-backed local sources and describes WorkManager as one option for reconciliation work.

Source: [Build an offline-first app](https://developer.android.com/topic/architecture/data-layer/offline-first)

### Persistent background work

Android recommends WorkManager for work that should persist across app exits, restarts, and device reboots. It supports constraints, unique work, and retry with backoff. However, WorkManager is for reliable background work and deferrable synchronization; it is not a guarantee that a time-sensitive emergency request will reach dispatch immediately. A network-recovered worker must therefore never change the UI to “SOS sent” without a confirmed server response.

Source: [Task scheduling with WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)

### Process-death and form-state preservation

Android distinguishes transient saved UI state from application data. `ViewModel`, `rememberSaveable`, and `SavedStateHandle` help restore small UI state after process death, but they are not replacements for durable application storage. Complex or important data should be persisted to disk, such as with DataStore or a database. For TACResQ, the emergency payload and idempotency key belong in the existing encrypted persistent store; a small UI-state marker can be restored separately.

Source: [Save UI states](https://developer.android.com/topic/libraries/architecture/saving-states)

## Decision

The next implementation should be a **safe SOS draft and confirmation-state experience**, not generic automatic offline submission.

The implementation should:

1. Preserve the existing encrypted, owner-bound, idempotent pending SOS envelope.
2. Expose an explicit state machine to the requester UI:
   - `Editing locally` — the form has not been submitted.
   - `Sending` — the current foreground submission is in progress.
   - `Awaiting confirmation` — the outcome is uncertain and the exact request is saved.
   - `Confirmed by dispatch` — only after the server returns a successful incident response.
   - `Could not confirm` — the request remains saved and can be explicitly retried or discarded.
3. Restore the unresolved request after process restart or sign-in with the original requester account.
4. Make retry explicit and send the original payload and idempotency key verbatim.
5. Require server confirmation before displaying language such as “received,” “sent to dispatch,” or “help is on the way.”
6. Show the original location capture time and accuracy, and warn if a saved GPS fix may now be stale.
7. Never submit the saved SOS automatically merely because connectivity returns.
8. Keep the in-app status/inbox as the source of truth after confirmation.
9. Keep the local envelope encrypted, owner-scoped, and removable through an explicit discard action.

## Non-goals

This task will not add continuous requester tracking, automatic background SOS delivery, a fifth user role, a second backend, a new emergency hotline integration, or a claim that TACResQ guarantees emergency response. It will not use real emergency records or live responder coordinates in tests.

## Acceptance criteria for the implementation PR

- An unresolved SOS survives process death and is restored for the original requester account.
- A different requester account cannot retry, discard, or inspect another account’s saved SOS.
- Airplane-mode or timeout paths never display confirmed/received language.
- A timeout after a server-side commit does not create a duplicate when the user retries.
- Retry sends the original request ID, location, capture time, and payload rather than current form/GPS values.
- Explicit discard removes only the owner’s unresolved envelope.
- Confirmed success clears the envelope and shows the server incident status.
- Missing, stale, unavailable, or manually selected location is labeled accurately.
- Unit/source tests cover restart restoration, ownership conflict, timeout/ambiguous failure, confirmed success, duplicate retry, discard, and stale-location messaging.
- Documentation and UI copy continue to state that TACResQ assists emergency coordination and does not replace official emergency services.

## Implementation sequence

1. Add a small repository-owned pending-SOS view-state model that can be derived from the existing DataStore envelope and submission result.
2. Update requester UI feedback and recovery actions without changing server authorization or idempotency behavior.
3. Add deterministic source/unit tests for the state transitions and safety wording.
4. Run the repository’s source, workflow, prototype, Android build, backend, security, and RPC checks in CI.
5. Keep automatic offline synchronization as a separate future research/implementation decision requiring an explicit operational safety review.

## References

[1]: https://developer.android.com/topic/architecture/data-layer/offline-first "Build an offline-first app"
[2]: https://developer.android.com/develop/background-work/background-tasks/persistent "Task scheduling with WorkManager"
[3]: https://developer.android.com/topic/libraries/architecture/saving-states "Save UI states"
