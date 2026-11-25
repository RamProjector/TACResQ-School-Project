# Admin Page Feature Implementation Plan

## Objective
Complete the Admin website page workflows using verified repository and backend capabilities. Prefer working controls and source-backed data over static status cards. Do not present an unconnected integration as operational.

## Page inventory and implementation order

| Page | Current capability | Next implementation |
|---|---|---|
| Overview | Account summaries and dependency reachability | Separate live service state from repository-only checks; add clear per-dependency timestamps and actionable retry behavior. |
| User management | Search, pagination, role changes, account suspension/reactivation, lifecycle history | Add profile detail and recent account activity using existing protected APIs; preserve reason requirements and active-incident guards. |
| Security | Live MFA factor inventory, backup TOTP enrollment, factor removal safeguards, global sign-out | Add session/device inventory only if the Auth provider exposes a supported, correctly scoped API; do not infer devices from browser-local data. |
| Reports | Date-range aggregate metrics, assignment timing, CSV export | Add transparent chart/table controls and validate empty, partial, and timezone-boundary periods against the reporting RPC. |
| Activity log | Source-bounded investigation feed and page-local counts | Add source/category coverage clarity and counts that are explicitly scoped to the returned page or queried range. |
| Recovery & continuity | Documented procedures and release gates | Add live backup freshness and restore evidence only after an approved provider integration, protected evidence schema, and isolated restore workflow exist. Never trigger production backup/restore from a generic Admin page. |
| Attachment storage | Repository and dated inspection evidence | Add live bucket configuration/inventory and upload health only through a read-only, server-authorized inspection endpoint. Keep object contents private; no general Admin media browser. |
| Settings — General / Accessibility | Browser-local density, page size, contrast, motion, and contextual-help preferences | Verify every preference is applied consistently across all Admin components and persists safely. |
| Settings — Notifications | Informational placeholder | Implement only after a server-backed preference model and enforcement points exist. Do not add client-only controls that imply emergency notifications are suppressed. |
| Settings — Security & sessions | Links to the Security workspace | Keep authenticator operations in Security; add session controls only when supported by provider APIs and tested revocation semantics. |
| Settings — Privacy & data | Governance guidance and manual interim procedure | Build request intake/case tracking only after the controller/contact, retention policy, identity verification, authorization model, and audit requirements are approved. |
| Help & about | Searchable workspace guidance | Keep guidance aligned with actual page behavior and distinguish available, read-only, and unverified capabilities. |

## Guardrails
- Inspect the relevant frontend, RPCs, migrations, RLS policies, and tests before adding each feature.
- Reuse existing protected APIs where possible. If an API or policy is missing, add it with migration and regression coverage before wiring the UI.
- Never expose passwords, MFA secrets, recovery material, private attachment bytes, precise incident data, or service credentials in Admin UI or logs.
- Do not let navigation visibility substitute for server-side authorization.
- Do not create client-only toggles for emergency delivery, retention, backup, deletion, or access policy.
- Keep Admin account provisioning trusted-operator-only; preserve audit reasons and active-incident safeguards.
- No production migrations, destructive data operations, deployment, or PR merge without explicit approval.

## Delivery sequence
1. Complete an evidence-backed page/API gap review and identify reusable existing endpoints.
2. Implement one page workflow at a time with its backend contract, migration (if required), tests, loading/error/empty states, and responsive UI.
3. Run focused frontend checks and repository regression/security checks; inspect the diff and report any checks that remain queued or blocked.
4. Keep each cohesive implementation in a reviewable PR and do not claim a page is complete until its end-to-end behavior is verified.
