# TACResQ Admin Website — Completion Gap Assessment and Research Plan

**Review date:** 2026-11-24  
**Reviewed branch:** `feat/tacresq-admin-portal-complete`  
**Related pull requests:** #348 (Admin portal continuation; stacked on #347), #347 (Admin account-security foundation)  
**Review type:** Repository inspection and external standards research  
**Change scope:** Documentation only. No runtime code, database schema, RPC grants, production data, provider settings, or deployments were changed.

## 1. Executive assessment

The current Admin website is a useful operational starting point, but it is not yet a complete administration workspace. The checked branch currently exposes five primary sections:

1. Overview
2. User management
3. Security
4. Reports
5. Activity log

The current operator guide confirms those sections and their present limits. The Admin console's section union and navigation list also contain only `overview`, `users`, `security`, `analytics`, and `audit`. There is no dedicated Settings workspace, Help/About area, accessibility preferences page, notification-preference page, privacy-governance workspace, integrations dashboard, or recovery-readiness page in the reviewed console.

The important gap is not simply a lack of menu items. Several useful administration capabilities need trustworthy backend evidence and enforceable authorization before they can be represented as working controls. The UI must not imply that a system is healthy, an audit trail is complete, a notification preference is effective, an account is disabled, or a backup is recoverable unless the underlying service actually proves it.

**Recommended direction:** complete the Admin information architecture and usability first, then add evidence-backed security, privacy, operational, and resilience workspaces in dependency order. Keep infrastructure secrets and high-impact operations outside ordinary Admin preferences.

## 2. Repository evidence and current boundaries

The following files were read from the reviewed branch:

| Repository evidence | What it establishes |
|---|---|
| `backend/web/src/pages/AdminConsole.tsx` | The current primary Admin sections are Overview, User Management, Security, Reports, and Activity Log. The console already has navigation state and page-level composition that a future Settings workspace must integrate with rather than duplicate. |
| `backend/web/src/lib/supabase.ts` | Admin-facing types and data access already include aggregate analytics, dependency health, paginated user data, investigation events, and account lifecycle history. These are existing foundations, not proof that every possible operational/security event is collected. |
| `docs/admin-portal-operator-guide.md` | Documents the five available sections, aggregate report export, current directory controls, and important limitations. It explicitly says dependency reachability is not an end-to-end emergency-readiness check. |
| `docs/ADMIN_AUDIT_TRAIL_SOURCE_INVENTORY_2026-11-22.md` | Records a source inventory and deployment-boundary review for Admin investigation history. Its header references an earlier branch/PR; treat its deployment snapshot as dated evidence, not a live guarantee. |
| `docs/security/security-event-coverage-and-observability-review.md` | Describes distinct security and operational event sources and known collection gaps. Its header references an earlier branch; confirm source behavior against the current code before implementing changes. |
| `docs/security/authentication-event-collection-boundaries.md` | Explains why Laravel-only authentication logging cannot represent the direct Supabase Auth flows used by Android and the web portal. |
| `docs/security/backup-disaster-recovery-research-and-implementation-plan.md` | Documents the separation between Postgres data, Storage object bytes, and the Render-hosted service, and why a database backup alone is not a complete application recovery plan. |

### Current capability boundaries to preserve

- The Reports workspace exposes aggregate request metrics and a privacy-limited CSV export. Do not turn it into a personal-data export by default.
- The Activity Log / investigation view is bounded by its underlying event sources. Do not label it a complete system-wide audit trail while sources remain incomplete.
- The Security workspace includes administrator TOTP/AAL2 status and factor-management safeguards. This is not the same thing as a complete security-event monitoring centre.
- Dependency reachability checks are useful signals, but they do not prove that a user can complete an emergency workflow end to end.
- The existing Admin role must not implicitly receive dispatcher, responder, driver, incident-chat, or dispatch powers. Any new permission must be explicitly justified by the authorization model and enforced server-side.
- Do not expose account suspension/reactivation, destructive data actions, restore operations, or security-policy mutations as active UI controls until their authorization, audit, recovery, and abuse-prevention gates are implemented and reviewed.
- Do not infer an organization/agency hierarchy, qualification model, certification system, duty roster, or tenant boundary that the deployed schema does not establish.

## 3. Recommended Admin information architecture

