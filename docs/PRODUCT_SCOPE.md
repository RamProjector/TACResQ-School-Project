# TACResQ: Canonical Product Scope and Project Identity

**Status:** Canonical product-scope reference for this repository  
**Product:** TACResQ  
**Geographic focus:** Tacloban City, Leyte, Philippines  
**Primary purpose:** General emergency GPS assistance and incident-response coordination

## 1. What TACResQ is

TACResQ is a **general-purpose emergency GPS helper focused on Tacloban City**. Its purpose is to help people report an emergency with a usable location and help authorized responders, drivers, and dispatchers coordinate around that incident.

The core idea is **“GPS for emergencies”**: capture or confirm an incident location, communicate the report through the app's supported workflow, let authorized response roles review and coordinate the incident, and help the assigned field team navigate to the destination.

TACResQ is not restricted to one kind of emergency. The product concept covers general incidents that may require assistance; the exact incident categories, escalation rules, and response procedures must follow the application's implemented configuration and any authorized local operating policy. Do not invent official partnerships or claim that an agency has adopted or operates the app without verifiable evidence.

## 2. Intended users and responsibilities

- **Requester:** Reports an emergency, provides incident details and a landmark where useful, confirms the location-sharing choice, may share a one-time current GPS fix, submits an SOS, and checks the request's status.
- **Field responder:** Reviews an assigned incident, uses the destination coordinates to navigate, and updates the response using permitted status transitions.
- **Driver:** Supports an assigned response and uses the incident destination for navigation according to the role's permitted workflow.
- **Dispatcher:** Reviews the incident queue and available field units, confirms assignments, and coordinates supported response actions.
- **Administrator or trusted operator:** Handles only the administration functions explicitly implemented and authorized in the repository. Do not assume a role has privileges merely because its name appears in documentation.

### Response-team and field-leadership boundary

- A dispatch "team" currently means the set of responder/driver assignments attached to one incident (up to the backend-enforced limit); it is not a separately managed standing team roster.
- Dispatchers may select eligible available responders/drivers as a group. The first selected member may populate the legacy `assigned_to` compatibility field; that field must **not** be presented as proof that the person is a formally appointed team leader.
- Individual response progress is authoritative per assignment and is updated by the assigned responder/driver through the server-enforced status transition RPC. The incident status is a conservative aggregate, not a substitute for member-level status.
- Chat is for coordination and situation reports. It must not be used as the source of truth for assignment, arrival, or completion; those states come from authenticated status actions and server records.
- A formal Team Leader designation, leader-specific permissions, or dispatcher appointment workflow is **not implemented**. Do not add one until TACRU's authorized operating procedure identifies who may designate leaders, required qualifications, and the leader's actual authority. Do not create another login role merely to represent a designation.

These roles define TACResQ's emergency-response workflow and must remain specific to this product.

## 3. Product boundaries

### In scope

- General emergency incident reporting and SOS workflows.
- GPS-assisted incident-location capture, manual location/landmark clarification where supported, and clear handling of unavailable or stale location fixes.
- A map that helps users understand incident and device locations without misleading users about what is being shared.
- Role-appropriate incident review, assignment coordination, response-status updates, and navigation to an assigned destination.
- Clear loading, empty, stale-data, permission-denied, offline/error, retry, and submission-outcome states.
- Consent-aware location behavior, privacy-conscious handling of sensitive incident data, and safeguards against accidental duplicate emergency submissions.
- Practical quality-of-life improvements that make reporting and responding faster, clearer, safer, and more accessible on real phones in the Tacloban context.

### Not the product's purpose

- **Not LifeLink.** LifeLink is a separate repository/product and must not be used as the source of TACResQ's requirements, feature assumptions, screens, or implementation plan.
- Not a generic chat app, social network, fleet-management product, or broad enterprise management system.
- Not a replacement for official emergency hotlines, local emergency services, authorized dispatch procedures, trained responders, or medical advice.
- Not proof of continuous GPS tracking. A one-time location fix, a consented foreground location report, and continuous/background tracking are different behaviors and must be described accurately.
- Not proof that a real dispatch organization is operating the system. Treat this as an educational/engineering project unless deployment and operational authorization are independently verified.

