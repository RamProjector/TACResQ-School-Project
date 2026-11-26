# TACResQ Admin IPS — Web UI/UX Redesign Master Plan

**Status:** Canonical design and implementation specification  
**Scope:** TACResQ administrator Information Processing System (Admin IPS) website  
**Theme decision:** Preserve the existing website theme. Redesign the interface, information architecture presentation, layout, typography, icons, components, responsiveness, and interactions without replacing the theme.  
**Repository baseline inspected:** `RamProjector/TACResQ-School-Project`, commit `b877cb3819484755145834f528252d13c04fb01b` (`main`)  
**Document rule:** This file is the single canonical web-admin redesign plan. Keep Android client redesign documents separate.

---

## 1. Final design decision

TACResQ Admin is an **administrative information system**, not a generic emergency dispatch command center.

The website's primary work is administration and governance: account management, access/security, reports, recorded activity, data continuity, preferences, and operator guidance. Emergency and incident data may appear where existing Admin capabilities require it, but the Admin interface must not be redesigned as a dispatcher console or given unverified dispatch powers.

### Non-negotiable decisions

1. **Keep the existing theme.** Preserve the current TACResQ palette, brand identity, backgrounds, surfaces, sidebar treatment, accent colors, semantic status colors, and existing theme behavior. Do not introduce a replacement palette, a new visual theme, or a competing design identity.
2. **Redesign the complete web experience.** This is not a task to delete paragraphs, hide sections, or apply a few cosmetic CSS changes. Rework the shell, every existing Admin workspace, page composition, tables, forms, dialogs, navigation, responsive behavior, and interaction details.
3. **Make the interface concise.** Remove repeated explanations and redundant labels. Keep essential field labels, status meaning, privacy notices, confirmation details, and accessibility names.
4. **Use information hierarchy rather than decoration.** Prefer clear alignment, compact headings, whitespace, tables, useful grouping, and contextual detail over oversized headings, decorative cards, or paragraphs that restate the page title.
5. **Create a distinctive administrative SVG icon family.** Custom SVGs should identify TACResQ Admin concepts. Keep Lucide for common utility actions; do not replace every familiar control with an unfamiliar custom symbol.
6. **Preserve real functionality.** Existing routes, permissions, API/RPC contracts, data semantics, security boundaries, and working features must remain intact unless a separate evidence-backed change is reviewed.
7. **Never invent operational certainty.** Unknown, unavailable, stale, uncollected, or unverified states must remain explicit. A successful dependency probe is not proof of complete system health.

### What this redesign is not

- Not a new theme or color-system redesign.
- Not a marketing/landing page redesign.
- Not a generic emergency dispatch dashboard.
- Not an excuse to add speculative modules or controls.
- Not a backend, database, infrastructure, or authorization rewrite.
- Not a reduction of critical information merely to make screens look empty.

---

## 2. Repository evidence and current baseline

The following observations were verified by reading source and documentation at the baseline commit above. They describe the inspected revision; re-check the current branch before implementation.

| Area | Verified baseline |
|---|---|
| Main Admin page | `backend/web/src/pages/AdminConsole.tsx` |
| Admin stylesheets | `AdminConsole.css`, `PortalVisualFoundation.css`, and `AdminResolution.css`, imported in that order by `AdminConsole.tsx` |
| Brand component | `backend/web/src/components/Brand.tsx`; displays the existing TACResQ app icon and wordmark |
| Global styles/theme tokens | `backend/web/src/index.css` |
| Web package | `backend/web/package.json`; React 19, Vite, TypeScript, Supabase JS and `lucide-react` |
| Existing Admin HCI contract | `tools/test_admin_hci_layout_contract.py` |
| Admin-only contract | `tools/test_web_admin_only_contract.py` |
| Admin page plan | `docs/plans/admin-page-feature-implementation-plan.md` |
| Admin completion/gap research | `docs/research/ADMIN_WEBSITE_COMPLETION_GAP_ASSESSMENT_2026-11-24.md` |
| Operator guide | `docs/admin-portal-operator-guide.md` |
| CI | `.github/workflows/ci.yml` runs the Admin HCI layout/form contract among other repository checks |

### 2.1 Existing Admin navigation

The inspected `AdminConsole.tsx` defines these eight primary sections:

1. Overview
2. User management
3. Security
4. Analytics & reports
5. Activity log
6. Data & continuity
7. Settings
8. Help & about

Settings is URL-backed and currently has General, Accessibility, Notifications, and Privacy subpages. Security is a separate primary section in the inspected code. Do not copy older documentation that describes a different section count or stale labels without reconciling it against the source.

