# TACResQ | Admin Portal — Security Page Audit & Improvement Plan

## Executive Summary

The current Security page (adminPage=security) communicates technical posture, but the page must distinguish verified controls from planned work. Account recovery is a security prerequisite, not a convenience action: the local Laravel Sanctum Admin TOTP factor currently has no supported recovery workflow.

## 1. Critical UX & Logic Contradictions

### A. Sole-factor lockout protection

- The sole verified TOTP factor is visibly locked and the page warns Admins to enroll a backup factor.
- Secondary-factor removal requires fresh TOTP verification.
- The UI must never imply that an unsupported recovery or removal action is available.

### B. Duplicate factor & session actions

- Consolidate duplicate authenticator-factor and global-session controls into single canonical sections.
- Do not mark this complete until a full-page review verifies all rendered states.

## 2. Account Lockout & Recovery Resilience

### A. Current state

The Supabase Auth Security page and the separate local Laravel Sanctum Admin domain have distinct MFA implementations. Do not assume that backup-factor UI in Supabase provides recovery for a local Sanctum Admin account.

The local Sanctum TOTP implementation has encrypted secret storage, a ten-minute pending enrollment, explicit confirmation, and replay-resistant step-up verification. It does not have recovery codes, factor reset, or an independently verified personnel identity registry.

### B. Required behavior

- Keep the sole-factor lockout warning visible.
- Do not add self-service factor disable/reset or a universal bypass.
- Treat a lost authenticator as an unavailable Admin capability until a separately reviewed recovery workflow exists.
- Require pre-established identity evidence, two independent approvers, tamper-resistant audit, atomic old-factor/session invalidation, and confirmed new-factor enrollment before any future recovery implementation.
- Never recover a local Sanctum identity by matching its email to a Supabase account.

The fail-closed handling policy and implementation gates are documented in docs/security/local-admin-authenticator-recovery-boundary.md. This is a policy/runbook only; it does not create a recovery endpoint or authorize production database maintenance.

## 3. Information Architecture & Copy Hierarchy

Separate live operational controls from deployment/migration diagnostics. Show only environment-scoped and verified status; do not present branch-local implementation as production enforcement.

## 4. Visual & Layout Polish

- Remove redundant punctuation from the Security heading.
- Improve administrator-name wrapping or provide an accessible full-name label.
- Prevent fixed footer banners from creating orphan print pages.

## 5. Prioritized Implementation Checklist

- [x] P0: Sole verified TOTP factor is visibly locked and the backup-authenticator warning is prominent.
- [x] P0: Local Sanctum authenticator recovery limitation is documented as a fail-closed policy; no unsafe reset/bypass is exposed.
- [ ] P0: Implement an independently verifiable, two-person local Admin recovery workflow with immutable audit and disposable-PostgreSQL concurrency tests.
- [x] P0: Full-page review confirms one canonical verified-factor list, one global-session revocation action, and one backup-enrollment action; convert each action group into a separately labelled semantic section.
- [ ] P1: Surface the verified access-token expiry window in the global sign-out confirmation.
- [x] P1: Separate live Admin MFA/session controls from the branch-only database implementation inventory and explicit environment-verification caveat.
- [x] P2: Remove the redundant Security-page heading punctuation, expose the full administrator name through an accessible label/title despite compact truncation, and add print rules that suppress navigation chrome and avoid splitting key Admin panels.

## Follow-up findings
- The live MFA panel has one authenticator inventory, one global sign-out control, and one backup-enrollment action. These are now distinct, labelled semantic sections for assistive technology; the backup action is not duplicated in the lockout warning.


- The Security page now labels the live MFA/session area separately from the branch implementation inventory. The latter explicitly states that migration presence in this branch does not establish deployment or production enforcement; environment verification remains unavailable from this repository-only change.
- The global sign-out confirmation continues to warn that already-issued access tokens can remain valid until expiry. No numeric expiry window is displayed because the effective Supabase JWT lifetime for the target project has not been independently verified; do not infer it from a local default or assume the project configuration.

## Implementation Boundary

