# Admin Page Feature Implementation Plan

## Objective
Complete the TACResQ Admin website using verified repository and backend capabilities. Keep each page focused on its own decision-making purpose, remove repeated information, and prefer working controls and source-backed data over decorative status cards. Never present an unconnected integration as operational.

## Current page capability and remaining work

| Page | Implemented capability | Remaining work / dependency |
|---|---|---|
| Overview | Account summaries, protected recent activity preview, on-demand dependency reachability, operational shortcuts, and clear health loading/error states. | Keep the page focused on current attention items; do not duplicate Analytics aggregates or Activity Log event counts. |
| User management | Protected paginated directory, search/role filters, reasoned ordinary-role changes, account suspension/reactivation, lifecycle history, and on-demand protected account activity preview. Admin provisioning remains trusted-operator-only. | Continue validating stale-page handling, permission failures, and active-incident transition guards. Do not add unverified profile fields. |
| Security | Current-session AAL/TOTP status, registered TOTP factor inventory, verified backup-authenticator enrollment, factor-removal safeguards, explicit global sign-out, and a five-event authentication preview. | Session/device inventory and arbitrary user-session controls require a supported provider API and independently verified revocation semantics. Authentication preview is not complete sign-in history. |
| Analytics & reports | Date-bounded aggregate request metrics, current status distribution, daily request chart, average/median first-assignment timing, Manila-time reporting, and CSV export. | Validate partial periods, zero-data periods, timezone boundaries, and chart accessibility against the reporting RPC. Keep aggregates separate from event-level investigation. |
| Activity log | Searchable, filterable, paginated, source-bounded investigation feed with explicit coverage limitations. | Improve source coverage only through trusted backend readers. Do not infer zero activity from missing sources or page-local results. |
| Data & continuity | Consolidated Backup & recovery and Attachment safeguards views, documented operator procedures, dated inspection context, and explicit disclosure that live evidence is not connected. | Backup freshness, off-site copy, and restore evidence require approved provider integration and protected evidence storage. Live bucket inventory/upload health requires a read-only server-authorized endpoint. No media browser or production restore control. |
| Settings — General | Browser-local interface density and default directory page-size preferences. | Verify consistent application across all Admin pages and storage failure handling. |
| Settings — Accessibility | Browser-local reduced-motion, high-contrast, and contextual-help preferences. | Continue keyboard, focus, contrast, and reduced-motion regression checks across every workspace. |
| Settings — Notifications | Informational read-only guidance; no quiet-hours or suppression toggle. | Requires a server-backed preference model and enforcement at the actual emergency-notification delivery points. Do not imply alerts can be suppressed until that exists. |
| Settings — Privacy & data | Data-subject-rights guidance, manual interim handling steps, and release gates for an online request centre. | Requires approved controller/contact details, privacy notice, retention schedule, identity verification, case model, authorization, audit, and disposal workflows. |
| Help & about | Searchable guidance aligned to the Admin workspaces and their current limitations. | Keep links, labels, and descriptions synchronized with implemented behavior. |

## Page-cleanup rules
1. Overview answers: what needs attention now? Keep it compact.
2. Analytics & reports answers: what is happening over a selected period? Show aggregate trends and metrics.
3. Activity log answers: what individual recorded events occurred? Keep event investigation separate from aggregates.
4. User management owns account and role lifecycle actions. Security owns authenticator and current-session controls.
5. Data & continuity groups recovery and attachment safeguards without implying live provider controls.
6. Settings status labels must distinguish editable browser preferences from read-only guidance.
7. Remove duplicated helper copy, redundant cards, stale labels, and controls that do not perform a verified action.
8. Preserve loading, error, empty, stale-data, keyboard-focus, and responsive states during cleanup.

## Guardrails
- Inspect the relevant frontend, RPCs, migrations, RLS policies, and tests before adding each feature.
- Reuse existing protected APIs where possible. If an API or policy is missing, implement it with migration and regression coverage before wiring the UI.
- Never expose passwords, MFA secrets, recovery material, private attachment bytes, precise incident data, or service credentials in Admin UI or logs.
- Navigation visibility is not an authorization boundary; server-side authorization remains authoritative.
- Do not create client-only toggles for emergency delivery, retention, backup, deletion, or access policy.
- No production migrations, destructive data operations, deployment, or PR merge without explicit approval.

## Delivery sequence
1. Complete evidence-backed page/API gap review and identify reusable endpoints.
2. Implement one page workflow at a time with backend contracts, migrations where required, tests, and complete loading/error/empty states.
3. Consolidate visual rules only after confirming the actual markup and responsive behavior; avoid layering more CSS overrides over existing duplicates.
4. Run focused frontend checks and repository regression/security checks. Report any checks that remain queued or blocked.
5. Do not call a page complete until its end-to-end behavior is verified.
