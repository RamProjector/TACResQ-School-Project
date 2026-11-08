# Android Client Completion Research

**Recommended PR title:** `docs: research Android client completion gaps and UI polish`

## Completion assessment

TACResQ has a substantial Android client baseline for requester SOS, responder/driver coordination, dispatcher assignment, maps and location, chat, notifications, authentication, and adaptive navigation. The reviewed implementation reflects important safety boundaries: SOS requires review and explicit confirmation; location is user-initiated or explicitly consented; pending ambiguous SOS submissions retain their original idempotency envelope for explicit retry; cached status is not treated as current; and the app does not claim that location or routing proves arrival or guarantees help. These are valuable foundations, not evidence of operational readiness.

The main completion risk is the gap between source-level contracts and real behavior under emergency-relevant conditions. Supplied tests primarily assert source patterns, not Android state transitions, process death, permission changes, real network ambiguity, assistive technology behavior, or controlled-backend outcomes. The highest-value next work is therefore executable recovery and permission testing, followed by targeted fixes to issues those tests expose. Avoid broad visual redesign or expanding tracking/routing until the core flows are verified and approved.

The assessment distinguishes repository implementation from live and device validation. Three named requester source-contract tests pass; other cited contract tests are source-level evidence. No full build/test suite, emulator/device, TalkBack session, provider configuration, or field operation was verified. Unverified behavior is treated as a validation gate, not as proof that a feature is absent.

## Implemented baseline

- **Requester SOS:** staged form, review and explicit confirmation; one-time user-initiated location capture, manual pin, freshness/validity checks, and landmark-only fallback; encrypted owner-scoped drafts; durable pending idempotency envelope and explicit retry; duplicate guard; active-request status gating; cancellation/edit flow; and a warning when the accepted server response's location differs from the approved payload. The three named requester source-contract tests pass.
- **Responder and driver:** shared role workflow gates duty changes on assignment refresh, shows per-assignment status, offers destination routing, and provides opt-in approximate location sharing with status feedback and manual stop/resume. Dispatcher refreshes queue and units, disables stale assignment actions, limits eligible assignments, and shows aggregate and per-member progress. Supplied tests check source contracts, not runtime authorization or device behavior.
- **Connectivity and lifecycle:** lifecycle-aware status collection, bounded read-only cached incident status, stale-state action gating, and a read-only WorkManager contract. SOS is not silently queued; ambiguous submissions retain the original request for explicit recovery. Drafts and cache ownership/expiry are reported as implemented, but runtime tests remain a gate.
- **Location and maps:** validated coordinates, one-time location, manual pins, freshness labels, incident details retained when map data is unavailable, assignment-gated route previews, explicit sharing disclosures, external Maps fallback, and a stoppable foreground service for consented responder sharing. Routing is disabled by default; provider and field readiness are not established.
- **Shared app basics:** login and requester-only registration, session restore, role-specific adaptive navigation, profile/safety guidance, notification permission request, and confirmed sign-out. Password recovery, account/profile editing, and a dedicated help/settings route are missing or incomplete in the reviewed surfaces; equivalent functionality elsewhere was not ruled out.
- **Notifications and chat:** caller-scoped inbox, read/open actions and recovery states, foreground realtime plus refresh fallback, incident-scoped chat, paging, retry, removable image attachments, and generic FCM tray content. The Realtime broadcast migration has now been applied to the connected TACResQ Supabase project and its trigger/function/policy verified read-only; actual event delivery and device behavior remain untested. Android 13+ permission handling lacks an in-app explanation/settings route; live push remains unverified.
- **UI/accessibility:** Material 3 light/dark themes, adaptive navigation/list-detail patterns, selected 48dp targets, a polite live-region banner, and SOS progress semantics exist. Supplied viewport/accessibility evidence is largely wireframe or source-text testing, not native runtime verification.

See [Product Scope](PRODUCT_SCOPE.md), [App Feature Gaps and Roadmap](APP_FEATURE_GAPS_AND_ROADMAP.md), [FCM Setup](FCM_SETUP.md), [Realtime Status](PHASE3_REALTIME_STATUS.md), and [In-App Routing Foundation](IN_APP_ROUTING_FOUNDATION.md) for the canonical boundaries and context. Keep the app positioned as reporting and coordination support: it does not replace local emergency services, guarantee response, prove arrival, or authorize actions outside a user's role.

## Prioritized actionable gaps

### P0 — Safety-critical verification before readiness claims

