# TACResQ activity and incident lifecycle reliability

## Objective

Reduce crashes, stuck loading/busy states, stale incident actions, duplicate operations, and incorrect recovery after Android lifecycle changes or backend failures. This is reliability hardening, not a claim that the app can prevent every runtime or network error.

## Current architecture verified on main

- A single MainActivity hosts Compose and forwards notification taps from both onCreate and onNewIntent.
- Notification identity is persisted in onSaveInstanceState; the notification tap opens the authenticated inbox, where the protected notification lookup remains the authorization boundary.
- Session restoration uses encrypted Android Keystore-backed DataStore. Requester pending-SOS envelopes and drafts are owner-bound and persisted.
- UI state is collected with collectAsStateWithLifecycle; periodic screen refreshes use repeatOnLifecycle(STARTED).
- Requester, responder, dispatcher, and chat screens use screen-level ViewModels and viewModelScope.
- Incident and per-responder status transitions are constrained in the domain model and enforced by Supabase RPC/database guards. GPS sharing has a separate explicit foreground-service lifecycle.

## Evidence-based lifecycle risks found

1. **Unexpected repository exceptions can escape screen coroutines.** Repository methods normally return Result, but their wrappers do not guarantee that every possible runtime exception is converted. Android's coroutine guidance warns that unhandled exceptions from launch can crash the app. Affected high-value paths included dispatcher queue refresh/assignment, requester status refresh, and chat refresh/pagination/text/image sends.
2. **Dispatcher refreshes could be silently dropped while another refresh was active.** A refresh requested during an active request was simply ignored. After an assignment or lifecycle resume, the screen could therefore keep an older queue snapshot until the next periodic poll.
3. **Dispatcher assignment had no synchronous busy claim.** Two rapid taps could enter the coroutine before the UI state changed, risking duplicate assignment requests.
4. **Requester actions correctly fail closed when live incident status is unavailable.** The refresh path needed an exception-safe boundary so an unexpected exception could not leave the loading/refresh flag or current-status eligibility inconsistent.
5. **Same-incident chat responses could outlive a conversation instance.** Checking only the incident ID was insufficient: navigating away and reopening the same incident could let an older request's response update the newly opened screen. Closing also needed to cancel the in-flight send, not only drop its Job reference.
6. **Responder actions could race before the busy state was published.** Rapid taps could launch multiple status/duty or location-resume operations. The location-resume path also needed guaranteed busy-state cleanup if repository or tracking calls threw unexpectedly.
7. **Requester SOS and pending-recovery operations could strand the sending state on an unexpected exception.** Their normal Result failures were handled, but thrown exceptions during create/retry/discard or draft persistence could bypass those handlers. A submitted SOS must continue using its existing idempotency envelope after an uncertain outcome; it must never be resent as a new request.

## Changes in this reliability pass

- Added repositoryCallOrFailure: convert unexpected exceptions into Result.failure while always rethrowing CancellationException.
- Dispatcher refreshes now coalesce overlapping refresh requests into one follow-up pass, clear the refresh-job slot in finally, and keep assignment disabled unless both incident and unit data are fresh.
- Dispatcher assignment claims the busy state synchronously and normalizes unexpected repository exceptions into a visible failure. Failed assignment confirmation invalidates the local freshness gate so the queue must be refreshed before another assignment.
- Requester incident refresh now normalizes unexpected repository/cache exceptions, always releases its refresh-job slot, and marks live incident status unavailable on unexpected failure. Cached status remains informational and does not authorize new emergency actions.
- Responder duty/status actions now claim the busy state before launching their coroutine and release it in a finally block, including cancellation and unexpected failures. Resuming location sharing uses the cancellation-safe repository boundary for assignment/identity checks, keeps sharing off on verification failure, and always clears the busy state.
- Chat refresh, older-message pagination, text sends, and image sends now use the same exception boundary. A monotonically increasing conversation generation scopes every async result to the exact open/close instance, even when the same incident is reopened. Closing cancels all three chat jobs and invalidates their generation. Cancellation remains cancellation; failures continue through the existing user-visible error state. For text sends, the ViewModel also retains the exact message text, incident ID, and client message ID when close cancels an in-flight send. Reopening that incident restores the original text and retry key; editing it or sending different content is blocked until the uncertain outcome is resolved. This in-memory recovery covers close/reopen while the ViewModel survives, but not process death or ViewModel replacement.

