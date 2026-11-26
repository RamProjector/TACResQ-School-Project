---
name: tacresq-admin-ips
description: Research, design, implement, and validate the TACResQ administrator Information Processing System (Admin IPS) website. Use when changing Admin web UI/UX, navigation, page layouts, typography, icons, tables, forms, accessibility, responsive behavior, Admin workflows, or their documentation.
---

# TACResQ Admin IPS skill

## Purpose and source of truth

Use this skill for the **TACResQ Admin IPS website** in `backend/web/`. It governs research, design decisions, implementation, and validation of the administrator-facing information system.

The detailed and authoritative design specification is:

- [TACResQ Admin IPS — Web UI/UX Redesign Master Plan](../../../docs/TACRESQ_UI_FUNCTIONALITY_MASTER_PLAN.md)

Read the complete master plan before substantial design or implementation work. It is the canonical source for the research, repository audits, IDS/IPS coursework requirements, architecture boundaries, page-level requirements, Material 3 findings, responsive strategy, and acceptance criteria. This skill is the concise operating procedure; it does not replace or fork that plan. When the plan is extended, keep this skill's workflow aligned and link to the new sections rather than copying the entire plan here.

Also follow repository-wide instructions in `AGENTS.md`, `CURRENT_TASK.md`, and `docs/AGENT_WORKFLOW.md`. The user's latest explicit instruction takes precedence.

## Activation triggers

Apply this skill when the user asks to:
- redesign, improve, research, or implement the Admin IPS website;
- continue Admin dashboard, navigation, Overview, User Management, Security, Analytics, Activity Log, Data & Continuity, Settings, or Help work;
- refine visual hierarchy, typography, SVG icons, adaptive layouts, tables, forms, dialogs, notifications, or responsive behavior;
- assess whether an Admin feature is supported by the actual backend, RPCs, policies, or database;
- update or consolidate the Admin IPS blueprint, design research, acceptance criteria, or implementation roadmap.

Do not apply it to redesign the Android application unless the user explicitly asks for shared cross-platform research. Keep Android-specific UI documents separate.

## Non-negotiable product boundaries

1. **This is an Admin IPS, not an emergency dispatch command center.** It is for administration and governance: account management, access/security, reports, recorded activity, data continuity, settings, and operator guidance.
2. **Preserve the existing TACResQ website theme.** Keep its current palette, typography families, backgrounds, surfaces, semantic status colors, branding, and theme behavior. The requested redesign changes composition and usability, not the theme.
3. **The desktop sidebar is already collapsible.** It has an expanded state and a compact 76px icon-rail state; mobile uses a separate drawer. Do not treat collapse as unfinished, rebuild it without evidence, or add a second permanent sidebar. Verify the actual current source before changing it.
4. **Do not invent product capabilities.** No speculative modules, controls, metrics, alerts, role hierarchy, tenant model, dispatch powers, or monitoring claims.
5. **Preserve real behavior.** Existing routes, roles, permissions, Admin MFA/AAL2 requirements, RPC/API contracts, data semantics, loading/error/empty/retry states, and working interactions must remain intact unless a separately researched change is requested and reviewed.
6. **UI is not authorization.** Hiding a button or route does not replace server-side authorization, RLS, grants, or RPC checks.
7. Keep TACResQ separate from LifeLink and blood-donor concepts. Do not transfer assumptions or branding between projects.

## Required workflow

### 1. Re-establish facts before acting

- Read `AGENTS.md`, `CURRENT_TASK.md`, and `docs/AGENT_WORKFLOW.md`.
- Read the canonical master plan linked above, especially sections relevant to the task.
- Inspect the current branch, latest `main`, open PRs, exact PR head SHA, CI/workflows, and recent commits.
- Inspect the actual relevant implementation, imported stylesheets, route definitions, tests, RPC calls, and authorization paths. Do not assume old audit notes still describe the latest source.
- Identify which statements are source-verified, which are documented design decisions, and which remain proposed or unverified.

### 2. Define a coherent slice

Before editing, state:
- the observed problem and source evidence;
- intended user/task outcome;
- files and layers affected;
- preserved routes, permissions, contracts, and behavior;
- acceptance criteria and regression coverage.

