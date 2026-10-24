# TACResQ Android Client UI/UX, HCI, Accessibility, and Privacy Redesign Plan

**Status:** Audit and implementation plan  
**Scope:** Native Android Jetpack Compose client, including requester, responder/driver, dispatcher, authentication, GPS map, navigation shell, privacy/safety information, and relevant loading/error/empty/offline states.  
**Approach:** One reviewable feature per pull request; preserve working emergency logic; verify each change with builds, tests, screenshots, and where relevant a real device.  
**Important boundary:** This is a design/engineering plan, not a claim of WCAG conformance, legal compliance, or operational readiness.

## 1. Goals and non-negotiable principles

1. **Emergency task first.** Prioritize the next safe action for each role; reduce decorative content and verbose repeated labels without hiding critical warnings.
2. **GPS remains a first-class destination.** Preserve the full-screen map and role-specific information. Never imply a location is live unless its source, timestamp, freshness, and sharing state support that claim.
3. **Truthful system state.** Distinguish loading, empty, stale, offline, permission denied, device location disabled, and server failure. Never replace failed live data with plausible-looking demo data.
4. **No destructive surprise.** SOS submission, cancellation, assignment, status transitions, and location sharing need clear consequences, duplicate-submit protection, and a recoverable path where safe.
5. **Privacy by design.** Explain why location is requested, what is sent, who can see it, when sharing stops, and what data is retained. Request the minimum location scope needed; no covert background tracking.
6. **Accessible under stress.** Use clear labels, strong contrast, large touch targets, predictable navigation, readable typography, non-colour status cues, and TalkBack semantics.
7. **Keep architecture and backend contracts intact.** UI work must not change Supabase/RPC semantics, authentication, role authorization, or incident lifecycle unless a separate explicitly reviewed backend change is needed.
8. **No fake polish.** A static mock or screenshot is not proof that map tiles, GPS, network requests, role permissions, or dispatch actions work.

## 2. Research baseline

Use these sources as implementation guidance, not as a substitute for device testing or Philippine legal review.