The following is a proposed target structure, not a claim that these pages already exist.

### 3.1 Overview

Keep Overview as the operational landing page. Improve it with:

- Clear summary of what is measured and the time window.
- Last-updated timestamp and explicit stale/unknown state.
- Shortcuts to User Management, Reports, Activity Log, and Security.
- A small set of actionable exceptions, not a wall of decorative KPI cards.
- Clear distinction between service reachability and end-to-end workflow health.

Do not show a green “healthy” state merely because a health endpoint responds.

### 3.2 User management

Retain the existing paginated directory and reason-required role changes. A later account detail view may include:

- Account identity and current role.
- Role/permission explanation grounded in the actual server policy.
- Account creation and role-change history where sources are available.
- MFA status only when the data source is authoritative and access is justified.
- Relevant lifecycle events and investigation references.

A user detail page must not become a hidden route to bypass list-level authorization. Enforce object-level access on the server, not only by hiding buttons.

### 3.3 Security

Evolve Security into a clearly bounded security workspace with separate views:

- **Security overview:** coverage and source-health summary; show “unknown” or “not collected” where appropriate.
- **Authentication events:** successful/failed sign-ins, session events, MFA challenges, and recovery events only where the actual provider/application sources can be collected and correlated.
- **Privileged actions:** role changes, security-sensitive operations, and denied attempts with actor, target, time, outcome, and reason where safe.
- **Access review:** a readable permission matrix and periodic review of who can perform privileged operations.
- **Event-source coverage:** what is collected, by which component, what is missing, access restrictions, and retention.

Do not merge authentication logs, operational incident histories, infrastructure logs, and application error logs into one undifferentiated feed. Preserve source identity and event semantics.

### 3.4 Reports and operational oversight

Reports should remain privacy-safe and should distinguish aggregate analytics from operational monitoring. A future read-only Operational Oversight workspace could present:

- Incident/request volume and status distribution.
- Response progress only when timestamps and status semantics are defined.
- Assignment integrity and conflicting/duplicate assignment exceptions.
- Location freshness/quality only when the system stores enough evidence to assess it.
- Notification delivery outcomes from the existing delivery-attempt source, with clear coverage limits.
- Chat availability or delivery indicators only if the actual chat system exposes reliable evidence.

Do not add dispatch controls or turn Admin into a dispatcher. Do not create metrics from guessed timestamps or ambiguous status labels.

### 3.5 Activity log

Keep the event-source scope visible. Recommended refinements:

- Filters by category, source, actor, target, incident, outcome, and date where the underlying query supports them.
- A clear explanation of what is and is not included.
- A per-source freshness/coverage indicator.
- Safe event details with sensitive values redacted.
- Export controls that show scope and protect against CSV formula injection.
- Audit access to sensitive investigation records and export events where feasible.

Do not call this a complete audit trail until the source inventory, collection, access, retention, and integrity requirements are satisfied.

## 4. Settings workspace — recommended subpages

Settings is the most visible missing part of the Admin experience. It should be a real workspace with grouped subpages, not a single long form. Split personal preferences from global policy and infrastructure configuration.

| Proposed subpage | Intended contents | Safe first release | Dependencies / restrictions |
|---|---|---|---|
| **General & appearance** | Theme, density, language/locale if supported, date/time display, default landing page, table page-size preference | Personal UI preferences with reset-to-default and save feedback | Persist only preferences that the app can honor. Avoid organization-wide branding/config without a defined owner and policy. |
| **Accessibility** | Reduced motion, text scaling/comfortable density, contrast preference, focus/keyboard guidance, screen-reader support notes | Honor system preferences and add user-controlled options only when implemented end to end | Must be tested at zoom, keyboard, touch, forced-colors/high-contrast, and assistive-technology levels. Do not offer decorative toggles that do not change behavior. |
| **Notifications** | Notification categories, delivery channel, quiet hours, frequency, test/diagnostic explanation | Read-only explanation of current delivery capabilities and status | Do not expose toggles until preferences are stored, validated, enforced by every relevant sender, and respected by emergency-critical notification rules. Emergency alerts must not be silently suppressed by a generic quiet-hours switch. |
| **Security & sessions** | MFA status, session/device visibility, sign-out guidance, recovery guidance, security notices | Read-only status and safe navigation to existing MFA controls | Session revocation, device removal, password policy, and account lock controls require authoritative APIs, strong re-authentication, audit evidence, and server-side enforcement. Never display secrets. |
| **Privacy & data** | Privacy notice, data categories/purposes, retention explanation, rights-request guidance, data export/deletion request status | Accurate notice and contact/request instructions grounded in approved policy | Do not invent a retention period, legal basis, controller identity, or deletion promise. A request workflow requires identity verification, case tracking, and a documented response process. |
| **Help & about** | Version/build, support contact, operator guide, FAQ, glossary, troubleshooting, known limitations | Static documentation, app version/build metadata, and contextual help | Support contacts and release metadata must come from maintained project configuration; do not invent contact details or claim 24/7 support. |
| **System configuration** (restricted) | Approved non-secret configuration inventory, change history, release information | Read-only inventory where safe | Do not build an environment-variable editor or expose provider credentials in the Admin website. Infrastructure secrets remain in trusted provider controls. |