The sole-factor warning and contradictory Remove control are implemented. The local Sanctum recovery runbook is documentation only and deliberately leaves recovery unavailable. No recovery endpoint, recovery codes, production factor reset, or privileged Sanctum suspension endpoint is implemented. Production MFA, session revocation, token TTL, and backend enforcement must not be claimed without environment-specific verification. Keep PR #347 draft until the recovery design, callable/Storage/bridge coverage, disposable-PostgreSQL concurrency tests, and exact-head CI validation are complete.


## Admin audit-history usability follow-up

- Account suspension/reactivation history now uses bounded server-side pages of five records instead of silently showing only the newest five.
- The lifecycle section exposes independent loading and error states and a compact Previous/Next pager; it does not imply that an unavailable reader contains zero events.
- If records are removed between page requests and the selected page becomes invalid, the UI returns to the last valid page.
- The ordinary investigation feed retains its existing independent filters and pagination. The lifecycle history remains a separate read-only source until a unified cross-source cursor and ordering contract is validated.
- The pager is keyboard-focus visible and stacks its controls on narrow screens.


## Admin navigation and directory accessibility follow-up

- Added a keyboard skip link that moves focus to the Admin workspace without requiring traversal of every sidebar item.
- The user-management section now has an explicit heading relationship; the directory table references its live result-status text and uses scoped column headers.
- User-directory and investigation-feed pagination now use semantic navigation landmarks and identify the result region they control.
- Existing role permissions, read-only audit behavior, and server-side filtering are unchanged. These are navigation and assistive-technology improvements, not new administrative capabilities.


## Admin account-directory responsive usability follow-up

- Search and role-filter controls now programmatically identify the account table they affect and reference its live result-status description.
- The filter toolbar wraps on narrow screens; the search field, role selector, and reset action remain usable without squeezing into one row.
- The account table keeps a readable minimum column width inside its existing horizontal-scroll region. Long account names and email addresses wrap within the account cell.
- A short mobile-only hint explains horizontal table navigation, and the scroll region has a visible keyboard-focus indicator.
- These changes preserve server-side filtering, the existing 25-row page size, current role-management permissions, and the existing read-only lifecycle boundary. No account status or suspension action was introduced.
- Static source-contract assertions cover the filter relationships, mobile guidance, table width, wrapping, and keyboard focus. Browser layout and assistive-technology testing remain outstanding.


## Account-directory search consistency follow-up

- Search input is normalized with trim() before the server-side request, avoiding separate queries for leading/trailing whitespace.
- While a debounced search is pending, the directory suppresses rows from the previous query and displays an explicit in-table progress state. Role-change actions remain unavailable during this interval.
- The regression contract now checks the pending-search presentation and the existing last-valid-page recovery when the account count shrinks.
- The server remains authoritative for search, role filtering, and pagination. No client-side filtering, new account data exposure, or account lifecycle controls were added.
- Static source assertions are present; run the repository's Python contract and browser tests in CI before treating the change as validated.


## Admin portal design-system and responsive-layout follow-up

- Added a shared spacing and typography scale for the Admin portal, with consistent DM Sans body typography and constrained heading hierarchy.
- Standardized workspace width, page gutters, panel spacing, control heights, table cell rhythm, and modal padding so the Overview, User Management, Security, Reports, and Activity Log sections share the same visual system.
- Defined explicit layout tiers for wide desktop, standard desktop, compact desktop/tablet, mobile, and narrow mobile. The layout remains fluid within each tier but changes structure at predictable breakpoints.
- Typography uses bounded responsive sizing rather than unrestricted viewport scaling; large displays receive modestly wider content and table spacing, while narrow screens retain legible controls and stacked pagination.
- Existing navigation, permissions, data queries, account lifecycle boundaries, and page content are unchanged.
- Static source assertions are added for the shared tokens and breakpoint contract. Actual browser screenshots, keyboard interaction, zoom behavior, and assistive-technology review remain required before declaring visual validation complete.


## Admin multi-page resolution and spacing implementation

