# Legacy Laravel Sanctum account-lifecycle boundary

**Status:** separate identity domain; local lifecycle-state and request enforcement are implemented in PR #347. PostgreSQL now enforces append-only lifecycle-event content, with a narrowly scoped foreign-key nulling exception. The privileged transition remains unimplemented. A reusable local Admin password re-authentication middleware and transactional audit recorder are present, but no privileged endpoint consumes them. This does not claim that Supabase account suspension affects Laravel Sanctum users.

## Why this is separate

The Laravel API authenticates a local \`App\Models\User\` with \`auth:sanctum\` and Laravel personal-access tokens. The model's fillable/cast definitions and the initial \`users\` migration contain no immutable Supabase Auth user identifier. The optional Supabase bridge instead validates a Supabase bearer token and resolves its profile through \`public.api_me()\`.

There is no approved, immutable relationship between these two identities. Matching email addresses are not sufficient. Do not add an email-based join, copy a client-supplied Supabase UUID, or infer identity from JWT metadata.

## Current controls

- Local login uses the Laravel \`users\` table; public registration creates only a local requester.
- Sanctum-authenticated routes are grouped behind \`auth:sanctum\` and \`account.active\`.
- Suspended local accounts cannot obtain new tokens or access protected routes. Logout and logout-all remain available for self-service token revocation.
- Lifecycle state is not mass assignable through the public User model.
- A separate \`local_account_lifecycle_events\` table records actor/target references, action, reason, before/after state, and timestamp. Deleting a referenced user nulls the relevant reference without deleting the event.
- PostgreSQL migration \`2026_11_23_000009\` rejects event UPDATE and DELETE operations. Its only permitted UPDATE is FK cleanup that changes one or both actor/target references from a non-null value to NULL while every other column remains unchanged.
- Feature tests cover content-update rejection, deletion rejection, and FK-retention behavior. PostgreSQL-specific trigger tests skip on non-PostgreSQL drivers.

## Still missing

- A privileged transition endpoint that uses the transactional recorder and local Admin guard.
- A reviewed, auditable Admin authenticator recovery process; no self-service factor reset is exposed.
- A production route that consumes the local Admin step-up middleware, plus device/recovery procedures and disposable-PostgreSQL integration validation.
- A full concurrency and integration run on disposable PostgreSQL before enabling transitions.

No local Admin transition UI/API is exposed. No production migration or account change is included in this implementation slice. Do not join Sanctum and Supabase identities by email.

## Local Admin TOTP factor and step-up guard

The local Laravel Admin domain now has a separate TOTP enrollment flow. `POST /api/admin/security/totp/enroll` requires a local Admin role and current password, creates a 160-bit random Base32 secret, stores it using Laravel authenticated encryption, and returns the secret plus an `otpauth://` URI once with `Cache-Control: no-store`. `POST /api/admin/security/totp/confirm` requires a valid six-digit code before marking the factor confirmed. Confirmed factors cannot be silently replaced through enrollment. The TOTP verifier uses RFC 6238 SHA-1, six digits, 30-second steps, a narrow ±1-step clock-skew window, row locking, and a monotonic `last_used_step` to reject replay. The local Admin step-up middleware now requires both the current local password and a fresh TOTP code; failed attempts are rate-limited. Secrets are encrypted at rest and never joined to Supabase identities.

The `local.admin.reauthenticated` alias is registered and has isolated test-route coverage, but no production route consumes it yet. Enrollment is not a recovery mechanism: if an Admin loses their authenticator, an independently reviewed recovery process is still required. Do not add a self-service disable/reset endpoint or expose suspension controls until recovery, operational lockout handling, and the privileged transition endpoint are reviewed and tested.
