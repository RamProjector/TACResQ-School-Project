# Legacy Laravel Sanctum account-lifecycle boundary

**Status:** separate identity domain; local lifecycle-state and request enforcement are implemented in PR #347. PostgreSQL rejects every lifecycle-event UPDATE, DELETE, and TRUNCATE. Actor/target IDs are retained as historical identifiers without foreign keys, so deleting a user cannot rewrite audit history. The privileged transition remains unimplemented. A reusable local Admin password + TOTP step-up middleware and transactional audit recorder are present, but no privileged endpoint consumes them. This does not claim that Supabase account suspension affects Laravel Sanctum users.

## Why this is separate

The Laravel API authenticates a local App\\Models\\User with auth:sanctum and Laravel personal-access tokens. The model's fillable/cast definitions and the initial users migration contain no immutable Supabase Auth user identifier. The optional Supabase bridge instead validates a Supabase bearer token and resolves its profile through public.api_me().

There is no approved, immutable relationship between these two identities. Matching email addresses are not sufficient. Do not add an email-based join, copy a client-supplied Supabase UUID, or infer identity from JWT metadata.

## Current controls

- Local login uses the Laravel users table; public registration creates only a local requester.
- Sanctum-authenticated routes are grouped behind auth:sanctum and account.active.
- Suspended local accounts cannot obtain new tokens or access protected routes. Logout and logout-all remain available for self-service token revocation.
- Lifecycle state is not mass assignable through the public User model.
- A separate local_account_lifecycle_events table requires non-null actor/target identifiers and records action, reason, before/after state, and timestamp. Actor and target IDs are stored as historical identifiers without cascading foreign keys; deleting a user preserves the original IDs and the complete event.
- PostgreSQL migration 2026_11_23_000009 rejects event UPDATE and DELETE operations. Migration 2026_11_23_000011 adds database CHECK constraints for positive/distinct actor and target identifiers, nonblank reasons, and the two supported state transitions. Migration 2026_11_23_000012 first checks for historical rows with a NULL actor or target and fails with an actionable error if any exist; it never fabricates identities, deletes events, or silently rewrites history. After the preflight succeeds, it makes both identifiers NOT NULL, so direct inserts cannot create unattributed lifecycle events. Any legacy repair must be separately reviewed and preserve the original audit evidence before the migration is retried. No UPDATE, DELETE, or TRUNCATE is permitted, including changes to actor/target identifiers.
- Feature tests cover content-update rejection, deletion and truncation rejection, missing-identity rejection, reference-mutation rejection, and preservation of actor/target identifiers after user deletion. Expected PostgreSQL trigger failures run inside nested transactions so the outer test transaction remains usable. PostgreSQL-specific trigger tests skip on non-PostgreSQL drivers.

## Still missing

- A privileged transition endpoint that uses the transactional recorder and local Admin guard.
- A reviewed, auditable Admin authenticator recovery process; no self-service factor reset is exposed.
- A production route that consumes the local Admin step-up middleware, plus device/recovery procedures and disposable-PostgreSQL integration validation.
- A full concurrency and integration run on disposable PostgreSQL before enabling transitions.

No local Admin transition UI/API is exposed. No production migration has been applied and no production account has been changed in this implementation slice. Do not join Sanctum and Supabase identities by email.

## Local Admin TOTP factor and step-up guard

The local Laravel Admin domain has a separate TOTP enrollment flow. POST /api/admin/security/totp/enroll requires a local Admin role and current password, rechecks the current local password under a locked and revalidated Admin row, creates a 160-bit random Base32 secret with a ten-minute pending-enrollment expiry, stores it using Laravel authenticated encryption, and returns the secret plus an otpauth URI once with Cache-Control: no-store. POST /api/admin/security/totp/confirm requires the current local password and a valid six-digit code, and locks/rechecks the local Admin role and active state before marking the factor confirmed. Confirmed factors cannot be silently replaced through enrollment. The verifier uses RFC 6238 SHA-1, six digits, 30-second steps, a narrow ±1-step clock-skew window, row locking, and monotonic last_used_step plus a one-way last-code fingerprint to reject replay, including adjacent-step code collisions. The local Admin step-up middleware requires both the current local password and a fresh TOTP code; failed attempts are rate-limited. Secrets are encrypted at rest and never joined to Supabase identities.

The local.admin.reauthenticated alias is registered and has isolated test-route coverage, but no production route consumes it yet. Enrollment is not a recovery mechanism: if an Admin loses their authenticator, there is no supported in-app recovery. The fail-closed handling policy and prerequisites are documented in docs/security/local-admin-authenticator-recovery-boundary.md. Do not add a self-service disable/reset endpoint or expose suspension controls until an independently reviewed, auditable recovery mechanism and the privileged transition endpoint are implemented and tested.


### Database-enforced audit record invariants

The PostgreSQL lifecycle-audit constraint migration independently rejects non-positive actor/target identifiers, identical actor and target IDs, blank reasons, and action/state combinations outside the supported `active → suspended` and `suspended → active` transitions. These checks complement the application recorder; they do not authorize an actor or implement an account-state transition endpoint. PostgreSQL-specific feature coverage attempts invalid direct inserts to ensure the database boundary remains effective even when callers bypass the recorder. The migration's static contract also verifies that each of the five named constraints is added exactly once and that rollback drops each same constraint exactly once. Adding a constraint intentionally fails if pre-existing rows violate it; resolve such data issues through a separately reviewed repair plan rather than weakening or silently skipping the invariant.