## Required lifecycle/state contract

| Workflow | Valid state progression | Failure/recovery rule |
|---|---|---|
| Session restore | Loading → Logged in / Logged out | Restore failure is explicit and retryable; never silently erase encrypted session data |
| Requester SOS | Draft → verify current status → verify pending envelope → capture/confirm location → submit once → server-confirmed incident | Normalize unexpected failures; release transient sending state; unknown submission outcome keeps the original idempotency envelope; never create a replacement request |
| Dispatch | Fresh queue + fresh units → select team → assign → refresh authoritative queue | Stale or failed queue/unit data disables assignment |
| Responder | Assigned → En route → On scene → Transporting/Resolved | Server validates each transition; actions claim busy synchronously; GPS sharing is independent and requires explicit consent; failed identity/assignment verification keeps sharing off |
| Chat | Authorized incident → load latest → paginate/send | Scope responses to incident + conversation generation; cancel jobs on close; preserve idempotency keys for uncertain send outcomes |
| Notification tap | Capture ID → restore authenticated session → open inbox → protected lookup | Push payload is a locator, not authorization; malformed/stale/cross-account notification must not open incident data |
| Foreground GPS | Explicit start → service active → authorized periodic updates → explicit/terminal stop | Permission loss, revoked assignment, sign-out, and service failure stop sharing and surface status |

## Regression scenarios to keep exercising

- Process recreation while session restore, notification routing, or requester draft restoration is in progress.
- Notification tap when app is cold, already foregrounded, backgrounded, or process-killed; two distinct notification IDs; duplicate tap; malformed ID; signed-out session; account switch.
- Double-tap SOS, assignment, status advance, responder duty changes, location resume, and message send; verify only one operation starts.
- Network timeout after request reaches server but before response; retry with same idempotency identity.
- Offline/timeout during dispatcher refresh, requester refresh, chat pagination, and image upload.
- Permission denied/revoked, device Location disabled, stale GPS fix, foreground service stopped by Android, responder signs out, assignment no longer en route.
- Navigate away during a chat read/send, reopen the same incident, and ensure old async responses cannot overwrite the new conversation instance.
- Close a conversation during image upload or text send; verify the old result cannot clear or append to a later draft. For text, simulate server commit followed by a lost response, close and reopen the same incident, then retry the restored exact text with the original client message ID and verify the server returns the original row without inserting a duplicate. Added `FakeTacApiChatTest.committedMessageWithLostResponseRetriesAfterConversationReopenWithoutDuplicate`, which commits a message, discards the first response to simulate a lost HTTP response, retries with the same payload/key after a simulated close/reopen, and asserts only one message row exists. This is a behavioral FakeTacApi idempotency test; it does not exercise the Android ViewModel lifecycle or live Supabase transport. The current ViewModel-only recovery does not survive process death; durable encrypted persistence and image-payload recovery remain separate work.

## Validation boundary

Automated source/contract checks and Android build/unit tests are required. Emulator and physical-device execution were not performed in this pass. Live Supabase behavior, real FCM delivery, Android process-death behavior, foreground-service survival under OEM power management, and actual GPS permission changes remain unverified. No live database migration, production configuration change, real incident mutation, or real FCM send is part of this work.

## Research basis

- Android Developers, [Architecture recommendations](https://developer.android.com/topic/architecture/recommendations): lifecycle-aware effects, repeatOnLifecycle, collectAsStateWithLifecycle, ViewModel tests and state ownership.
- Android Developers, [Coroutine best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices): unhandled coroutine exceptions can crash apps; catch expected failures in ViewModel coroutines and rethrow CancellationException.
- Android Developers, [Lifecycle-aware coroutines](https://developer.android.com/topic/libraries/architecture/coroutines): viewModelScope work is cancelled when its ViewModel is cleared.
- Kotlin, [Coroutine exception handling](https://kotlinlang.org/docs/exception-handling.html): cancellation is control flow; uncaught exceptions from launch propagate as unhandled failures.