## 4. Location and emergency-safety principles

1. **Never misrepresent location behavior.** Explain whether a location is a device preview, a submitted incident coordinate, or a consented responder location report. Do not describe one-time GPS sharing as continuous tracking.
2. **Do not hide uncertainty.** If GPS is unavailable, inaccurate, stale, or permission is denied, explain the limitation and provide supported alternatives such as a manual pin or landmark when available.
3. **Do not silently lose an emergency draft.** Preserve entered information when safe and technically possible; explain when an existing active request prevents a second submission and offer explicit supported actions.
4. **Avoid duplicate SOS creation.** If a submission's outcome is uncertain, reconcile with the server before inviting another send. Never blindly retry a time-sensitive emergency write.
5. **Show only confirmed response states.** Do not invent responder assignment, acceptance, arrival, ETA, delivery, or resolution. Distinguish a failed refresh from an empty queue.
6. **Fail closed for protected actions.** When authorization or assignment cannot be verified, do not enable actions that depend on that verification.
7. **Protect sensitive data.** Do not put real emergency details, credentials, tokens, or precise personal locations in source control, test fixtures, logs, screenshots, or public issue reports.
8. **Keep emergency guidance responsible.** The app can assist with reporting, location, and coordination; it must not imply that opening the app guarantees help or replaces contacting the appropriate official emergency service.

## 5. Implementation truth versus product intent

This document defines product identity and direction; it does not claim every planned capability is implemented or operationally validated.

Before describing a feature as complete, inspect the current source and tests. In particular:
- The current Android map uses OpenStreetMap/osmdroid and displays supported incident/device-location information.
- Current responder navigation opens external turn-by-turn navigation; do not claim that full in-app turn-by-turn routing or a guaranteed ETA exists unless implemented and verified.
- Background responder tracking and FCM push notifications are not implemented according to the current README status table.
- Physical-device GPS, network, tile-loading, and real-backend behavior must not be called verified solely because CI builds pass.
- A passing build or test does not establish live service readiness, official operational approval, or guaranteed emergency delivery.

Update this section when verified implementation changes, and keep the README status table consistent.

## 6. Product-design priorities

When planning improvements, prioritize in this order:

1. **Safety and correctness:** prevent false claims, duplicate SOS requests, unauthorized actions, and silent loss of user input.
2. **Location clarity:** make GPS, manual pin/landmark, permission, freshness, and sharing states unmistakable.
3. **Emergency-task efficiency:** minimize time and cognitive load for a requester to report an incident and for a responder to understand the destination and next action.
4. **Role-specific workflow:** keep requester, responder, driver, dispatcher, and administrative responsibilities distinct.
5. **Resilience:** handle slow or missing connectivity, refresh failures, lifecycle changes, retries, and uncertain submission outcomes.
6. **Accessibility and usability:** readable text, clear hierarchy, appropriately sized touch targets, screen-reader semantics, contrast, and small-screen/text-scaling support.
7. **Performance and maintainability:** profile real bottlenecks, avoid needless location polling and UI work, and add regression tests around critical workflows.

Do not add features merely to increase feature count. Any proposed feature should solve a TACResQ emergency GPS/reporting/response problem and should be checked against existing behavior before implementation.

## 7. Required repository and agent guardrail

**For every future TACResQ task, read this document before researching, designing, editing, or reviewing code.**