1. **Exercise ambiguous SOS outcomes end to end.** Add emulator/instrumented tests with a controllable backend that commits an SOS then drops the response. Verify restart and repeated explicit retry use the identical payload and idempotency key, produce at most one incident, preserve the envelope until confirmation, and never offer a replacement-send route while the outcome is unresolved. Confirm the UI says confirmation is unknown and gives safe recovery guidance. This is primarily a verification gap around implemented safeguards, not a claim that idempotency is missing.
2. **Verify location-to-submit safety.** Test permission denial, approximate-only permission, revocation, location services off, timeout, stale/inaccurate fix, manual pin, and landmark-only submission. Ensure no denied/stale fix is submitted, weak precision is clear at final review, and landmark fallback remains usable. Android distinguishes approximate and precise access and cautions that last-known fixes can be outdated; test freshness rather than assuming it ([1], [2]).
3. **Validate role authorization and dispatcher truthfulness against an isolated non-production backend.** No separate staging project is configured. Test driver/responder account scope, assignment freshness, concurrent eligibility changes, server rejection, and refresh failure using authorized test accounts and synthetic data. Do not use the connected live project for mutation tests or imply operational dispatch validation.
4. **Verify consented foreground location sharing on devices.** Across supported Android versions, test start only after explicit consent and permission, backgrounding/lock, notification denial, service notification and stop action, network loss, and assignment status change. Do not start sharing silently or represent a stale fix as current. Android 14+ has location foreground-service declarations, permission prerequisites, and while-in-use restrictions that require version-specific validation ([3], [4]).

### P1 — Close concrete capability gaps and recovery friction

1. **Add provider-backed password recovery.** Provide a non-enumerating request confirmation, clear recoverable errors, and successful reset/sign-in; do not reveal whether an email is registered. The recovery capability is absent in the reviewed auth screen; verify provider configuration before shipping.
2. **Reconcile pending SOS and status on resume.** On requester foreground/resume, deduplicate a pending-envelope check and live-status refresh. Preserve explicit retry/no-auto-send behavior; show cached data as timestamped and read-only until confirmed current. Add lifecycle tests rather than inferring recovery from lifecycle-aware flow collection ([5], [6]).
3. **Improve notification controls.** Explain current Android notification permission in Profile/Notifications and provide a system settings route. Denial should not break inbox, manual refresh, or incident/chat navigation; avoid repeated prompts. Validate Android 13+ grant/denial, channel disablement, push tap routing, duplicates and out-of-order events in an isolated test environment and on device ([7], [8]).
4. **Add a concise, safety-bounded help destination.** Explain how to report and check a request, what location sharing means, and that users should contact local emergency services directly when needed. Keep guidance consistent with [Product Scope](PRODUCT_SCOPE.md); do not promise dispatch response or introduce unapproved escalation procedures.
5. **Make transport outcomes more actionable.** Distinguish confirmed rejection from offline/timeout with unknown outcome. For uncertain SOS, explicitly say it may have reached dispatch and guide the user to recover the saved request, not create another one.

### P2 — Polish and broaden validation

1. **Native accessibility and adaptive QA:** run Compose accessibility checks plus TalkBack on auth, requester, responder, dispatcher, notification, and chat critical paths. Verify semantics, focus order, changing status announcements, contrast, touch targets, large text, compact phones, short landscape, and expanded windows ([9], [10]).
2. **Persistence ownership tests:** test encrypted draft and pending-envelope recovery across process death, sign-out and account switch; verify only the owner can restore them and successful confirmation or explicit discard clears the right state.
3. **Map/routing comprehension and resilience:** test map tile/network failure, stale destination confirmation, route preview versus live navigation language, external-map fallback, accuracy/freshness labels, and disclosure comprehension. Keep routing gated until provider configuration and processing disclosures are approved.
4. **Privacy review:** validate in-app inbox preview sensitivity for shared-device/shoulder-surfing contexts. Retain generic lock-screen push payloads and do not add coordinates or incident narratives to tray notifications.
5. **Feature completeness only where supported:** consider profile maintenance only for verified supported operations. Do not add broad account administration or background location tracking as a polish shortcut.

## Specific UI polish checklist

- [ ] SOS review states whether the location is a fresh GPS fix, approximate/low-accuracy fix, manual pin, or landmark-only report; show accuracy/freshness when available without implying continuous tracking.
- [ ] Uncertain submission state is unmistakable: “confirmation unknown” rather than “sent”; primary recovery targets the saved SOS with the original key; no replacement-send CTA appears while unresolved.
- [ ] Location denial, revocation, timeout, stale fix, disabled services, map-tile failure, offline status, and confirmed rejection each have distinct concise feedback and a safe next action.
- [ ] Dispatcher assignment success is shown only after server acceptance and a successful authoritative refresh; stale queue/unit state is visibly non-actionable.
- [ ] Responder sharing UI states recipient/scope, approximate accuracy, last confirmed update, service state, and effective stop control; no status implies arrival or guaranteed delivery.
- [ ] Notification settings show permission state and a system settings route; inbox remains available when push is denied.
- [ ] TalkBack gets meaningful labels and logical focus for primary actions, errors, SOS progress, sync freshness, read/unread state, and chat send/retry results without disruptive focus stealing.
- [ ] Critical touch targets are at least 48dp where practicable; verify all controls, not just selected primary buttons. Android recommends 48dp targets and accessible descriptions ([9]).
- [ ] Verify light/dark status colors and text contrast; retain text labels rather than color-only status. Make full values accessible wherever compact text is ellipsized.
- [ ] Verify native Compose at narrow supported widths, short landscape, expanded layouts, and 200% font scale; no critical SOS, location, sync, or navigation content is clipped or obscured. WCAG 2.2 is a useful benchmark for reflow/contrast/target checks, not proof of native Android conformance ([11]).
- [ ] Keep wireframe screenshot tests explicitly scoped to the wireframe; add native screenshot/UI tests for Compose behavior.