### Settings behavior requirements

Every editable preference should have:

- A clear description and scope (this Admin account vs all users vs system-wide).
- A visible current value and default/reset option.
- Explicit Save/Cancel behavior or a clearly documented autosave model.
- Saving, saved, validation-error, and retry states.
- Unsaved-change protection when leaving a dirty page.
- Server-side validation for any persisted value.
- A last-updated indicator where the source supports it.
- A keyboard- and touch-accessible interaction pattern.

Preferences must not silently become global system policy. Use separate permission gates and explicit confirmation for global changes.

## 5. Contextual help and quality-of-life improvements

Question-mark help icons are recommended, but important instructions must not depend on hover alone.

### Contextual help pattern

- Place a small help affordance beside unfamiliar labels or complex metrics.
- On desktop, allow hover and keyboard focus to reveal a short explanation.
- On touch devices, open the same explanation on tap.
- Provide an accessible name and relationship to the control/description.
- Keep the popover dismissible with Escape and avoid covering the focused control.
- Use a persistent help link or disclosure for longer instructions, limitations, and safety-critical guidance.
- Never put required instructions exclusively in a tooltip.

### High-value usability improvements

1. **Consistent page structure:** breadcrumb, page title, one-sentence purpose, primary action, and page-specific help.
2. **Navigation:** stable sidebar/section navigation, clear active state, mobile collapse, and browser Back/Forward support.
3. **Search:** search Settings/help content and long tables where it is genuinely useful; preserve server-side bounds.
4. **Tables:** meaningful empty states, loading skeletons, visible filter state, result count, page-size control, and accessible horizontal scrolling.
5. **Forms:** labels, inline validation, error summary, field-level help, and preservation of safe user input after recoverable failures.
6. **Feedback:** clear success/error status, no silent failures, no duplicate submissions, and disabled/loading state during writes.
7. **Destructive actions:** explain consequences, identify affected records, require reason where appropriate, and use a second confirmation step for high-impact actions.
8. **Time display:** show timezone explicitly and keep timestamps consistent across Reports, Activity Log, and event details.
9. **Status design:** never communicate health or risk by color alone; pair status color with text/icon/meaning.
10. **Responsive behavior:** test narrow mobile widths, tablet layouts, desktop, large displays, and browser zoom.
11. **Help discovery:** operator guide, glossary for TACResQ-specific terms, and “why am I seeing this?” explanations for metrics and warnings.
12. **Privacy-safe defaults:** mask sensitive values, avoid unnecessary personal data, and make export scope visible before download.

## 6. Additional high-value workspaces after Settings

These should be considered separate workstreams because they depend on new data coverage, governance, or backend controls.

### 6.1 Privacy governance

Proposed areas:

- Privacy notice and policy version history.
- Data inventory: categories, purpose, source, recipients/processors, and retention basis.
- Retention schedule and disposal evidence.
- Data-subject request intake/status (access, correction, objection, and other applicable rights).
- Privacy incident assessment and escalation record.
- Third-party processor inventory.

The Philippine Data Privacy Act requires transparent information about processing and recognizes data-subject rights. Retention should be tied to a justified purpose and applicable legal requirements; this document does not set a legal retention period or substitute for a privacy officer/legal review. See the NPC source in References.