The navigation groups currently communicate Workspace, Governance, Data & Continuity, and Preferences & Support. The final navigation may refine grouping and labels, but must preserve route compatibility and only expose implemented, authorized destinations.

### 2.2 Existing feature boundaries

| Workspace | Existing capability evidenced in source/docs | Redesign boundary |
|---|---|---|
| Overview | Account summaries, recent recorded-activity preview, dependency reachability probes, workspace shortcuts, explicit loading/error states | Make it a concise administrative landing page. Avoid duplicating Analytics aggregates or Activity Log records. Do not call dependency reachability end-to-end readiness. |
| User management | Protected paginated directory, search/role filters, reasoned role changes, account lifecycle history, protected account activity preview | Improve directory scanability and account detail presentation. Preserve server-side checks and reason/audit requirements. Do not add unverified profile fields. |
| Security | Administrator session/AAL/TOTP status and authenticator safeguards | Separate current-session/security controls from complete security monitoring. Do not imply full sign-in history or arbitrary user-session control unless the source supports it. |
| Analytics & reports | Date-bounded aggregate request metrics, status distribution, daily request volume, assignment timing, Manila-time reporting, CSV export | Keep aggregates distinct from event-level investigation. Make period, timezone, units, data freshness, and export scope clear. |
| Activity log | Searchable, filterable, paginated investigation feed with explicit source-coverage limitations | Keep event provenance and limitations visible. Do not label it a complete system-wide audit trail while sources are incomplete. |
| Data & continuity | Backup/recovery and attachment safeguards information with documented limits | Keep evidence and inspection dates explicit. Do not imply a live backup inventory, successful restore, or media browser without verified integration. |
| Settings | Browser-local density/page-size and accessibility preferences; informational guidance where server-backed policy is absent | Distinguish local preferences from global policy. Do not display inert toggles as functional controls. |
| Help & about | Searchable operator guidance | Keep concise, searchable, accurate, and synchronized with implemented behavior. |

### 2.3 Roles and authorization boundaries

- The Admin interface is for authorized administrators; it is not an alternate dispatcher workspace.
- The inspected Admin role-change options cover requester, responder, driver, and dispatcher. Admin provisioning is a separate trusted-operator process and must not become available through ordinary public signup or a casual role dropdown.
- Hiding a button or navigation item is not authorization. Every privileged read and mutation must remain enforced by the backend.
- Do not infer agency/tenant hierarchy, qualifications, certifications, duty rosters, or other data models unless the deployed schema and policies establish them.
- Keep current MFA/AAL2 requirements and protected investigation access intact.

### 2.4 Existing theme and typography

The inspected `index.css` defines the current theme foundation:

- Main interface font: **DM Sans**
- Display font: **Space Grotesk**
- Technical/monospaced font: **IBM Plex Mono**
- Existing palette tokens include `--ink`, `--ink-deep`, `--ink-soft`, `--paper`, `--card`, `--line`, `--ember`, `--ember-dark`, `--sage`, and semantic green/amber/red tokens.
- The Admin shell uses the existing deep navy sidebar, neutral workspace surfaces, white/near-white panels, and existing accent/status colors.
- `Brand.tsx` uses the existing TACResQ app-icon asset and wordmark.

**Theme freeze:** Continue consuming existing tokens. Do not redefine their values as part of this redesign. Do not add a new color palette, gradient system, dark theme, or new brand mark. If an existing token is insufficient for a specific component, document the gap and reuse the closest semantic token until separately approved.

The inspected global stylesheet imports the three fonts from Google Fonts. Treat font loading, fallback behavior, layout shift, and availability as performance concerns; do not change the typeface family as part of the redesign.

### 2.5 Existing layout and sidebar observations

- The Admin shell is a CSS grid with a sidebar and a min-width-safe main workspace.
- The desktop sidebar collapse state is represented by `admin-shell--sidebar-collapsed` and persisted in browser local storage.
- The Admin stylesheet contains a collapsed-grid contract expecting a `76px minmax(0, 1fr)` layout and hides the wordmark/context/navigation labels in the compact state.
- The source-contract test checks that behavior.
- The styles are split across three imported stylesheets. The shell's base grid width is declared more than once (including 238px and 270px variants), with additional responsive overrides. This creates a cascade/order risk for collapsed sizing.
- The desktop collapse control is inside the sidebar header. The mobile drawer opener is in the topbar outside the drawer; this is correct because the opener must remain reachable when the drawer is closed.
- At the mobile/tablet breakpoint, the sidebar becomes a fixed off-canvas drawer with a backdrop, close control, independent scrolling, focus handling, and reduced-motion treatment.