- Keep TACResQ and LifeLink as separate products and repositories.
- Keep requirements, screens, data models, and terminology specific to TACResQ's emergency GPS and incident-response domain; do not import unrelated product requirements.
- Keep proposed changes tied to general emergency reporting, GPS/location clarity, authorized response coordination, navigation, privacy, reliability, and accessibility for the Tacloban focus.
- Inspect the current branch, relevant files, previous PRs, and CI before making changes; avoid redoing work already merged.
- Prefer one coherent, reviewable PR at a time. Add or update tests for safety-critical behavior, run required checks, and merge only when the required checks pass.
- Do not change production services, use paid services, add credentials, or make destructive backend changes without explicit approval.
- Record what changed and what remains unverified. Never claim a PR, test, deployment, or merge succeeded unless the repository/tool result confirms it.

## 8. One-sentence project description

> **TACResQ is a Tacloban-focused general emergency GPS helper that supports incident location sharing, authorized response coordination, and navigation to reported emergencies.**



## 9. Research basis and implementation decision — 2026-10-31

Public-source review found:

- Tacloban City's CDRRM Plan 2016–2022 states that the CDRRMO exercises direction and control over emergency operations from city to barangay level. The same plan identifies revision/enhancement of existing TACRU Emergency Response Team guidelines as an action, with an approved TACRU guidelines document as the intended verification. This shows that TACRU-specific operational rules are relevant, but does **not** disclose who appoints a field leader or what that leader may do. The plan period ended in 2022 and must not be treated as the current TACRU SOP. Source: https://storage.googleapis.com/request-attachments/CHVEib8tSDiwuuUSzJIqgxoikqAvhjsGg8ZXEqvALshyCL4lZ3BQmXgMse5KKDKRtIjN2aWOCzTUilaoXOo5RxcFrot04zx7KyKn/Tacloban%20CDRRMP.pdf
- Republic Act 10121 assigns LDRRMOs responsibility to organize, train, and directly supervise local emergency response teams and accredited community disaster volunteers. Source: https://elibrary.judiciary.gov.ph/thebookshelf/showdocs/2/21121
- The Philippine ICS Field Operations Guide recognizes Strike Team/Task Force Leaders and Single Resource Boss positions. It describes defined operational leadership: receive assignments through a supervisor, organize/brief subordinates, monitor progress and safety, and report resource/situation status up the chain. ICS emphasizes chain of command and unity of command. A field leader is therefore an operational assignment, not a new login identity or an arbitrary dispatcher label. Source: https://studylib.net/doc/27939895/ics-field-operations-guide
- Tacloban City's 2023 Annual Report describes a 24/7 CDRRMO monitoring/response team, but does not publish TACRU field-leader appointment rules or a current operational roster. Source: https://new.tacloban.gov.ph/wp-content/uploads/2025/02/2023-ANNUAL-REPORT.pdf
- Public reporting describes TACRU as established under CDRRMO and operating shifts with organic personnel and volunteers; this is contextual reporting, not an authority document. Source: https://www.sunstar.com.ph/tacloban/local-news/tacloban-rescue-unit-tops-disaster-humanitarian-response

**Implementation decision:** Keep existing Dispatcher and Responder accounts. If TACRU confirms a leadership designation, model it as an incident/resource-scoped designation assigned only by the authority specified in the current TACRU SOP—not as a fifth account role and not by assuming the first selected responder is the leader. Keep leadership separate from assignment membership. Record who designated/changed the leader, timestamp, reason, and authorizing basis. Give the leader only coordination/reporting permissions explicitly confirmed by SOP; do not silently grant authority to change incident priority, dispatch/release resources, override another responder's status, or close incidents.

**Until TACRU confirms the current SOP:** retain multi-responder incident assignment and individual status tracking; do not expose an unrestricted leader picker or automatically designate a leader. If a response requires a leader but no authorized designation exists in system data, show "Field lead not recorded" rather than inventing one. Obtain the current approved TACRU Emergency Response Team guidelines / CDRRMO Manual of Operations and confirm: (1) who appoints the field lead, (2) whether designation is per shift/team or per incident, (3) qualifications, (4) exact authority and reporting line, and (5) whether volunteers or junior rescue members may be assigned.
