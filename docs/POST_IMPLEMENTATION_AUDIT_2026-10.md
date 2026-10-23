# TACResQ post-implementation audit

**Audit snapshot:** 2026-10-23  
**Reviewed main commit:** `3ac4b26297347658e4a4b3097120450043734980` (PR #107 merged)  
**Validation evidence:** PR #107 CI run #520 passed source checks, Android debug/release builds, browser prototype navigation/visual checks, Laravel/dashboard integration, and clean-PostgreSQL migration replay; the separate RPC-grant contract passed.  
**Not validated:** physical-device runtime, live Supabase project settings/data/RLS behavior, real email confirmation/deep-link flow, cross-device operation, production release signing/deployment.

## Executive assessment

TACResQ has moved beyond a static mock: the repository contains a Kotlin/Compose Android app, distinct demo and Supabase API modes, encrypted local session persistence, idempotent requester SOS retry state, consent-gated one-time requester location, foreground responder location sharing, role-gated RPCs/RLS migrations, dispatcher team assignment, per-unit statuses, and a web dashboard/prototype test pipeline.

**It is not yet validated for real emergency use.** CI demonstrates that code compiles and specified automated contracts pass; it does not establish that the live Supabase project, Android location APIs, map tiles, or full multi-account workflow operate correctly on physical devices.

## Findings by priority

### P0 — Release/operational gate

#### P0.1 — No end-to-end device and live-backend evidence
- **Evidence:** `docs/SUPABASE_TESTING.md` and `docs/GPS_LOCATION_NOTES.md` explicitly state that real-device and live-project behavior remain unverified. CI builds APKs and replays migrations against clean PostgreSQL, but neither is a live-project/device test.
- **Risk:** A successful build can still fail at email confirmation, permissions, GPS fix acquisition, map tile loading, deployed schema version, role provisioning, or cross-account synchronization.
- **Required next action:** Use a separate Supabase QA project and synthetic identities/incidents. Run the device checklist in issue #24 on at least one supported physical Android device; record Android/API version, APK SHA, permission choice, and sanitized pass/fail evidence.
- **Exit criteria:** Requester → dispatcher → assigned responder/driver → requester status/history flow passes on device against the QA backend, including failure paths and cleanup. Do not use real emergency data.

#### P0.2 — App must remain explicitly non-operational until the gate passes
- **Evidence:** `docs/PROTOTYPE_SCOPE.md`, `docs/GPS_LOCATION_NOTES.md`, and backend testing docs frame this as a school prototype.
- **Risk:** Users may mistake a successful APK build or demo flow for a dependable emergency response service.
- **Required next action:** Keep demo/test labels and clear emergency-service disclaimers; do not claim production readiness or use it as the sole emergency channel.

### P1 — High priority engineering and safety

#### P1.1 — Android behavior lacks native unit/instrumentation tests
- **Evidence:** The repository tree has no `app/src/test/` or `app/src/androidTest/` tests. Python source-contract tests inspect source patterns; browser tests exercise the prototype/wireframe, not the Compose Android app.
- **Risk:** Kotlin state races, lifecycle cancellation, API JSON parsing, Compose interaction regressions, and Keystore/device behavior may escape CI.
- **Required next action:** Add Android unit tests for repository error/idempotency logic and ViewModel state transitions; add instrumented/emulator smoke tests for sign-in, role routing, SOS review/send/cancel/edit, assignment progression, and location-sharing stop/resume. Keep source-contract tests as supplemental guards, not the only app tests.

#### P1.2 — Supabase email-confirmation deep link is declared but not handled in the Activity
- **Evidence:** The manifest declares `tacresq://auth/callback`, while `MainActivity.onCreate` only creates Compose content and does not inspect the launch intent or handle `onNewIntent`. The Supabase adapter supplies this redirect for signup.
- **Risk:** The confirmation link may open the app without an explicit callback result, leaving the user uncertain whether confirmation succeeded. Custom URI schemes can also be claimed by other apps; callback parameters must not be trusted blindly.
- **Required next action:** Verify actual Supabase email-template/redirect behavior in QA. Then either implement and test a narrowly scoped callback (validate state/expected parameters; never accept arbitrary bearer tokens from a URL) or use a confirmation flow that clearly returns users to a safe sign-in screen. Consider verified Android App Links if a controlled HTTPS domain is available.
- **Exit criteria:** Tested success, expired link, replay, malformed parameters, and cancelled/unsupported-link behavior on device; no token leakage into logs.

#### P1.3 — Live role/RLS/RPC policy has not been verified against the configured project
- **Evidence:** SQL migration replay and grant-contract checks pass in CI; `docs/SUPABASE_TESTING.md` says accounts must be provisioned and verified separately. The app embeds only a publishable key, which is expected, but that does not itself secure data.
- **Risk:** Live migration drift, missing profiles, wrong role provisioning, or project-level settings can still break flows or expose records.
- **Required next action:** In a separate QA project, verify migration ledger alignment and test each role against allowed and denied operations: requester, responder, driver, dispatcher, anonymous. Include direct table access denial, cross-requester reads/edits/cancellation, unassigned-unit status changes, location reads/writes, malformed coordinates, and closed-incident mutations.
- **Exit criteria:** Record exact project migration version and a role-by-operation results matrix. Do not apply or seed the live project as part of this audit.

#### P1.4 — Location lifecycle needs physical-device verification, not a redesign
- **Evidence:** `LocationProvider.kt` uses a one-shot current fix for requester SOS and an approximate foreground stream for responders; the responder ViewModel stops sharing on screen/lifecycle exit, permission loss, unavailable services, or leaving the unit's own `en_route` state. The location button dependency is documented as alpha in `docs/GPS_LOCATION_NOTES.md`.
- **Risk:** OEM/provider differences, permission revocation, settings changes, app backgrounding, and network loss can behave differently from source-level expectations.
- **Required next action:** Test precise/approximate/denied/revoked permissions, location services toggled off, timeout/stale fix, offline map tiles, screen-off/backgrounding, navigation away, reassignment, sign-out, and explicit resume. Confirm there are no uploads after sharing stops. Review the Location Button library/API and current Play policy before any production distribution.
- **Constraint:** Preserve the five-destination navigation and GPS map as a primary destination. Do not introduce background tracking without a separate privacy/product decision.

#### P1.5 — Privacy/data governance is not yet a release-ready package
- **Evidence:** The app and backend handle names, phone numbers, incident descriptions, one-time requester coordinates, and responder location history. Repository docs describe access controls, but a complete user-facing privacy notice, retention/deletion process, incident-data retention schedule, and operational access-review procedure are not established by CI.
- **Risk:** Excessive retention or unclear user expectations around who can see location/contact data.
- **Required next action:** Define minimum collection, authorized visibility, retention/deletion, test-data cleanup, incident audit access, and contact/reporting process. Verify actual database retention and backup behavior before writing policy promises.

### P2 — Important quality and maintainability

#### P2.1 — Improve automated coverage from contract checks to behavior tests
- Add deterministic tests for duplicate auth submission, encrypted session restoration and Keystore failure, unresolved SOS retries across process restart/logout, idempotency conflicts, requester single-active-request rules, edit/cancel boundaries, team assignment limits, and per-unit aggregate status edge cases.
- Add race/concurrency tests for double submit, duplicate assignment, simultaneous status updates, and network timeout after a server commit.

#### P2.2 — Compose UI accessibility and visual regression coverage
- Current CI visual screenshots primarily cover the browser prototype/wireframe, not native Compose screens.
- Add native screenshots/semantics checks for small screens, large font/display size, TalkBack labels, contrast, keyboard/focus behavior, loading/error/empty/offline states, and long requester descriptions. Keep the UI minimal and consistent; avoid adding explanatory clutter.

#### P2.3 — Polling, freshness and resilience
- The app polls every 8 seconds until push notifications are added; map location reports have their own refresh cadence.
- Measure request volume and battery/network impact on a real device and QA backend. Consider lifecycle-aware adaptive backoff when offline/backgrounded and jitter to avoid synchronized bursts. Keep timestamps and stale labels honest; never display an old responder fix as live.
- Add telemetry only with privacy review and without tokens, precise coordinates, phone numbers, or incident descriptions in logs.

#### P2.4 — Make test and real-backend installation less error-prone
- The demo and Supabase debug variants currently share an application ID, so installing one replaces the other.
- Consider build-variant application ID suffixes and unmistakable in-app environment indicators, while keeping release builds on Supabase and ensuring demo identities can never reach the real backend.

#### P2.5 — Consolidate backend/deployment documentation
- The Android app currently uses Supabase directly; the repository also contains a Laravel/Render adapter/dashboard and a legacy API contract.
- State clearly which components are active, optional, or prototype-only; document one canonical deployment diagram and keep API/migration docs aligned. Avoid implying that Render is required for the current Android Supabase flow.

#### P2.6 — Release pipeline hardening
- Confirm the release artifact is signed with a controlled keystore, the signing process is reproducible, package/version metadata is intentional, and install/update behavior is tested. Unsigned release APK generation is not a distributable release.
- Review deep-link association, backup policy, dependency versions (including alpha libraries), Play target SDK/policy requirements, and the privacy declarations before external distribution.

## Positive controls already present

- Demo and Supabase backend modes are separated; release selects Supabase.
- Android session state and pending SOS envelope are encrypted with an Android Keystore AES-GCM key; legacy plaintext session keys are removed rather than silently reused.
- Requester SOS uses a durable idempotency key and blocks replacement while an earlier submission is unresolved.
- Requester location is one-time and user initiated; responder sharing is approximate, foreground-only, incident-bound, and consent-gated.
- Backend migrations include RLS/RPC grant hardening, restricted search paths, server-side status/assignment authorization, and per-responder status.
- Dispatcher assignment is atomic for teams, capped at five units, and queue ordering/wait age is handled.
- CI includes Android builds, source contracts, browser prototype/visual checks, Laravel/dashboard integration, clean-PostgreSQL migration replay, and a separate RPC-grant contract.

These controls reduce risk but do not replace live QA or independent security review.

## Recommended execution order

1. **QA readiness:** establish a separate Supabase QA project, confirm disposable role accounts, capture build artifact hashes, and execute issue #24 on a physical device.
2. **Auth callback:** validate and fix the signup confirmation/deep-link journey.
3. **Native test foundation:** add Kotlin unit tests and emulator/device smoke coverage for the highest-risk state machines.
4. **Authorization matrix:** automate role-based negative cases against isolated PostgreSQL/Supabase QA.
5. **Privacy and release review:** document retention/access controls, verify signed release packaging and policy requirements.
6. **Polish/performance:** native accessibility/visual tests, polling/backoff measurements, and documentation consolidation.

## Audit limits

This is a source/configuration and CI-evidence audit, not a penetration test or a claim of live service verification. No production database was modified, no test accounts were seeded, and no physical device was available to this workflow.