**Required verification:** Do not assume the reported collapse issue is solved merely because the class and source assertion exist. Inspect computed `grid-template-columns`, sidebar bounding width, and workspace bounding box at each desktop width and after resize. If only the sidebar's contents collapse while the grid column remains wide, fix the cascade/layout contract. Preserve the mobile opener outside the closed drawer.

---

## 3. Research findings and product references

The references below are design-pattern references, not templates to copy and not proof that TACResQ has equivalent features.

### 3.1 Product references

| Reference | Pattern worth borrowing | What not to copy |
|---|---|---|
| **Linear** | Quiet navigation, compact controls, restrained icon treatments, precise alignment, keyboard-friendly workflows, reduced decorative noise | Its issue-tracking information architecture or its theme |
| **Stripe Dashboard** | Data-first tables, clear value alignment, restrained surfaces, focused forms, progressive disclosure for detail | Financial workflows or data structures |
| **ArcGIS Mission** | Only where Admin reviews map-related/incident evidence: spatial context can be paired with contextual information rather than a separate decorative map card | A dispatch-first shell or operational capabilities not present in Admin |
| **Motorola CommandCentral** | High-signal status presentation and separating important alerts from routine information | Command-center navigation, wallboard density, or unimplemented dispatch controls |

### 3.2 Human-interface principles

1. **Recognition over recall:** use familiar navigation, predictable action placement, and visible current context.
2. **Progressive disclosure:** show the fields and actions needed for the current task; place technical detail, audit payloads, and explanatory material behind expandable details.
3. **Consistency:** the same control type should behave and look consistently across Admin pages.
4. **Task-oriented hierarchy:** the page should answer one primary question, such as “Which account needs review?” or “What recorded event occurred?”
5. **Information density without crowding:** prefer compact, aligned information over excessive cards or tiny unreadable text.
6. **Feedback and reversibility:** show whether an action is pending, saved, failed, or unavailable; confirm consequential actions and preserve recoverable form state.
7. **Honest system state:** distinguish healthy, degraded, stale, unknown, not collected, and unavailable.

### 3.3 Admin IPS-specific design conclusion

Use Linear and Stripe as the stronger references for the overall Admin IPS experience. Use GIS/emergency software references only for specific data views where the Admin portal actually needs geographic or incident context. The Admin website should remain an administration and information-processing workspace.

---

## 4. Final visual and interaction direction (existing theme retained)

### 4.1 Application shell

- Keep the existing TACResQ identity and palette.
- Build a stable desktop shell: sidebar, compact topbar, and main workspace.
- The sidebar must have a real expanded and collapsed width; the workspace must reclaim the released width.
- Keep navigation labels available through accessible names and tooltips in the collapsed state.
- Keep the collapse control reachable and visually aligned inside the desktop sidebar header.
- Keep the mobile drawer opener in the topbar, outside the closed drawer.
- Move sign-out into the existing account/profile menu only if a safe, discoverable account menu is implemented; do not simply hide sign-out.
- Keep breadcrumbs short and useful. Avoid repeating the same page name in a large eyebrow, title, subtitle, and card heading.
- Use a consistent page-header pattern: concise title, optional one-line context only when it adds meaning, and page actions aligned to the right.

### 4.2 Page composition

Avoid using the same dashboard template for every page.

| Page | Primary composition |
|---|---|
| Overview | Compact attention/summary area, recent recorded activity, limited shortcuts, dependency reachability with a precise scope label |
| User management | Search/filter toolbar, paginated directory table, contextual account details, guarded actions |
| Security | Current administrator session and authenticator status, grouped security controls, clear capability/coverage limits |
| Analytics & reports | Period/timezone controls, key aggregates, readable chart, supporting data table, scoped export |
| Activity log | Search/filter toolbar, event table/list, expandable event details, visible source coverage |
| Data & continuity | Evidence inventory and dated inspection context, clear unavailable/not-connected states, documented operator guidance |
| Settings | Short grouped preference controls with save/reset behavior; separate local preferences from read-only policy guidance |
| Help & about | Search-first reference, concise answers, version/support details only when grounded in configuration |

### 4.3 Cards, surfaces, and borders

- Do not place every element in a card.
- Use tables for records, lists for sequential activity, simple grouped sections for settings, and panels only when content is a distinct unit.
- Avoid nested cards inside cards.
- Use the existing border and surface tokens; reduce redundant outlines and decorative shadows.
- Use dividers to separate related rows rather than drawing a box around every row.
- Let whitespace and alignment create hierarchy.
- Keep action buttons near the content they affect.
- Keep dangerous actions visually distinct and require confirmation appropriate to their consequence.

