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