Prefer one coherent, reviewable slice over broad unrelated cosmetic churn. Continue the existing PR/branch when repository policy and user instructions call for it; do not create duplicate PRs.

### 3. Choose the correct work type

**Update the blueprint first** when:
- evidence is incomplete or conflicting;
- a new design pattern, page requirement, data contract, or security boundary needs research;
- the user requests research/documentation only;
- a proposed feature cannot yet be supported by the current source or backend.

**Implement code** when:
- the relevant requirement is already sufficiently specified;
- the current source confirms the behavior and affected contracts;
- a safe, testable implementation slice is available.

Do not silently turn a documentation/research request into an application change. Do not stop at a plan when the user has authorized implementation and tools permit a safe change.

### 4. Implement and validate

- Preserve theme tokens; use existing design primitives and stylesheet ordering intentionally.
- Trace UI actions to state, route, RPC/API, and authorization boundary as relevant.
- Keep behavior truthful: distinguish loading, empty, unavailable, stale, error, success, and not-collected states.
- Add focused regression tests for layout structure, responsive rules, accessibility, navigation, and retained behavior.
- Run relevant source-contract tests, lint, typecheck, web build, and applicable CI checks.
- Inspect exact-head CI logs when a check fails; diagnose and fix the cause. Never weaken or remove a meaningful test just to obtain a green run.
- Distinguish passed, failed, skipped, and not-run checks. A source test/build is not browser visual QA, live-backend verification, or proof of production readiness.
- Review the complete diff for regressions, secrets, data leakage, unrelated edits, and unauthorized production side effects.

### 5. Record and hand off

- Commit to the appropriate existing task branch, update the existing PR where applicable, and avoid merging unless the repository's required automated checks and merge policy permit it.
- Update `CURRENT_TASK.md` with objective, files/behavior changed, branch/PR/commit, exact validation outcomes, blockers, and one next concrete action.
- Never claim a test, workflow, PR review, merge, or deployment succeeded without exact evidence.

## Design system and interaction rules

### Information architecture

The existing primary Admin destinations are:
- Overview
- User management
- Security
- Analytics & reports
- Activity log
- Data & continuity
- Settings
- Help & about

Verify current route labels and implementation before changing them. Settings has URL-backed subpages; do not collapse separate Security and Settings responsibilities or create duplicate destinations.

### Shell and navigation

- Preserve the existing TACResQ brand and shell.
- Desktop sidebar collapse is an existing feature; the compact state is an icon rail. Mobile navigation is a separate off-canvas drawer.
- Do not add a permanent second navigation pane based only on a screenshot. A contextual pane may be used only when it provides page-specific value and adapts to available width.
- Keep the workspace min-width-safe and let it reclaim available width.
- Use concise page headers and clear active navigation. Collapsed navigation must retain accessible names, tooltips, focus visibility, and keyboard operation.
- Avoid nested scroll traps. Keep the mobile drawer opener reachable outside the closed drawer.

### Page-specific composition

Do not force every page into a generic card dashboard.

| Page | Preferred composition |
|---|---|
| Overview | Compact verified summaries; recent recorded activity as primary content; dependency reachability as supporting content; limited shortcuts |
| User management | Search/filter toolbar, paginated directory table, contextual account details, guarded actions |
| Security | Current administrator assurance/session controls, authenticator safeguards, explicitly scoped security evidence |
| Analytics & reports | Period/timezone controls, verified aggregates, readable charts with data/table equivalents, scoped export |
| Activity log | Filter/search toolbar, dense event table/list, expandable evidence, source-coverage limits |
| Data & continuity | Evidence inventory, dated inspections, honest unavailable/not-connected states |
| Settings | Grouped working preferences; distinguish browser-local settings from server policy |
| Help & about | Searchable concise operator guidance |

For the Overview, Section 34 of the master plan is the source-grounded composition audit: full-width verified metric summaries, an asymmetric activity/dependency workspace when width permits, container-aware stacking, compact shortcuts, and expandable technical caveats. The existing data semantics and coverage limitations must remain visible.

### Typography, surfaces, and copy