### 4.4 Copy and labels

- Remove paragraphs that repeat the page title or explain an obvious control.
- Keep descriptions to one short line when a page needs context.
- Do not repeat the same status in a badge, heading, paragraph, and metadata line.
- Keep persistent form labels; placeholder text is not a replacement for a label.
- Keep critical security, privacy, data freshness, and consequence explanations even when they require more text.
- Prefer direct action names: “Export CSV”, “Refresh”, “Change role”, “View details”.
- Avoid unexplained abbreviations and excessive uppercase/letter spacing.
- Put technical definitions and source-coverage details in expandable help or details sections, not in the primary reading path.

---

## 5. Typography specification

Keep DM Sans, Space Grotesk, and IBM Plex Mono. Change their roles and hierarchy, not the font families.

| Role | Recommended size | Weight/use |
|---|---:|---|
| Page title | 22–26px | 600; Space Grotesk or existing display token |
| Section heading | 15–17px | 600; short and task-specific |
| Panel/table group heading | 14–16px | 600 |
| Body and table content | 13–14px | 400–500; DM Sans |
| Form label | 12–13px | 500–600; persistent |
| Supporting text | 12–13px | 400; only when useful |
| Metadata | 11–12px | 400–500; secondary contrast |
| IDs, timestamps, technical values | 11–12px | IBM Plex Mono; tabular numerals where useful |
| Icon-only tooltip | 12px minimum where feasible | concise action description |

### Typography rules

- Avoid oversized page titles. The current responsive title token reaches 40px; reduce the Admin page-title hierarchy toward the range above.
- Use fluid sizing only where it improves fit; never let viewport width inflate an administrative page title unnecessarily.
- Keep body copy comfortable and readable. Do not shrink table text to fit more columns.
- Use tabular numerals for counts, IDs, dates, and measurements where alignment helps scanning.
- Keep line length controlled for explanatory copy; avoid long full-width paragraphs.
- Use consistent line-height and avoid headings that wrap into awkward single-word lines.
- Respect browser zoom and user text scaling.
- Use font fallbacks that preserve layout if Google Fonts cannot load. Assess self-hosting only after license, build, and performance review; it is not a requirement to add a new font.

---

## 6. Custom TACResQ Admin SVG icon system

A unique SVG family is required, but it must represent **administrative information-system concepts**, not turn the site into an emergency-response icon showcase.

### 6.1 Icon families

Create original TACResQ-specific SVGs for these Admin concepts:

| Concept | Visual direction |
|---|---|
| Overview | Structured dashboard/grid with one emphasized information point |
| User management | Account/profile with a controlled directory or grouping motif |
| Security | Layered access/verification motif; distinct from generic emergency shield imagery |
| Analytics & reports | Measured data/chart motif with a recognizable report structure |
| Activity log | Ordered event/timeline or recorded-entry motif |
| Data & continuity | Structured data/archive with a continuity/restore cue; do not imply a backup is verified |
| Settings | Deliberate control/adjustment motif |
| Help & about | Knowledge/reference motif |

Custom icons should be recognizable at small sizes and should not depend on color alone.

### 6.2 Technical standards

- Use a consistent 24×24 viewBox for standard UI icons; use 32×32 or 48×48 for larger empty-state or feature illustrations.
- Keep stroke weight consistent (approximately 1.75–2px at 24px scale), with coherent caps, joins, corner radii, and optical alignment.
- Use `currentColor` so SVGs inherit the existing theme.
- Keep fills and strokes simple; avoid filters, gradients, tiny details, and excessive path complexity.
- Include outline and selected/emphasized variants only where a state genuinely changes.
- Decorative SVGs must be hidden from assistive technology. Meaningful icon-only controls need accessible names and visible focus.
- Preserve adequate target size around icons; the icon itself is not the interactive target.
- Optimize SVG output and avoid shipping a large icon font for a small set of symbols.

### 6.3 Use Lucide appropriately

Keep `lucide-react` for familiar utility actions such as search, close, refresh, edit, download, sort, pagination, and menu controls. Use the custom library for the TACResQ Admin identity and the eight primary administrative concepts. Do not replace familiar utility icons merely to make every icon different.

---

## 7. Component and page standards

### 7.1 Tables and directories