### 6.2 Integrations and API health

A read-only integrations page could summarize Supabase Auth/Database/Storage, Render-hosted API, mapping/routing provider, and push notification provider. Each status should include:

- Healthy / degraded / unavailable / unknown.
- Last successful check and observation time.
- What the probe actually tested.
- Failure category and safe diagnostic reference.
- Data freshness and whether the probe is stale.

A successful ping is not an end-to-end workflow test. Do not expose secrets, internal tokens, raw stack traces, or unrestricted provider logs.

### 6.3 System resilience and recovery readiness

A read-only resilience workspace may show:

- Database backup evidence and last verified restore test.
- Storage object backup/integrity evidence separately from database backup status.
- Deployment/release information.
- Recovery runbook and ownership.
- Recovery objectives (RTO/RPO) only after they are approved and realistically testable.
- Known recovery gaps and last review date.

Do not claim recoverability because a provider dashboard says “healthy.” Do not initiate restore/rollback from the Admin portal in the initial release. The existing backup/recovery review explains why Postgres backups, Storage object bytes, and Render service persistence are separate concerns.

### 6.4 Attachment storage health

A future storage-health view should report upload success/failure, allowed type/size policy, incomplete uploads, object metadata/integrity, access denials, and retention/deletion status. It should not become an unrestricted image/video browser. Incident attachments remain sensitive and should be accessible only through approved incident membership and authorization rules.

### 6.5 Configuration and change history

Keep personal preferences separate from system-wide configuration. A future read-only inventory can show approved non-secret settings, version, last change, actor/reason, and safe before/after values. Secret values must remain masked and must not be retrievable by ordinary Admin users.

## 7. Security, privacy, and accessibility design gates

### Security and auditability

Use OWASP ASVS 5.0 V16 and the OWASP Logging Cheat Sheet as review baselines:

- Maintain an inventory of log sources, event types, formats, access, use, and retention.
- Capture relevant authentication decisions and failed authorization attempts where technically possible.
- Include who/what/when/where/result context without logging passwords, tokens, backup codes, or unnecessary personal data.
- Protect logs against unauthorized access, injection, tampering, and uncontrolled export.
- Distinguish security logs from operational/error/performance logs.
- Display incomplete collection honestly; missing evidence must not be shown as “zero events.”
- Record access to and export of sensitive investigation data where feasible.

OWASP API Security Top 10 should guide review of any new Admin API/RPC: object-level authorization, authentication, property-level authorization, resource limits, function-level authorization, sensitive business flows, SSRF, security misconfiguration, inventory, and unsafe upstream API consumption.

### Privacy

Before implementing privacy controls, confirm:

- Who is the personal information controller and who is the responsible contact.
- What data categories TACResQ processes and for what purposes.
- Which third-party processors receive data.
- Applicable retention and disposal rules.
- How data-subject requests are verified, assigned, tracked, and answered.
- Which incident facts are required for a privacy breach assessment and who is authorized to decide notification obligations.

A suspected incident is not automatically a reportable breach. Any mandatory notification assessment and deadline must be handled by the responsible privacy/security personnel under the applicable NPC rules.

### Accessibility

Use WCAG 2.2 Level AA as the target baseline. Include:

- Keyboard operation for every action and navigation control.
- Visible focus that is not hidden by sticky headers, dialogs, or popovers.
- Labels, instructions, and programmatically associated errors.
- Adequate target size and spacing for pointer/touch controls.
- No color-only status communication.
- Reflow and zoom without loss of content or functionality.
- Screen-reader announcements for asynchronous results and save/error states.
- Reduced-motion support.
- Alternatives to charts, including accessible tables or textual summaries.

The existing branch has focus and reduced-motion styling, but that does not establish full WCAG conformance. Automated checks must be supplemented by keyboard, screen-reader, touch, and zoom testing.

## 8. Implementation dependencies and order