- Preserve DM Sans, Space Grotesk, and IBM Plex Mono roles and existing theme tokens.
- Use compact, controlled hierarchy; avoid oversized headings, repeated labels, redundant explanatory paragraphs, and tiny unreadable text.
- Prefer alignment, whitespace, grouping, tables, lists, and dividers over nested cards and decorative outlines.
- Cards are for distinct summary units, not every control or record.
- Keep persistent form labels; placeholders are not labels.
- Retain essential privacy, security, consequence, source, freshness, and coverage explanations. Use progressive disclosure for secondary technical detail, not to conceal critical warnings.
- Keep controls and actions near the content they affect. All controls must have real behavior and truthful feedback.

### Custom SVG icons

- Use the distinctive TACResQ Admin SVG family for domain concepts such as dashboard, users, records, processing, permissions, audit, reports, security, and system health.
- Use consistent geometry, stroke weight, proportions, and optical alignment; prefer `currentColor`, accessible names, and reusable components.
- Use familiar Lucide icons for standard utilities (search, close, refresh, chevron, download, edit) rather than inventing unfamiliar symbols for common actions.
- Avoid emergency siren, rescue, Red Cross, or blood-donation iconography as the Admin identity.
- Do not replace the existing TACResQ app icon/brand mark with a newly invented website logo without a separately approved identity task.

## Adaptive layout and accessibility

- Design against available content width, not only device labels. Use CSS Grid/Flexbox, container queries where supported by project tooling, `minmax(0, 1fr)`, `min-width: 0`, fluid sizing, and content-driven reflow.
- Use the canonical plan's viewport QA matrix and test immediately below/at/above layout transitions, split-screen, orientation, short heights, and 100/125/150/200/400% zoom.
- Never force cramped multi-column tables or panels; stack before text and controls become unusable.
- Preserve keyboard access, visible focus, semantic headings/landmarks, accessible names, labels, error association, status announcements, contrast, touch target usability, and reduced-motion behavior.
- Hover must not be the only way to discover or operate a control.
- Avoid clipping at zoom or with longer user-provided text. Do not solve overflow by shrinking essential content to illegibility.

## Security, data, and IDS/IPS invariants

- Verify each metric, event, status, and action against the current RPC/database/API source before presenting it.
- Keep Supabase Auth/Postgres and Laravel Sanctum/Eloquent identity namespaces distinct unless a trusted mapping is proven. Do not join identities by email or assume role/session synchronization.
- Keep Render/Laravel transport telemetry, direct Supabase operations, provider Auth audit events, domain history, Admin investigation events, and FCM delivery attempts semantically distinct.
- Do not label the current Activity Log as a complete system-wide audit trail while read/search, denied actions, session/IP/device fields, and other sources remain incomplete.
- Preserve Admin AAL2/TOTP checks, protected RPC access, RLS, least privilege, bounded pagination, and server-side enforcement.
- Never fabricate session IDs, IP addresses, device identities, action outcomes, source coverage, delivery receipts, or health states.
- Minimize sensitive data in logs and projections. Do not expose tokens, credentials, MFA secrets, FCM tokens, raw provider payloads, message contents, incident narratives, precise coordinates, or attachment paths in general-purpose audit views.
- Treat notification provider acceptance, device delivery, inbox receipt/read, and user acknowledgement as different outcomes.
- The exact IDS/IPS coursework fields and current source coverage are documented in the canonical plan. Do not rename authoritative columns destructively to force a UI schema; use a compatibility projection only when supported and reviewed.

## Completion checklist

Before calling an Admin IPS change complete, verify all applicable items:

- [ ] Current source, routes, PR head, and CI were inspected.
- [ ] Canonical master plan and repository agent instructions were followed.
- [ ] Admin IPS remains administration/governance, not a dispatcher console.
- [ ] Existing TACResQ theme, branding, and typography families are preserved.
- [ ] Existing collapsible sidebar and mobile drawer are preserved; no unnecessary second sidebar added.
- [ ] Existing routes, role checks, MFA, RPC/API contracts, and data meanings are unchanged or explicitly reviewed.
- [ ] Page composition is task-specific; records remain table/list-first where appropriate.
- [ ] Responsive behavior adapts to actual available width and zoom.
- [ ] Keyboard, focus, labels, semantics, contrast, and reduced motion are addressed.
- [ ] Loading, empty, unavailable, stale, error, retry, and success states remain distinct.
- [ ] Data claims and security/IDS/IPS evidence are source-backed and scoped honestly.
- [ ] Focused regression tests and applicable builds/checks were run.
- [ ] Failures were investigated rather than suppressed.
- [ ] Exact validation outcomes, commit, PR, blockers, and next action were recorded.

