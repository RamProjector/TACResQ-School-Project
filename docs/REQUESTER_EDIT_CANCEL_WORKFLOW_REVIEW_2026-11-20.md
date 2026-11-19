# Requester Edit and Cancellation Workflow Review — 2026-11-20

## Decision

Keep the existing requester policy: one active incident per requester. While an incident is **pending and not assigned**, its owner may edit the report (including correcting the location pin) or cancel it. Once dispatch assigns a response unit, requester self-service edit/cancel is rejected; the requester must contact dispatch for urgent corrections or cancellation coordination. Resolved and cancelled incidents remain historical records and are not editable.

This review does not introduce a permanent location lock at SOS submission. A location correction remains possible during the pending window because correcting a wrong emergency destination is safety-critical.

## Repository findings

Reviewed the active PR #335 branch, `security/incident-assignment-privilege-hardening`, at head `64969179e1a6f24ad3df7fa9a20a5eb0350ee51e`.

- Android requester home shows Edit and Cancel actions only when the active incident status is `PENDING`.
- The edit dialog supports emergency type, description, location notes, and map-pin correction.
- The Android repository calls Supabase RPCs `api_update_my_incident` and `api_cancel_my_incident`.
- The edit RPC verifies the authenticated requester profile, ownership, pending status, and null legacy assignment; it locks the profile and incident rows.
- Location changes validate coordinate pairs/ranges, source, manual-pin accuracy, and client-reported capture/selection timestamp freshness. Unchanged existing location metadata can be retained for text-only edits.
- Edits are recorded in `incident_request_edit_logs`; the database trigger creates generic, deduplicated dispatcher inbox notifications.
- The edit workflow applies a 60-second cooldown after an edit, requires review confirmation for subsequent edits, and allows a reviewed location-only correction during the cooldown.
- The cancellation RPC verifies requester role and ownership, locks the incident row, and permits cancellation only while pending. The incident status audit trigger records status transitions.
- The one-active-request guard excludes resolved and cancelled incidents, so cancellation frees the requester's slot for a new SOS.
- The Laravel backend route file does not expose requester edit/cancel routes. The current Android requester edit/cancel client path is the Supabase RPC path; do not describe this as parity across both backends.

## Review assessment

The existing implementation is directionally sound and should be preserved. The main test gap addressed in this commit is authenticated cancellation coverage: ownership denial, terminal-state denial, successful pending cancellation, attributable status audit, and the ability to create a new request after cancellation.

The UI's status check is a presentation safeguard only. The locked, server-side RPC check is authoritative when assignment and requester actions race. A stale screen must not be able to change an incident after dispatch assignment.

## Research basis

- OWASP Authorization Cheat Sheet: authorization must be checked for each requested object/action; guessed or manipulated identifiers must not bypass ownership checks.
  https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- OWASP Business Logic Security Cheat Sheet: contextual authorization depends on the object state at the time of the action; check-then-act operations should be atomic, using transactions, row locks, or conditional updates.
  https://cheatsheetseries.owasp.org/cheatsheets/Business_Logic_Security_Cheat_Sheet.html
- OWASP REST Assessment Cheat Sheet: test object-level authorization on writes as well as reads, including identifier substitution between accounts.
  https://cheatsheetseries.owasp.org/cheatsheets/REST_Assessment_Cheat_Sheet.html
- Android location documentation distinguishes device location estimates from user-selected map coordinates; location accuracy/provenance should not be fabricated for a manual pin.
  https://developer.android.com/develop/sensors-and-location/location

## Validation scope and deployment boundary

- Added authenticated PostgreSQL migration-replay assertions for requester cancellation.
- Extended the source contract to pin the Android pending-only action visibility and Supabase cancellation RPC guards.
- These are committed regression checks; they are not evidence that the full CI suite or a live Supabase environment has passed.
- No production migration was applied, no Render deployment/configuration was changed, and PR #335 remains unmerged.
- Manual Android UI acceptance and a clean PostgreSQL migration replay remain required before merge.