| Priority | Workstream | Why this order | Exit criteria |
|---|---|---|---|
| P0 | Confirm Admin route/permission model and source inventory | Prevents building pages on unsupported or unsafe assumptions | Current route map, authorization matrix, data-source map, and known limitations documented |
| P1 | Settings shell + Help/About + General preferences | Completes the missing information architecture with low-risk, personal-scope features | Navigation works; preferences are real, scoped, validated, accessible, and reversible |
| P1 | Accessibility preferences and interaction refinements | Benefits every Admin workspace and reduces future rework | Keyboard/touch/zoom/screen-reader review; no inert accessibility controls |
| P2 | Security coverage and access review | Depends on event-source and authorization coverage | Event inventory, collection limits, source freshness, access control, and retention are explicit |
| P2 | Privacy & Data governance | Requires approved policy ownership and data inventory | Approved notice, controller/contact, purposes, processors, retention rules, and request process |
| P3 | Operational Oversight and Integrations | Requires defined operational metrics and reliable health probes | Each metric has source, definition, timestamp, freshness, and unknown/degraded behavior |
| P3 | Resilience and attachment-storage health | Requires verified backup/object integrity and restore evidence | Database and object recovery evidence separate; tested runbook and ownership |
| P4 | Configuration/change history | Requires governance and reliable configuration inventory | No secrets exposed; changes attributable and reviewable; privileged actions server-enforced |

### Questions that must be answered before later phases

1. Is TACResQ intended for one organization or multiple agencies/tenants?
2. Who owns system-wide settings and who can approve changes?
3. Which notification channels and user preference rules are actually implemented?
4. What is the approved privacy controller/contact and retention schedule?
5. Which event sources can be read reliably in the deployed environment?
6. What backup/restore evidence is available for database rows and Storage objects?
7. What are the approved incident-response, recovery, and escalation owners?
8. Which Admin actions are read-only, which are privileged, and which are intentionally unavailable?

Do not answer these questions by assuming an organization ID, qualification/certification model, duty roster, or provider feature that is not present in the authoritative schema and services.

## 9. Definition of completion

The Admin website should not be called complete until:

- Every visible page has a defined operator purpose and documented data source.
- Every displayed metric has a definition, scope, timestamp, freshness indicator, and known limitations.
- Every toggle changes a real, supported behavior or is explicitly read-only/unavailable.
- Every privileged operation is authorized by the server and generates appropriate evidence.
- The Admin role does not acquire dispatch/responder capabilities implicitly.
- Sensitive data is protected in views, logs, exports, attachments, and retention workflows.
- Audit and monitoring coverage gaps are visible rather than disguised as zero events.
- Settings distinguish personal preference, organization policy, security policy, and infrastructure configuration.
- Every important help instruction is available to keyboard, touch, and assistive-technology users.
- Responsive, keyboard, screen-reader, zoom, error, empty, loading, and failure states are tested.
- Documentation matches the shipped behavior and does not overstate deployment readiness.

## 10. Research references

1. OWASP, **Application Security Verification Standard 5.0 — V16 Security Logging and Error Handling**: https://github.com/OWASP/ASVS/blob/master/5.0/en/0x25-V16-Security-Logging-and-Error-Handling.md
2. OWASP, **Logging Cheat Sheet**: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
3. OWASP, **API Security Top 10 — 2023**: https://owasp.org/API-Security/editions/2023/en/0x00-header/
4. W3C, **Web Content Accessibility Guidelines (WCAG) 2.2**: https://www.w3.org/TR/WCAG22/
5. National Privacy Commission, **Republic Act No. 10173 — Data Privacy Act of 2012**: https://privacy.gov.ph/Data-Privacy-Act/
6. National Privacy Commission, **Implementing Rules and Regulations of RA 10173**: https://privacy.gov.ph/implementing-rules-regulations-data-privacy-act-2012/
7. NIST, **SP 800-61 Rev. 3 — Incident Response Recommendations and Considerations for Cybersecurity Risk Management** (April 2025): https://csrc.nist.gov/pubs/sp/800/61/r3/final
8. OWASP, **File Upload Cheat Sheet**: https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html
9. OWASP, **Authorization Cheat Sheet**: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html

## 11. Review limitations

- This is a source-level gap assessment, not a penetration test, privacy impact assessment, legal opinion, accessibility conformance report, or production health certification.
- Repository documentation that references older branches or dates is treated as historical evidence and must be revalidated before implementation.
- External standards provide design and verification guidance; they do not prove that TACResQ currently meets those standards.
- No schema, production configuration, provider plan, backup setting, or deployed service was changed during this review.
- No application code or user-facing Admin page was implemented as part of this documentation commit.