## Measurable acceptance criteria

1. **SOS idempotency:** a simulated commit-then-timeout followed by restart and two explicit retries produces exactly one server incident; every retry carries the same payload and idempotency key; the pending envelope remains until confirmed; no automatic or replacement submission occurs.
2. **Location integrity:** automated UI/integration tests cover denied and approximate permission, revocation, timeout/stale fix, manual pin, and landmark-only cases. Only a fresh or manually selected pin is submitted; landmark is required without a pin; approximate limitations are visible at review.
3. **Ownership and lifecycle:** process restart/account-switch tests prove drafts and pending SOS restore only for their owner; confirmed success clears them; ambiguous outcomes preserve the original request. Resume-triggered refresh is deduplicated and stale cached statuses remain read-only.
4. **Assignment truthfulness:** stale/failed refresh, concurrent eligibility change, server rejection, and successful assignment are tested; no action is submitted from stale data, and no success is shown without accepted server state and refresh.
5. **Location sharing:** supported-version tests verify explicit action and granted permission before sharing, persistent service notification and effective stop, denial/revocation recovery, and no automatic start from backgrounding. Sharing stops when the user's own assignment no longer permits it.
6. **Accessibility and layout:** automated Compose checks and manual TalkBack run cover critical auth/SOS/assignment/chat states. Action controls meet 48dp where applicable; announcements and focus are logical; contrast is checked; critical flows remain available at 200% font scale and compact/short-landscape layouts.
7. **Notification behavior:** Android 13+ allow/deny and channel disablement are tested; denial preserves inbox and manual refresh; tray payloads contain no message body, identity, coordinates, or sensitive incident details; app settings route works.
8. **Evidence discipline:** CI/source-contract results, emulator tests, controlled non-production backend tests, and physical-device checks are reported separately. A separate staging project is not configured. No “operationally ready” claim is made based only on static contracts or synthetic tests.

## Suggested sequence of focused PRs

1. **PR 1 — `test(android): harden SOS idempotency and recovery`**: build a fake-backend or isolated non-production test harness for commit-then-timeout, retry, restart, account isolation, and accepted-response mismatch. Fix only observed state-machine defects; retain explicit retry and no replacement-send safeguards.
2. **PR 2 — `test(android): verify location permissions and sharing lifecycle`**: exercise requester location and responder foreground-service flows across supported API levels, including approximate/denied/revoked permission and notification denial. Clarify any demonstrated permission/service ambiguity.
3. **PR 3 — `feat(android): add safe auth recovery and notification controls`**: implement provider-backed password reset and a notification settings/status entry, with non-enumerating auth feedback and inbox-preserving denial behavior.
4. **PR 4 — `test(android): validate assignment truth and role access`**: add dispatcher stale/concurrent/rejected assignment and driver-specific authorization tests against a controlled non-production backend.
5. **PR 5 — `a11y(android): polish emergency flows across layouts`**: based on TalkBack, large-text, contrast, touch-target, and compact-layout findings, fix observed UI barriers and add native regression checks.
6. **PR 6 — `docs(android): document verified device and backend coverage`**: record versions/devices, scenarios, outcomes, known limits, and feature gates; keep documentation explicit that app assistance does not replace local emergency services or guarantee response.

## References

[1]: https://developer.android.com/develop/sensors-and-location/location/permissions "Request location permissions | Sensors and location"
[2]: https://developer.android.com/develop/sensors-and-location/location/retrieve-current "Get the last known location | Sensors and location"
[3]: https://developer.android.com/develop/background-work/services/fgs/service-types "Foreground service types"
[4]: https://developer.android.com/guide/topics/permissions/private-alternatives/location-button "Request session-based location access with the location button"
[5]: https://developer.android.com/topic/libraries/architecture/coroutines "Use Kotlin coroutines with lifecycle-aware components"
[6]: https://developer.android.com/topic/architecture/data-layer/offline-first "Build an offline-first app"
[7]: https://developer.android.com/develop/ui/compose/notifications/notification-permission "Notification runtime permission"
[8]: https://developer.android.com/develop/ui/compose/notifications/channels "Create and manage notification channels"
[9]: https://developer.android.com/guide/topics/ui/accessibility/apps "Make apps more accessible"
[10]: https://developer.android.com/develop/ui/compose/accessibility/testing "Accessibility in Jetpack Compose"
[11]: https://www.w3.org/TR/WCAG22/ "Web Content Accessibility Guidelines (WCAG) 2.2"
