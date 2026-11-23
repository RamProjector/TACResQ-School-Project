# Legacy Laravel Sanctum account-lifecycle boundary

**Status:** separate identity domain; local lifecycle-state and request enforcement are implemented in PR #347. PostgreSQL now enforces append-only lifecycle-event content, with a narrowly scoped foreign-key nulling exception. The privileged transition and trusted audit writer remain unimplemented. This does not claim that Supabase account suspension affects Laravel Sanctum users.

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

- A trusted transactional audit writer tied to a privileged transition.
- A verified local Admin authorization and MFA/re-authentication boundary.
- Explicit same-valid-token denial tests for each high-risk incident and assignment route.
- A full concurrency and integration run on disposable PostgreSQL before enabling transitions.

No local Admin transition UI/API is exposed. No production migration or account change is included in this implementation slice. Do not join Sanctum and Supabase identities by email.
