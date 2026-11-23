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
- A verified local MFA factor or other stronger step-up mechanism; the current guard rechecks the local password and is not MFA.
- A full concurrency and integration run on disposable PostgreSQL before enabling transitions.

No local Admin transition UI/API is exposed. No production migration or account change is included in this implementation slice. Do not join Sanctum and Supabase identities by email.

## Local Admin step-up guard (not an endpoint)

The local Laravel middleware alias `local.admin.reauthenticated` requires a local `users.role = admin` identity and a fresh `current_password` check against that same Laravel user record. Failed attempts are rate-limited per local Admin and source for 15 minutes after five failures. The middleware does not accept Supabase identity claims, does not infer Admin status from email, and does not provide MFA. No production route currently consumes this alias; it is a reusable guard with isolated feature coverage only. Do not describe this as MFA or expose suspension controls until a local second factor/re-authentication policy and a reviewed transition endpoint are implemented.