- [Android: Layouts and navigation patterns](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns) — use familiar navigation patterns; bottom navigation is for 3–5 peer-level top destinations, with adaptive layouts on larger windows.
- [Android: Navigation bar (Compose)](https://developer.android.com/develop/ui/compose/components/navigation-bar) — selected state, consistent destinations, labels, and icon use.
- [Android Accessibility: touch target size](https://support.google.com/accessibility/android/answer/7101858) — aim for at least 48 × 48 dp interactive targets; check spacing and test on device.
- [Android Developers: Compose accessibility codelab](https://developer.android.com/codelabs/jetpack-compose-accessibility) — semantics, content descriptions, TalkBack, and manual accessibility testing.
- [W3C WCAG 2.2](https://www.w3.org/TR/WCAG22/) — use applicable principles as a cross-platform benchmark: text contrast (4.5:1 for normal text; 3:1 for large text), visible focus, understandable labels, consistent navigation, and minimum target-size guidance. WCAG is not by itself a declaration that this native Android app is legally conformant.
- [Android: Request runtime permissions](https://developer.android.com/training/permissions/requesting) and [location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime) — request permissions in context, explain purpose, handle denial gracefully, and prefer foreground location unless background access is truly core and justified.
- [Google Play: background location policy](https://support.google.com/googleplay/android-developer/answer/9799150) — background access needs a justified core use case, clear disclosure, and policy review; foreground access is preferred where sufficient.
- [Philippines Data Privacy Act of 2012 (NPC)](https://privacy.gov.ph/data-privacy-act/) and [Implementing Rules and Regulations](https://privacy.gov.ph/implementing-rules-regulations-data-privacy-act-2012/) — notice, legitimate purpose, proportionality, safeguards, data-subject rights, retention, and clear information are requirements to have counsel/privacy personnel validate against actual operations.
- [Material Design 3: Floating action button](https://m3.material.io/components/floating-action-button/guidelines) — reserve prominent floating actions for the most important action; do not confuse the GPS-map navigation shortcut with the requester's emergency/SOS submission action.
- [Google Maps Android navigation help](https://support.google.com/maps/answer/3273406?hl=en) — use familiar recenter/compass affordances, explicit start/stop actions, and an expandable information card. TACResQ must label external navigation as external unless it implements actual in-app routing.
- [Google Maps Android sheet redesign (9to5Google, 2025)](https://9to5google.com/2025/04/24/google-maps-sheet-redesign-android/) — a useful visual reference for keeping the map visible behind a compact, reversible sheet instead of replacing the map with full-screen detail content. Borrow the map-context principle, not Google's information architecture or unimplemented features.

## 3. Initial repository observations

These are code-inspection findings from the current default branch, not claims that every behaviour has been reproduced on a physical device.

- The client uses Jetpack Compose and Material 3 with a shared theme in `app/src/main/java/com/tacresq/app/ui/theme/Theme.kt`.
- Navigation and the role shell are implemented in `ui/AppRoot.kt` and `ui/RoleShell.kt`; requester, responder/driver, dispatcher, and map screens are separate composables.
- A dedicated `GpsMapScreen.kt` already exists and uses osmdroid. It includes connectivity/location checks and freshness handling; redesign must preserve and test these behaviours rather than replace the map with a static illustration.
- The shared role shell currently has role-specific labels/destinations and a prominent map entry point. Audit whether each label is truly a top-level destination, whether selected state/back behaviour are consistent, and whether a center-docked map control remains discoverable and unobstructed on narrow screens.
- `AppRoot.kt` contains generic role sections for profile, safety, history/requests, and field units. These need real-purpose content, consistency, and data/privacy review rather than merely changing colours.
- Requester and dispatcher screens include sync status and errors. Audit whether these messages are concise, actionable, and consistent; do not hide the fact that displayed data may be stale.
- The current theme defines light/dark palettes and shape tokens, but spacing, typography hierarchy, card treatments, status semantics, and component usage still need a cross-screen audit.
- A repository-tree audit found `SUPPORT.md` but no standalone privacy policy or terms-of-service document. Do not fabricate the responsible controller's contact details, legal basis, retention periods, or vendor disclosures; confirm them with the project owner and actual data flow before publishing a policy.
- A read-only schema inventory of the connected Supabase project found `profiles` (name/email/phone/role), `incidents` (description and coordinates plus location source/accuracy/timestamp), `location_logs` (user/incident-linked coordinates and timestamps), `fcm_tokens` (push tokens), `incident_assignments`, `incident_status_logs`, `incident_request_edit_logs` (before/after values), and `incident_submission_keys` (request/response payloads). Treat these as privacy-sensitive categories during notice, retention, access, logging, and cleanup design. This inventory records schema only; it does not establish actual retention duration, all third-party recipients, or the lawful basis for processing.
- The map recommendation document `docs/MOBILE_GPS_MAP_UI_RECOMMENDATION.md` includes useful placement guidance but contains older implementation-boundary statements that should be checked against the present repository before relying on them.
- Supabase/Render bridge work is tracked separately in PR #134. UI changes must not be represented as a substitute for the pending authenticated end-to-end verification.

## 4. Prioritized step-by-step backlog

### Phase 0 — Safety and baseline capture

- [ ] Record current main SHA, open PRs, CI state, app build variants, and backend deployment status before touching code.
- [ ] Verify the real-backend bridge PR and do not merge it until CI and authenticated synthetic-account RPC workflows pass. UI branch must not change that gate.
- [ ] Build and install the current demo/debug APK and the real-backend QA APK separately; label which backend each build uses.
- [ ] Capture current screenshots at a small phone viewport, a larger phone viewport, and landscape if supported for: login, requester home/request creation, request details/history, responder/driver current assignment, dispatcher queue/field units, GPS map, profile, safety/privacy.
- [ ] Record a baseline functional checklist for location permissions, location services off, no network, tile failure, empty data, stale locations, server rejection, duplicate submission, and back navigation.
- [ ] Never use live emergency records or real people's location/contact data as test fixtures.

**Exit criteria:** Baseline screenshots and test results are attached to the implementation PR; existing failures are clearly distinguished from regressions.

### Phase 1 — GPS map and location states (first UI implementation target)

- [ ] Keep the map a dedicated, full-screen top-level destination; verify the map can be reached in one tap from every role's primary screen.
- [ ] Define a calm, map-first hierarchy: map canvas dominates; compact top status/role label; accessible recenter control; concise bottom sheet or panel for selected incident/unit details; avoid large cards obscuring the incident area.
- [ ] Use consistent semantic marker shapes/icons for incident, current device position, and responder/driver; do not rely on colour alone. Markers must be distinguishable at typical zoom and have accessible detail alternatives.
- [ ] Provide a clear map legend or concise contextual key only when it helps interpretation; avoid permanently consuming map space.
- [ ] Keep controls at least 48 dp where practical, provide content descriptions, and ensure controls remain clear of gesture/system insets.
- [ ] Design distinct, short states for: locating, location permission not granted, device location turned off, location unavailable, network unavailable, map tiles unavailable, no incidents with coordinates, stale unit coordinates, invalid/future timestamp, and retry in progress.
- [ ] Show coordinate provenance and time: “reported by requester”, “last updated …”, “location unavailable”, or “stale — confirm before navigation” as appropriate. Never present a fallback city center as the user's exact position.
- [ ] Make recentering explicit and non-destructive; preserve manual map movement and avoid repeatedly snapping the camera while a user inspects pins.
- [ ] Keep incident details and navigation handoff reachable without hiding the map or confusing “open external navigation” with live in-app route guidance.
- [ ] Confirm the app's actual routing capability before adding a route line or ETA. If the app does not calculate routes, label external navigation honestly.
- [ ] Test rotation/recreation, returning from system Settings, permission denial/regrant, lifecycle pause/resume, network transitions, and marker selection.
- [ ] Review location capture/sharing permissions and consent separately from visual polish; don't add background access solely to make the UI look live.

**Exit criteria:** Build passes; map UI tests cover state rendering and selected marker actions; screenshots are reviewed; real-device tests confirm GPS/network states; no unsupported live-location claims.

### Phase 2 — Navigation shell and information architecture

- [ ] Inventory each role's current destinations and map every destination to a real feature, not placeholder content.
- [ ] Keep 3–5 primary destinations where possible; prioritize role tasks and use the same placement/selected-state rules across roles.
- [ ] Audit the central GPS-map control for label clarity, selected state, tap area, content overlap, accessibility semantics, and compatibility with Android back.
- [ ] Remove duplicate navigation routes and generic “Safety” or “Profile” placeholders only when their real replacement is implemented; preserve access to safety and privacy information.
- [ ] Make back behaviour predictable: map returns to prior primary role screen; system Back must not unexpectedly log out or reset a draft.
- [ ] Preserve requester draft input across recoverable interruptions and ask for confirmation before discarding meaningful input.
- [ ] Check small-screen and large-font layouts; avoid labels being clipped or navigation targets squeezed together.
- [ ] Keep the GPS map visible as a core destination without using the same visual prominence for every action. SOS must remain distinct from map navigation.

**Exit criteria:** Navigation tests pass for all four roles; no dead tabs or placeholder destinations; accessibility labels and selected states are verified.

### Phase 3 — Shared design system and screen consistency

- [ ] Define tokens for spacing, screen margins, typography, elevation, corner radii, icon sizes, and status colours in one shared location.
- [ ] Define reusable components for screen headers, status banners, sync state, incident cards, unit cards, empty states, errors, confirmation dialogs, and loading placeholders.
- [ ] Use one typography scale with clear title/body/metadata hierarchy; remove empty subtitles and repeated explanations where they add no task value.
- [ ] Standardize status wording and appearance across requester, dispatcher, responder, and driver; always pair colour with text/icon.
- [ ] Standardize buttons: one primary action per section, secondary outlined/text actions, destructive actions visually distinct and confirmed where appropriate.
- [ ] Support light/dark themes and system font scaling; validate contrast rather than relying on visual intuition.
- [ ] Use consistent spacing and scroll behaviour across screens; handle keyboard/insets so fields and actions aren't obscured.
- [ ] Replace decorative emoji with consistent Material/SVG/vector icons where icons are needed.
- [ ] Review loading, empty, error, and stale states in every screen—not just the happy path.

**Exit criteria:** Component previews/screenshots cover shared components and all roles; visual diffs reviewed at multiple viewport/font sizes; no duplicated ad-hoc status patterns remain.

### Phase 4 — Requester journey

- [ ] Keep “create emergency request” hidden until explicitly requested or clearly launched through the primary SOS/request action; avoid a permanently expanded form crowding the home screen.
- [ ] Keep one active request per requester if that is the confirmed business rule; block duplicate active submissions on the client while relying on server idempotency for correctness.
- [ ] Structure request creation into the fewest safe steps: incident details, location confirmation, final review/send. Do not add wizard steps unless they reduce errors.
- [ ] Show map pin, source (GPS/manual), reported accuracy, capture time, and a landmark/address note when available; allow correction before send.
- [ ] Distinguish “saved locally / awaiting server confirmation” from “accepted by server”; retry must not create a duplicate incident.
- [ ] Show active request status, assignment/response progress, and last successful sync clearly.
- [ ] Support safe edit/cancel rules; explain when editing is no longer allowed and request confirmation before cancellation.
- [ ] Use concise validation adjacent to the field, preserve entered values on errors, and never clear a request because the network failed.
- [ ] Make emergency guidance visible without overloading the form; avoid implying the app replaces official emergency services.

**Exit criteria:** Test submission, retry, cancellation, accidental submission, location correction, offline/timeout, and active-request rule against both UI and backend contracts.

### Phase 5 — Responder/driver workflow

- [ ] Put the assigned/current response first; make incident location, urgency, status, and next allowed action immediately scannable.
- [ ] Separate responder/driver status from global duty availability; labels must explain what each status means.
- [ ] Make location-sharing state explicit: off, requesting permission, sharing, paused, failed, stopped; show last successful report time and accuracy when available.
- [ ] Start/stop tracking only in the intended assignment/status lifecycle; stop when the response no longer qualifies, and make any recovery/resume flow verify the assignment again.
- [ ] Provide an external turn-by-turn navigation action only when a valid incident coordinate exists; warn about stale/missing coordinates.
- [ ] Make status transitions idempotent at the UI level and handle slow/rejected requests without optimistic false success.
- [ ] Keep history secondary to the active response.

**Exit criteria:** All allowed and forbidden status transitions tested; location consent, permission denial, stale data, and stop-tracking cases verified.

### Phase 6 — Dispatcher workflow

- [ ] Prioritize pending incidents by the confirmed operational rule and make the reason/order visible; do not present an unvalidated “AI score” as authoritative.
- [ ] Surface urgent requests, age/wait time, location confidence, and whether location is stale/missing.
- [ ] Keep assignment selection and confirmation clear; prevent selecting unavailable/ineligible units and preserve existing multi-responder limits.
- [ ] Distinguish “assignment submitted” from “assignment confirmed”; refresh state after successful writes.
- [ ] Keep the map and queue complementary: map for spatial awareness, queue for prioritization/assignment. Do not force complex dispatch actions into map markers alone.
- [ ] Show each unit's duty status and location freshness separately; absence of a marker means no usable coordinate, not necessarily “unit offline”.
- [ ] Ensure stale/offline data is prominent enough to prevent unsafe dispatch decisions.
- [ ] Review dispatcher access boundaries and auditability with backend/RPC contracts.

**Exit criteria:** Synthetic dispatcher workflow passes with requester/responder/driver test identities; unauthorized assignment and stale-location cases fail safely.

### Phase 7 — Authentication, account, safety, and privacy

- [ ] Make login/register screens concise, accessible, and clear about requester registration vs provisioned staff accounts.
- [ ] Keep demo shortcuts confined to the explicitly labelled demo build; never expose demo credentials in real-backend/release UI.
- [ ] Ensure password/email errors are specific and recoverable without unnecessarily exposing account existence.
- [ ] Add a real privacy notice accessible from account/safety areas before collecting sensitive location/contact data.
- [ ] The privacy notice must accurately identify the responsible organization/controller, purposes, legal basis, categories collected, who receives them, retention/deletion rules, security measures, data-subject rights and contact/complaint process, and relevant third-party services. Confirm each statement against actual data flows and approved policy; do not invent retention promises or legal claims.
- [ ] Explain location purpose at the moment of use; state whether capture is one-time or continuous, who can see it, when it stops, and how to disable it.
- [ ] Ask only for required permission scope; request foreground permission in context and request background access only if a separately justified, approved core feature requires it.
- [ ] Review location data in logs, crash reports, screenshots, analytics, notifications, and cached state; minimize or redact sensitive coordinates and personal details.
- [ ] Add accessible Terms, Privacy, Help/Support, and safety guidance entry points with maintained content and contact details.
- [ ] Have the project owner/privacy officer or qualified Philippine counsel review the notice against RA 10173 and its IRR before claiming compliance.

**Exit criteria:** Privacy copy approved by responsible stakeholders; permission disclosures match actual code; data access, retention, account deletion, and incident cleanup are documented and verified.

### Phase 8 — Accessibility, resilience, and QA across all screens

- [ ] Aim for 48 × 48 dp touch targets with adequate spacing; test using Android Accessibility Scanner where available.
- [ ] Add content descriptions/semantics to meaningful icons and map controls; mark purely decorative graphics appropriately.
- [ ] Verify TalkBack order, headings, field labels, error announcements, and status changes.
- [ ] Check text contrast and non-text contrast; do not use colour as the only way to identify status.
- [ ] Test larger font/display size, landscape, narrow devices, and keyboard-visible layouts.
- [ ] Ensure motion/animation is brief and nonessential; never continuously pulse or animate emergency controls.
- [ ] Test no network, high latency, API 401/403/429/5xx, invalid payloads, stale records, tile server outage, and process recreation.
- [ ] Test app lifecycle and cancellation of polling/location jobs; no background polling or location capture should continue outside the approved lifecycle.
- [ ] Keep screenshots and test evidence in the PR; record known gaps instead of marking all checks green by assumption.

**Exit criteria:** Automated CI and manual test evidence complete; unresolved accessibility/security issues documented; release readiness reviewed separately from visual acceptance.

## 5. Proposed pull-request sequence

Each PR should stay focused, use a feature branch, preserve the existing backend contract, and include before/after screenshots plus test evidence.

1. **UI-0 — Baseline inventory and this plan** (documentation only).
2. **UI-1 — GPS map layout and state presentation** (map remains full-screen; visual and interaction improvements; no backend contract changes).
3. **UI-2 — Navigation shell consistency and accessibility** (role destinations, selected state, back handling, touch targets).
4. **UI-3 — Shared design tokens and components** (theme, typography, spacing, status/error/sync components).
5. **UI-4 — Requester home and request submission flow** (reduce clutter, review/send clarity, draft and error resilience).
6. **UI-5 — Responder/driver current assignment and location-sharing feedback**.
7. **UI-6 — Dispatcher queue, unit list, and map-to-queue workflow**.
8. **UI-7 — Authentication/profile/help/safety/privacy entry points and copy review**.
9. **UI-8 — Cross-screen accessibility and resilience QA** (font scaling, TalkBack, touch targets, lifecycle/network matrix).
10. **UI-9 — Real-device end-to-end acceptance** (all roles, real-backend QA project/accounts, test-data cleanup, final release checklist).

Order can change only if baseline evidence identifies a higher-severity safety blocker. Avoid mixing unrelated UI and backend changes in one PR.

## 6. Release gate

Do not call TACResQ production-ready solely because the UI looks modern or CI builds. Release acceptance requires all of the following:

- [ ] CI passes on the exact reviewed commit.
- [ ] The Render bridge is deployed and live smoke checks pass.
- [ ] Authenticated synthetic requester, dispatcher, responder, and driver workflows pass through the intended transport.
- [ ] Authorization, RLS/RPC grants, idempotency, and forbidden-role actions are verified.
- [ ] GPS permission/device-setting/network/tile/stale-coordinate states pass on real devices.
- [ ] Privacy notice and actual location/data practices have been reviewed and approved.
- [ ] Accessibility and usability checks include manual TalkBack/device testing.
- [ ] TACRU operational workflow, incident escalation, support/monitoring, and outage procedures are explicitly accepted by responsible stakeholders.
- [ ] All QA data is clearly synthetic and cleaned up; no real emergency request is used for testing.

## 7. Review notes and change log

- 2026-10-24: Created initial plan from repository inspection and official Android, W3C, Google Play, and Philippine NPC guidance. This document does not claim that the planned work is implemented.