- Tables are primary interfaces for account directories, activity records, and report detail.
- Use clear column names, aligned numeric values, stable row heights, and subtle row separators.
- Keep search, filters, page size, result count, and pagination in a compact toolbar.
- Avoid forcing a wide table to shrink into unreadable text. Use a local horizontal scroll region or a carefully designed compact row/detail presentation.
- Keep important identity fields together; place secondary technical identifiers in lower-emphasis text.
- Make row actions keyboard accessible and give repeated actions names that identify the affected record.
- Preserve sorting/filter state where existing behavior supports it.
- Use explicit empty, loading, unavailable, and error states; do not show stale privileged data after authorization failure.

### 7.2 Forms and settings

- Prefer single-column forms; use two columns only for short, related fields when there is sufficient space.
- Keep persistent labels above controls.
- Place short hints and validation feedback next to the relevant field.
- Group related settings and clearly distinguish editable local preferences from read-only policy information.
- Explain whether a setting is browser-local or server-backed.
- Provide save/reset feedback and protect unsaved changes when leaving a form.
- Do not render inert toggles for unimplemented notification, privacy, security, or system policies.

### 7.3 Dialogs and confirmations

- Keep dialogs focused on one decision.
- State the affected account/record and consequence.
- Keep primary and destructive actions clearly differentiated.
- Fit within short viewport heights using internal scrolling where necessary.
- Preserve focus, return focus to the trigger, support Escape where safe, and prevent background interaction when modal.
- Do not put long policy paragraphs into every confirmation; link to the relevant details when appropriate.

### 7.4 Status, notifications, and feedback

- Pair status color with text or a meaningful icon; never rely on color alone.
- Distinguish success, warning, error, stale, unknown, and unavailable.
- Show freshness timestamps only when they belong to the currently displayed result set.
- Keep toast messages concise and non-blocking; persistent errors need an actionable recovery path.
- Never show “live”, “healthy”, “complete”, “delivered”, “backed up”, or “restorable” unless the source proves that exact claim.

---

## 8. Responsive and adaptive layout strategy

The design must respond to **available space**, not just device labels. Do not create one media query for every test resolution. Use content-driven breakpoints, fluid sizing, CSS Grid/Flexbox, container queries, and component-level adaptation.

### 8.1 Test viewport width matrix

Use these widths as a broad QA matrix, not as 31 required CSS breakpoints:

`320, 360, 375, 390, 414, 430, 480, 540, 600, 640, 720, 768, 800, 834, 900, 960, 1024, 1080, 1152, 1200, 1280, 1366, 1440, 1536, 1600, 1680, 1728, 1920, 2048, 2560, 3440 px`

### 8.2 Test viewport height matrix

`568, 640, 720, 768, 800, 900, 1080, 1440 px`

Test representative width/height combinations, especially narrow + short, medium + short, and ultrawide + tall. Do not exhaustively run every Cartesian combination on every commit.

### 8.3 Required adaptive behaviors

| Available space | Expected behavior |
|---|---|
| Wide desktop | Expanded sidebar, comfortable workspace, optional contextual detail column |
| Standard desktop | Expanded sidebar and content-width-aware page layout |
| Narrow desktop/laptop | Compact or collapsed sidebar when needed; toolbar wraps without overlap |
| Tablet landscape | Drawer or compact navigation; tables and filters reorganize |
| Tablet portrait | Single workspace column; panels stack; navigation remains reachable |
| Narrow/mobile browser | Drawer navigation, compact topbar, focused single-column task flow |
| Short viewport | Header and navigation remain usable; long panels/dialogs scroll internally |
| Split-screen/resized window | Components adapt to their container instead of assuming full-screen width |
| 200–400% zoom | Content reflows without clipped controls or page-level horizontal scrolling for ordinary content |

### 8.4 Browser and environment variations

Include:
- Portrait and landscape.
- Mouse, keyboard, and touch.
- 100%, 125%, 150%, 200%, and 400% browser zoom.
- Split-screen and narrow browser windows.
- Browser chrome reducing available height.
- High-DPI/device pixel ratio variations.
- Font loading delay/failure.
- Reduced motion and forced-colors/high-contrast modes.
- Resize while menus, dialogs, expanded rows, or filters are open.

### 8.5 Implementation rules

- Use page-level media queries only when the overall composition must change.
- Use container queries when an individual component should adapt to its own available width.
- Use `minmax(0, 1fr)`, `min-width: 0`, and bounded widths to prevent grid/flex overflow.
- Use `clamp()` for fluid spacing and restrained type sizing, with readable minimums.
- Use dynamic viewport units such as `dvh` where supported and safe fallbacks for viewport-height layouts.
- Keep horizontal scrolling local to genuinely wide data tables; avoid page-wide overflow.
- Keep modal and dropdown positioning within the visible viewport.
- Preserve focus and selected state when a layout changes from sidebar to drawer.
- Keep navigation usable with a keyboard and touch, including visible focus and adequate target dimensions.

