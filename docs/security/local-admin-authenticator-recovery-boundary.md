# Local Laravel Admin authenticator recovery — safety boundary

**Status: NOT IMPLEMENTED / NOT AVAILABLE.** This runbook is a fail-closed incident-handling policy, not a recovery mechanism. It does not authorize database edits, factor deletion, password resets, or bypassing the local Admin TOTP step-up requirement.

## Scope and identity boundary

This procedure applies only to the legacy Laravel local User accounts authenticated with Laravel Sanctum. It does not apply to Supabase Auth administrators, Supabase profiles, or any account inferred from a matching email address. The two identity domains have no approved immutable mapping; never join them by email or accept a client-supplied Supabase UUID as proof of identity.

## If an Admin loses or cannot access their authenticator

1. **Keep the security boundary intact.** Do not disable MFA, delete the credential row, replace the encrypted secret, mint a bypass token, alter the Admin role, or call internal transition functions manually.
2. **Preserve incident-response operations.** Continue emergency intake and responder workflows through their normal non-Admin roles. Do not grant a responder or requester temporary Admin privileges to work around the lockout.
3. **Record the incident outside the application.** Use the organization's approved incident-management channel. Record the affected local account identifier, time reported, reporter, independent reviewers, operational impact, and decision. Do not record passwords, TOTP codes, QR payloads, Base32 secrets, session tokens, or recovery secrets.
4. **Verify identity using pre-established organizational evidence.** A name, email inbox, phone number, screenshot, or possession of the affected device alone is insufficient. Verification must use the organization's documented personnel records and a second independent channel that was established before the lockout. If those records/channels do not exist, stop; do not improvise verification.
5. **Require two-person authorization before any future recovery implementation.** The requester must not approve their own recovery. Two authorized reviewers must independently attest to the identity evidence and record their approvals. This requirement is a design gate; there is currently no in-app approval workflow.
6. **Escalate to the designated system owner/security maintainer.** Until a reviewed recovery mechanism and auditable implementation exist, the account remains unable to perform Admin step-up actions. Do not perform direct production SQL or undocumented maintenance to restore access.
7. **After an approved future recovery mechanism is implemented,** require a new authenticator enrollment and confirmation, revoke affected Sanctum tokens/sessions, verify the audit trail, and conduct a post-recovery review before restoring privileged work. These are future acceptance requirements, not currently available operations.

## Required design gates before implementation

- An immutable, independently verifiable link between the local account and the organization's personnel record; never email-only matching.
- A two-person approval record with distinct approvers, timestamps, reason, evidence references, and tamper-resistant audit retention.
- A narrowly scoped recovery action that cannot be invoked by public clients, ordinary Admin sessions, or the affected account alone.
- Explicit handling for the only Admin being unavailable, without creating an undocumented universal bypass or promoting an unverified user.
- Atomic invalidation of the old factor and sessions, followed by a time-limited enrollment that must be confirmed before privileged access resumes.
- Rate limits, replay protection, no-store responses, secret redaction, and tests for duplicate requests, concurrent approvals, stale approvals, and rollback.
- A tested operational procedure on disposable PostgreSQL, reviewed before any production migration or account change.

## Current technical facts

- Local Admin TOTP secrets are encrypted at rest.
- Enrollment secrets are returned once, pending enrollment expires after ten minutes, and confirmation is required.
- Confirmed factors cannot be silently replaced through the enrollment endpoint.
- The local Admin step-up middleware requires the current local password and a fresh, non-replayed TOTP code.
- No self-service disable/reset endpoint exists.
- No production route currently consumes the local Admin step-up middleware for account suspension.
- No recovery endpoint, recovery-code store, two-person approval workflow, or verified personnel-identity registry exists.

**Operational rule:** if the sole authenticator is lost today, there is no supported in-app recovery. Preserve the account and evidence; do not bypass the controls. This limitation must be resolved through a separately reviewed recovery feature before the local Admin suspension workflow is enabled.