## Prohibited assumptions and shortcuts

Never:
- invent additional Admin portal roles or self-service Admin provisioning without a documented separation-of-duties requirement and a complete server-enforced permission model;
- conflate authorization role, account lifecycle status, and operational duty/assignment state;
- replace the existing theme because a reference screenshot uses a different palette;
- rebuild the already-collapsible sidebar without new source evidence;
- add a permanent second sidebar or duplicate navigation;
- copy another product's branding, palette, terminology, or information architecture wholesale;
- redesign Admin as a mobile emergency dispatch or map-first interface;
- invent users, roles, metrics, security detections, operational status, or backend integrations;
- trust client-side visibility or claims for authorization/audit evidence;
- imply complete logging, session tracking, FCM receipt, or end-to-end system health without verified sources;
- make destructive schema changes, widen grants, deploy, or change production configuration without explicit authorization;
- claim tests or visual checks passed when they were not run.

## Reference map

Consult the canonical master plan for detailed evidence and acceptance requirements:
- Sections 1–16: final design direction, repository baseline, theme, typography, custom SVG icons, page standards, responsive strategy, accessibility, performance, security, tests, phases, acceptance.
- Section 17 and following research references: administrative workflows and data-intensive interfaces.
- Section 19: mandatory IDS/IPS coursework criteria.
- Sections 20–31: repository source audits, identity/backend boundaries, Admin RPCs, activity logging, notification delivery, and telemetry limitations.
- Sections 32–35: screenshot-informed layout, sidebar correction, Overview workspace audit, and live database callable/trigger findings.
- Sections 36–38: Material 3 research, data workflow patterns, and Admin IPS information architecture/wayfinding.
- Sections 39–40: visual hierarchy, typography, spacing, information density, Overview operations, evidence freshness, and attention hierarchy.
- Section 41: single Admin-portal role boundary, operational-role separation, account lifecycle UX, safe privileged actions, provisioning boundary, and privacy-safe history.
- Section 42: cross-source correlation, source-qualified identity resolution, timestamp uncertainty, detection prerequisites, and telemetry coverage.
- Section 43: rule lifecycle, deterministic test strategy, false-positive control, severity/confidence/notification separation, alert fatigue, and degraded-source handling.
- Section 44: human-led security investigation lifecycle, triage dispositions, evidence provenance, append-only decisions, containment authority boundaries, and closure/lessons learned.
- Section 45: monitoring assurance, telemetry freshness, explicit source inventory and coverage, independent monitor-of-monitor paths, liveness/readiness separation, alert health, and truthful status presentation.
- Section 46: backup/recovery evidence levels, Supabase beta project clone limitations, independent Auth and Storage recovery, measured RPO/RTO, isolated restore drills, Admin Data & Continuity UX, and Render ephemeral-filesystem boundaries.
- Section 47: dependency-specific degraded modes, safe SOS confirmation states, read-only offline reconciliation, role-specific requester/responder/dispatcher behavior, FCM acceptance versus device delivery, GPS freshness, retry/idempotency policy, and Admin monitoring boundaries.
- Section 49: end-to-end workflow assurance, distinction between interaction/idempotency/trace/request/event IDs, Laravel/Supabase correlation gaps, W3C Trace Context and Supabase correlated-log support, authoritative workflow proof stages, correlation confidence, privacy-safe Admin investigation UX, phased instrumentation, and synthetic workflow acceptance tests.\n- Section 50: synthetic SOS lifecycle verification, separate dispatcher team assignment from field-unit claiming, no invented responder-acceptance event, authoritative evidence per stage, idempotent retry/failure injection, synthetic identities, layered CI/DB/Android/provider testing, and current FCM worker limitation. Detailed procedure: `docs/QA_SYNTHETIC_SOS_LIFECYCLE_VERIFICATION.md`.

If the master plan has additional sections beyond this map, read and apply them as well. The canonical document always wins over this summary when it contains more precise or newer evidence.