### 8.6 Existing responsive implementation to consolidate

The inspected stylesheets contain overlapping breakpoints and shell overrides, including 1920px+, 1440px, 1201–1439px, 901–1200px, 761–900px, max 760px, max 480px, max 900px, max 1120px, max 960px, max 700px, and max 520px rules across different files.

Do not add another layer of overrides. First map all existing selectors and their cascade. Consolidate the Admin shell and component responsive rules into a clear, documented system while preserving the existing theme and behavior.

---

## 9. Accessibility and usability requirements

Target WCAG 2.2 AA as an engineering goal, but do not claim conformance without a complete audit.

- Text contrast: at least 4.5:1 for normal text and 3:1 for large text.
- Visible keyboard focus for every interactive element.
- Keyboard access to navigation, tables, menus, dialogs, filters, and pagination.
- Logical focus order and appropriate focus restoration.
- Semantic headings, landmarks, labels, table headers, status announcements, and form error associations.
- Accessible names for icon-only controls; decorative SVGs hidden from screen readers.
- Do not use color as the only signal for status, errors, or selection.
- Reflow at narrow equivalent widths and browser zoom; no loss of information or functionality.
- Touch targets should be comfortably usable; aim for approximately 40–44px where practical, while meeting applicable WCAG target-size requirements.
- Respect reduced-motion preferences; avoid non-essential animation.
- Test forced-colors/high-contrast mode.
- Keep error messages specific and actionable without exposing sensitive implementation details.
- Provide clear empty and unavailable states; do not rely on a blank panel to communicate absence of data.

---

## 10. Performance and implementation quality

- Preserve the current font families; optimize loading/fallback behavior and avoid layout shift.
- Avoid introducing new UI libraries or dependencies unless the repository review proves they are needed.
- Use the existing Lucide dependency and a small optimized custom SVG library.
- Avoid heavy blur, large shadow stacks, expensive animated backgrounds, and unnecessary motion.
- Keep tables and charts responsive; consider virtualization only if measured record volume justifies it.
- Avoid rendering large hidden page trees if it causes measurable performance or accessibility problems.
- Ensure CSS ownership is clear: reduce duplicate selectors and stylesheet-order dependencies.
- Keep components reusable where behavior is genuinely shared; do not over-generalize different workflows into one complex component.
- No console errors, broken links, inaccessible controls, or inert buttons in the redesigned pages.

---

## 11. Security, privacy, and truthful data presentation

The Admin UI is a privileged surface.

1. Backend authorization remains authoritative. UI visibility is never a substitute for RLS, RPC authorization, or server-side role checks.
2. Keep privileged data out of local storage. Browser-local storage may hold non-sensitive presentation preferences only.
3. Do not expose service-role credentials, access tokens, MFA secrets, recovery codes, private attachment bytes, or sensitive incident details unnecessarily.
4. Preserve MFA/AAL2 requirements for protected investigations and security-sensitive actions.
5. Keep role changes reasoned, auditable, and limited to server-authorized roles.
6. Do not expose Admin provisioning through public signup or ordinary role controls.
7. Keep CSV export scoped and protected against spreadsheet formula injection.
8. Keep audit source coverage visible. Missing events must not be interpreted as proof that no event occurred.
9. Do not represent dependency reachability as full application health or emergency readiness.
10. Do not add account suspension, deletion, restore, notification suppression, retention, or global policy controls until the underlying authorization, audit, recovery, and enforcement behavior is verified.
11. Do not invent privacy controller details, legal basis, retention periods, data-subject rights processing, or vendor disclosures.
12. Use synthetic accounts and synthetic records for QA; never use real emergency incidents or personal location data in test fixtures.

---

## 12. Testing and verification plan

### 12.1 Existing checks

At the inspected baseline:
- `tools/test_admin_hci_layout_contract.py` protects key navigation, sidebar, mobile drawer, focus, and form contracts.
- `tools/test_web_admin_only_contract.py` protects Admin-only frontend boundaries.
- `.github/workflows/ci.yml` runs the Admin HCI contract.
- `backend/web/package.json` exposes `build` (TypeScript check + Vite build), `dev`, and `preview`; it does not define a dedicated frontend test or viewport-matrix script.
- `tools/visual_regression.py` uses Playwright to capture the interactive mobile wireframe gallery at a fixed 1440×1080 browser viewport. It is not a live AdminConsole viewport suite; map views are excluded due to nondeterministic external tiles.

Re-check current scripts and workflows before relying on these statements.

### 12.2 Required checks for the redesign