- Added a dedicated Admin resolution stylesheet loaded after the existing portal foundation, so the Overview, User Management, Security, Reports, and Activity Log views share one bounded typography and spacing system.
- Defined explicit wide desktop (1920px+), large desktop (1440–1919px), standard desktop (1201–1439px), compact desktop (901–1200px), tablet (761–900px), mobile (up to 760px), and narrow mobile (up to 480px) layout tiers. Sizing remains fluid inside each tier, while structural changes happen at fixed breakpoints.
- Standardized body/control typography, heading scale, workspace gutters, panel rhythm, table density, and form-control sizing. Large screens gain restrained content width and table spacing instead of unbounded font growth.
- Reworked the mobile Admin sidebar into a horizontally scrollable navigation strip and reduced secondary chrome on narrow viewports so the active workspace retains usable width.
- Applied a shared responsive layout to audit coverage, lifecycle history, investigation filters, event cards, analytics metrics, and health panels. Filters and cards reflow into fewer columns as available width decreases; tables retain a readable minimum width inside their scroll region.
- Added bounded modal height/scroll behavior, consistent keyboard focus visibility, and reduced-motion handling.
- Added static source-contract assertions for stylesheet loading, each resolution tier, audit/report reflow, table minimum width, modal bounds, and reduced-motion behavior.
- No application data, queries, role permissions, routes, account lifecycle capabilities, or backend behavior changed.
- Static source assertions do not establish visual correctness. Browser screenshots at representative fixed widths (1920, 1440, 1280, 1024, 768, 390px), 200% zoom, keyboard-only navigation, and screen-reader review remain outstanding.


## Admin layout and interaction refinement follow-up

- Refined the Admin header and navigation at intermediate desktop/tablet widths so breadcrumb labels and administrator names truncate safely instead of forcing horizontal page overflow. Mobile header content can wrap without hiding the active section or sign-out action.
- Expanded audit event details into a readable definition-list grid, with long technical references wrapping safely and the included-source limitations remaining available through a keyboard-operable disclosure.
- Standardized account-directory control alignment, result-status spacing, table captions, and pagination sizing. The underlying server-side filtering and page data are unchanged.
- Unified minimum interaction sizing and visible focus indicators across navigation, forms, disclosures, and buttons. Added high-contrast and forced-colors fallbacks while preserving the existing skip link.
- Added print styles for administrative evidence: app chrome and interactive controls are suppressed, while visible report panels, account tables, and expanded audit details are formatted for paper.
- Added static source assertions for intermediate-width header reflow, audit details, directory context, keyboard/touch affordances, high-contrast modes, and print output.
- No new Admin powers, API calls, database changes, or production configuration changes were introduced.
- Browser testing remains necessary for the real header/sidebar at 761–1100px, narrow phones, keyboard and forced-colors modes, and printed audit pages. Static assertions do not replace those checks.


## Admin print and narrow-screen follow-up

- Printed account tables now repeat their header row and avoid splitting a data row across pages, improving the readability of multi-page evidence.
- Long audit identifiers, account metadata, and table values are constrained to the printable page width without changing stored values or on-screen data.
- Added a narrow-viewport tier for 360px and below: compact workspace gutters, single-column metrics, stacked pagination controls, and tighter audit detail padding. The account table retains its intentional horizontal-scroll behavior rather than compressing columns into unreadable text.
- Extended the static source-contract test to lock these layout rules.
- These are presentation-only changes. No data access, account permissions, lifecycle transitions, API calls, or database behavior changed.
- Still required: execute the Python contract test and verify browser rendering at 320px, 360px, 200% zoom, and multi-page print output. These checks have not been run in this remote-only commit workflow.


## Role-change dialog accessibility and toast-feedback follow-up

- Role-change validation, stale-account, and mutation failures now use a dismissible toast outside the modal rather than an inline modal error. This keeps the dialog layout stable and makes the feedback visible without requiring the operator to search behind the scrim.
- Successful role changes use the same toast surface with a status announcement; errors use an alert announcement. Toasts dismiss automatically after five seconds and can be dismissed immediately.
- The toast region is positioned above the Admin workspace, remains within narrow viewport bounds, respects device safe-area insets, and is excluded from printed reports.
- Added static source-contract assertions for the toast lifecycle, announcement semantics, dismissal control, responsive styling, and print behavior.
- Kept the programmatic dialog description, access-change warning, reason-length guidance, and textarea/character-counter associations.
- No role-transition authorization, server RPC, database, or lifecycle behavior changed. Browser, keyboard, and screen-reader verification remains outstanding; static assertions only confirm source wiring.
