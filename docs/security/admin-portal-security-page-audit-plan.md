# TACResQ | Admin Portal — Security Page Audit & Improvement Plan

## Executive Summary

The current **Security** page (`?adminPage=security`) successfully communicates strong technical honesty about Supabase Auth (`AAL2` + TOTP enforcement) and database migration boundaries. However, as an administrative security console for an emergency response platform (**TACResQ**), it suffers from **information fragmentation**, **contradictory UI controls**, **cognitive overload from developer-facing disclaimers**, and **high lockout risk** for administrators.

---

## 1. Critical UX & Logic Contradictions

### A. Contradictory "Remove" Action on Verified Factors

- **Current State:**
  - Under **Registered Authenticator Factors**, the active factor (`TACResQ Administrator — Verified`) displays a **`Remove`** button.
  - The footer explicitly contradicts this: *"This page cannot remove verified factors or disable MFA."*
- **Why It Needs Fixing:** Offering an interactive button for an unsupported or blocked action breaks user trust and creates dead-end frustration.
- **Recommended Fix:**
  - **When `verified_factors === 1`:** Hide or disable the `Remove` button and replace it with a tooltip/badge: *"Primary factor locked — add a backup authenticator first to replace or remove."*
  - **When `verified_factors >= 2`:** Allow removal of a secondary factor only after a fresh TOTP re-verification prompt.

### B. Duplicate Factor & Session Action Cards

- **Current State:**
  - `REGISTERED AUTHENTICATOR FACTORS` and `Sign out all devices` appear in the **Live Session Check** card (Page 1), and then `VERIFIED AUTHENTICATOR FACTORS (1)` and `Sign out all devices` appear again immediately below it (Page 2).
- **Why It Needs Fixing:** Duplicated controls cause confusion about whether the two `Sign out all devices` buttons perform different actions.
- **Recommended Fix:** Consolidate into a single **Multi-Factor Authentication (MFA)** card and a single **Active Sessions & Devices** card.

---

## 2. Account Lockout & Recovery Resilience

### A. Single-Point-of-Failure Lockout Risk

- **Current State:** Supabase Auth TOTP does not generate recovery codes, and the user currently has only **1 verified factor** with no backup device enrolled.
- **Why It Needs Fixing:** If an administrator loses, breaks, or factory-resets their phone during an active incident response, they are permanently locked out of administrative controls.
- **Recommended Fix:**
  - Promote **Add Backup Authenticator** from a passive secondary block to a **high-visibility warning banner** whenever `verified_factors < 2` (e.g., *"Action Recommended: Only 1 authenticator is registered. Add a backup device to prevent permanent lockout."*).
  - Require a step-by-step confirmation modal when enrolling a backup authenticator that verifies a 6-digit code from the *new* device before saving.

---

## 3. Information Architecture & Copy Hierarchy

### A. Separating Operational Controls from Developer/Deployment Diagnostics

- **Current State:** Engineering implementation notes (*"Database enforcement below is defined in this branch's migration; production application has not been verified here"*) are mixed directly into the primary operational interface alongside daily admin actions.
- **Why It Needs Fixing:** Operational administrators need clear, scannable posture indicators—not git-branch and migration caveats—while engineers/auditors need verifiable environment telemetry.
- **Recommended Fix:**
  1. **Top Summary Strip (At-a-Glance Posture):**
     - Session Assurance: `AAL2 Verified`
     - MFA Coverage: `1 of 2 Recommended Factors Active` (Amber warning state)
     - Backend Enforcement: `Unverified in Production` (Distinct environment badge)
  2. **Primary Interactive Controls (Middle):**
     - **Authenticator Factors** (Primary + Backup management)
     - **Session Revocation** (`Sign out all devices` with clear token-expiry notice)
  3. **Collapsible "System & Migration Coverage" Drawer/Accordion (Bottom):**
     - Move the **Control Coverage** checklist (`Defined privileged-access controls` vs. `Not currently verified or implemented`) into a structured audit table with clear status pills (`Enforced`, `Migration Pending`, `Not Implemented`).

### B. Clarifying Session Invalidation Lag

- **Current State:** The caveat *"Global sign-out revokes refreshable sessions; existing access tokens remain valid until expiry"* is buried at the bottom of Page 2 in small footer text.
- **Why It Needs Fixing:** Admins revoking sessions during a suspected compromise need to know immediately if active JWTs stay alive for `N` minutes.
- **Recommended Fix:** Display the exact access-token TTL right inside the **Sign out all devices** confirmation dialog (e.g., *"Refresh tokens will be revoked immediately. Active sessions will expire within [X] minutes."*).

---

## 4. Visual & Layout Polish

- **Fix Redundant Header Punctuation:** Change `Security.` (with a trailing period on Page 1) to `Security` to match `Overview`, `User management`, `Reports`, and `Activity log`.
- **Sidebar Truncation:** `Ramy Justine Fa...` is truncated abruptly; allow two-line wrapping or a clean `title` tooltip on hover.
- **Print / Export View (Page 3 Orphan):** When exported or printed to PDF, the sticky/bottom bar (`Administrator permissions are checked before each action`) spills onto an empty Page 3. Add `@media print` CSS rules to hide fixed footers and prevent awkward card page breaks.

---

## 5. Prioritized Implementation Checklist

- [x] **P0 (Critical):** The sole verified TOTP factor is visibly locked; the page now shows a prominent backup-authenticator lockout warning whenever fewer than two verified factors are registered. Backup enrollment remains gated by a fresh TOTP-verified AAL2 session.
- [ ] **P0 (Critical):** Deduplicate the **Verified Authenticator Factors** and **Sign out all devices** blocks into single canonical sections. This still needs a full-page review; the current component contains one factor list and one global sign-out action, but no claim is made that every page-level duplicate has been removed.
- [ ] **P1 (High):** Surface the access-token expiry window directly in the **Sign out all devices** confirmation modal instead of burying it in footer text.
- [ ] **P1 (High):** Visually separate **Live Admin Controls** from **Deployment / Migration Verification Status** using clear status badges (`Verified`, `Unverified Migration`, `Planned`).
- [ ] **P2 (Medium):** Clean up typography (`Security.` -> `Security`) and add `@media print` rules so footer banners do not orphan onto a blank final page.

## Implementation Boundary

This document records the requested Admin Portal Security page UI/UX work for PR #347. The P0 sole-factor lockout warning and contradictory Remove control have now been implemented with a focused regression contract. Remaining checklist items are still plans, not implementation evidence. Production MFA, session revocation, token TTL, or backend enforcement has not been verified. Before implementing factor removal or backup enrollment, verify the actual Supabase Auth capabilities and ensure recovery cannot weaken the existing AAL2/TOTP requirement. The session-expiry message must use the configured, verified access-token lifetime rather than a hard-coded estimate. Keep deployment/migration status explicitly environment-scoped.