| Check | Minimum expectation |
|---|---|
| TypeScript/web build | `pnpm --dir backend/web build` or the repository's current equivalent |
| Admin HCI contract | Run existing `tools/test_admin_hci_layout_contract.py` |
| Admin-only contract | Run existing `tools/test_web_admin_only_contract.py` |
| Relevant security/source contracts | Run the Admin/auth/audit/security tests affected by the change |
| Navigation | Every Admin section and legacy URL works; browser Back/Forward and keyboard navigation remain correct |
| Data behavior | Loading, empty, error, stale, unavailable, permission denied, and successful states are accurate |
| Sidebar | Verify actual sidebar width and workspace expansion when collapsed at multiple desktop widths |
| Responsive | Run the broad width/height matrix, including just below/at/above each chosen content-driven breakpoint |
| Accessibility | Keyboard-only, focus order, screen-reader names, contrast, zoom/reflow, reduced motion, forced colors |
| Visual review | Capture real Admin pages, not only wireframes; review every section and responsive state |
| Regression | Confirm routes, roles, permissions, APIs, filters, forms, exports, dialogs, and account workflows remain intact |

### 12.3 Viewport test execution strategy

- Pull request smoke suite: representative compact, standard, laptop, tablet, and mobile viewports, plus short-height and zoom checks for changed components.
- Major shell/layout change: full width matrix and representative height combinations.
- Release candidate: full matrix plus manual keyboard, touch, zoom, and assistive-technology review.
- Capture screenshots for each Admin section and key states: loading, populated, empty, error, expanded detail, open menu/dialog, collapsed sidebar, and mobile drawer.
- Test immediately below, at, and above each actual breakpoint.
- Keep screenshot differences report-only only where repository policy says so; navigation, functionality, accessibility, build, and security checks remain blocking.

---

## 13. Implementation phases

### Phase 0 — Baseline and inventory
- Record the current main SHA, open PRs, CI state, routes, section permissions, and backend dependencies.
- Capture screenshots and inspect every Admin section at representative widths.
- Inventory all existing selectors and stylesheet load order.
- Verify the sidebar collapse width using computed browser layout.
- Record existing defects separately from redesign regressions.

**Exit:** source inventory, screenshots, baseline test results, and a list of confirmed versus suspected issues.

### Phase 1 — Design foundation (theme preserved)
- Define spacing, typography roles, border/radius/elevation usage, focus styles, and component density using existing theme tokens.
- Define the custom TACResQ Admin SVG family.
- Define shared patterns for page headers, tables, filters, forms, status indicators, empty/error states, and dialogs.
- Do not change theme token values.

**Exit:** documented design tokens/roles, icon assets/components, and reviewed component examples.

### Phase 2 — Application shell and sidebar
- Consolidate the shell CSS and responsive rules.
- Fix the real desktop collapsed-column behavior.
- Preserve mobile drawer opener/closer placement and accessibility.
- Standardize topbar, breadcrumbs, profile/account menu, and workspace width.
- Verify short-height, zoom, keyboard, and resizing behavior.

**Exit:** sidebar physically shrinks, workspace expands, no overlap/overflow, drawer remains operable.

### Phase 3 — Page-by-page Admin redesign
Recommended order:
1. Overview
2. User management
3. Security
4. Analytics & reports
5. Activity log
6. Data & continuity
7. Settings
8. Help & about

For each page: improve information hierarchy, remove redundant copy, redesign its primary data view, standardize controls, and verify all states and role boundaries.

**Exit:** all existing Admin sections have a consistent but purpose-specific layout; no feature is silently removed.

### Phase 4 — Responsive and accessibility hardening
- Validate the viewport matrix and zoom levels.
- Fix overflow, short-height dialogs, nested scrolling, focus loss, and touch target issues.
- Verify contrast and reduced-motion/forced-color behavior.

**Exit:** responsive and accessibility evidence is attached to the implementation review.

### Phase 5 — Final regression and release review
- Run web build, Admin contracts, relevant security tests, and full repository CI.
- Review actual rendered pages at all major viewport families.
- Verify backend/API behavior and role isolation have not changed.
- Document any manual checks that remain outstanding.

**Exit:** exact-head checks pass, no unresolved blocking issues, and manual acceptance gaps are stated honestly.

---

## 14. Acceptance criteria

The redesign is ready for final review only when all applicable criteria are met:

