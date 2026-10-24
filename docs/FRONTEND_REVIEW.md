# TACResQ frontend review standard

The automated reviewer is a triage layer. It must never label a screen "approved" solely because CI passed. Every UI change is reviewed against the general HCI checks and the role-specific checks below.

## General HCI / Android checks

- **Information hierarchy:** the primary task is obvious without reading long explanatory paragraphs. Labels use the user's vocabulary, not implementation terms.
- **Navigation:** retain the five-button navigation and keep GPS/map a primary destination. Show selected state, preserve sensible Back behavior, avoid dead ends and unexpected role mixing.
- **Consistency:** shared spacing, typography, icon style, status colors, card shapes, and button hierarchy. No decorative labels that compete with emergency actions.
- **Accessibility:** meaningful content descriptions, TalkBack order, adequate contrast, minimum 48dp interactive targets, scalable text, and no color-only status meaning.
- **State clarity:** loading, genuinely empty, stale, offline, permission-denied, validation, success, and server-error states are distinct. Retry/recovery actions must be actionable and not claim server success prematurely.
- **Lifecycle:** Compose collection and polling are lifecycle-aware; location listeners and map resources are cleaned up; returning from Settings refreshes permission/service state; process recreation and rotation do not duplicate submissions.
- **Responsive behavior:** verify narrow screens, large font scale, keyboard-visible forms, and system insets. Avoid brittle fixed dimensions.
- **Real integration:** actions must be traced to the real backend result. Demo/mock mode must be labeled; a local state update is not proof that the server persisted an action.

## TACResQ-specific workflow checks

### Requester
- Home shows current request state and a prominent Request Help action; the form stays hidden until requested.
- One active request per requester. Creating another requires canceling the current request through the supported workflow.
- Prevent duplicate SOS submissions during in-flight work and retain server-side idempotency.
- Distinguish accidental draft edits from changes to a submitted request. Draft discard/cancel requires confirmation when data would be lost; urgent location correction must follow the supported safety rule.
- Never show a request as submitted, assigned, or canceled until the backend confirms it.

### GPS / map
- Map remains a core feature, not a decorative replacement for clear GPS status.
- Distinguish permission denied, device Location disabled, no fix yet, stale fix, network/tile failure, and valid fix.
- Never fabricate coordinates. Manual pins must be clearly marked as manual and must not be presented as device GPS.
- Verify allow/deny/permanent denial, device Location off/on, approximate/precise permission where relevant, app resume from Settings, offline map tiles, and disposal/recreation.

### Responder and dispatcher
- Role-specific destinations and actions are clear; unauthorized actions are rejected by the backend.
- Per-responder assignment state is not confused with the incident's overall status.
- Queue loading, successful empty queue, stale cache, and failed initial fetch are visually distinct.
- Location sharing and tracking are consent-aware and clearly communicate freshness/availability.
- Retry, reassignment, cancellation, and partial failure do not falsely imply a successful operation.

## Review evidence and sign-off

For each UI PR, the report should list heuristic findings and manual gates. Human review should record relevant screenshots, emulator/device checks, role/account used (synthetic test account only), backend mode, and untested scenarios. Static analysis cannot judge visual polish, task completion time, map usability, or production readiness.