- [ ] The existing TACResQ website theme and brand are preserved.
- [ ] The product remains clearly an Admin IPS website, not a dispatch dashboard.
- [ ] Every existing Admin section is redesigned and remains reachable.
- [ ] No existing feature, route, permission, or backend contract is removed or silently changed.
- [ ] The sidebar's desktop collapse changes the actual grid column and gives the width back to the workspace.
- [ ] The mobile drawer opener remains reachable outside the closed drawer.
- [ ] Typography is compact, readable, consistent, and not dominated by oversized page titles.
- [ ] Repeated paragraphs, redundant labels, duplicate cards, and decorative noise are removed without hiding essential meaning.
- [ ] Original TACResQ Admin SVG icons are used for core administrative concepts; Lucide remains for familiar utility actions.
- [ ] Tables, filters, forms, dialogs, status indicators, and feedback patterns are consistent.
- [ ] All states are truthful: loading, empty, stale, unavailable, error, success, and permission denied.
- [ ] Responsive layouts work across representative width/height combinations, split-screen, orientation, and zoom.
- [ ] Keyboard navigation, focus, screen-reader names, contrast, reduced motion, and reflow are reviewed.
- [ ] No new client-only security controls or unsupported operational claims are introduced.
- [ ] Build, Admin contracts, security checks, and required CI pass on the exact reviewed commit.
- [ ] Browser/device acceptance is recorded separately from automated source tests.

---

## 15. Research references

Use these as supporting guidance. Re-check current versions and applicability during implementation.

### Product/interface references
- [Linear — How we redesigned the UI](https://linear.app/now/how-we-redesigned-the-ui) — restraint, hierarchy, consistency, and reducing unnecessary interface decoration.
- [Esri — ArcGIS Mission](https://www.esri.com/en-us/arcgis/products/arcgis-mission/overview) — contextual spatial information, relevant only to Admin pages that genuinely need map/incident context.
- [Motorola Solutions — CommandCentral](https://www.motorolasolutions.com/en_us/products/command-center-software.html) — high-signal status and alert presentation; not an Admin IPS information-architecture template.

### Human-computer interaction and accessibility
- [Nielsen Norman Group — Recognition vs. recall](https://www.nngroup.com/articles/recognition-and-recall/) — make actions and context recognizable rather than requiring users to remember hidden conventions.
- [Nielsen Norman Group — Progressive disclosure](https://www.nngroup.com/articles/progressive-disclosure/) — reveal secondary detail when needed without hiding critical information.
- [W3C — WCAG 2.2](https://www.w3.org/TR/WCAG22/) — accessibility success criteria.
- [W3C — Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html) — responsive reflow and avoiding two-dimensional scrolling for ordinary content.
- [W3C — Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) — interactive target-size requirements.
- [W3C — Decorative Images](https://www.w3.org/WAI/tutorials/images/decorative/) — accessible treatment of decorative graphics.
- [W3C — SVG 2](https://www.w3.org/TR/SVG2/) — SVG structure and semantics.

### Typography, icons, and responsive implementation
- [Material Design — Typography](https://m3.material.io/styles/typography/overview) — type roles and hierarchy.
- [Material Design — Icons](https://m3.material.io/styles/icons/overview) — consistent icon use and sizing.
- [Lucide — Icon design](https://lucide.dev/guide/design/icon-design) — coherent icon geometry.
- [Lucide — Accessibility](https://lucide.dev/guide/advanced/accessibility) — accessible icon implementation.
- [MDN — Responsive design](https://developer.mozilla.org/en-US/docs/Learn_web_development/Core/CSS_layout/Responsive_Design) — responsive layout foundations.
- [MDN — Container queries](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_containment/Container_queries) — component adaptation based on available space.
- [MDN — clamp()](https://developer.mozilla.org/en-US/docs/Web/CSS/clamp) — bounded fluid sizing.
- [Playwright — Emulation](https://playwright.dev/docs/emulation) — viewport/device emulation for automated verification.

### Security and emergency-context guidance
- [OWASP Application Security Verification Standard](https://owasp.org/www-project-application-security-verification-standard/) — application security verification baseline for privileged workflows.
- [ITU-T F.760.2](https://www.itu.int/rec/T-REC-F.760.2/en) — first-responder interface guidance; use only for relevant readability, feedback, and high-stress information presentation, not as the Admin IPS page architecture.

---

## 16. Maintenance rule

Update this file when the Admin navigation, page capabilities, theme tokens, icon system, responsive architecture, or acceptance gates materially change. Keep source-backed facts distinct from recommendations. If another document covers a narrow topic (for example, security-event coverage or the operator guide), link to it rather than duplicating its entire content here.

**This plan is the final design direction:** complete TACResQ Admin IPS web UI/UX redesign, existing theme preserved, concise professional interface, distinctive administrative SVG icons, real responsive behavior, and all current authorized functionality retained.
