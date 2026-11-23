# Account suspension review — batch 13: Auth signup validation and rollback

## Scope

This batch examines the final `public.tacresq_profile_on_signup()` implementation after all migrations have replayed. It focuses on the Auth-to-profile lifecycle boundary, not on implementing suspension.

## Source findings

The final function is redefined by the requester-contact migrations. It:

- requires a non-empty Auth email;
- requires a non-empty name and caps it at 120 characters;
- restricts phone input to digits and common separators, caps its raw length at 25 characters, and requires 7–15 digits after normalization;
- explicitly creates the `requester` role regardless of client-supplied role metadata;
- pins its SECURITY DEFINER search path to `pg_catalog`;
- schema-qualifies the normalization functions and target table;
- uses `ON CONFLICT (auth_user_id) DO NOTHING`;
- revokes direct execution from `PUBLIC`, `anon`, and `authenticated`.

The trigger is an AFTER INSERT hook on `auth.users`. A validation exception aborts the Auth insert transaction, so an invalid signup must not leave either an Auth row or a profile row.

## New disposable migration-replay regression

The replay script now exercises two invalid signup cases against the final trigger:

1. A phone value with no acceptable digits.
2. A name longer than 120 characters.

For each case it requires SQLSTATE class `22023` (caught as PostgreSQL `invalid_parameter_value`) and verifies that neither `auth.users` nor `public.profiles` contains the rejected test UUID. The test runs only in the disposable clean-replay database.

Existing replay fixtures also test that a public signup attempting to supply `role=admin` is still provisioned as `requester`.

## Suspension-specific limitation

These tests protect signup validation and transaction rollback; they do not test suspended-account enforcement. The current schema has no separate account-state column, and the signup function does not yet initialize or inspect one. When account state is introduced, preserve the conflict-safe insert and ensure signup cannot overwrite or reactivate an existing profile. Do not infer that disabling Auth signup alone is sufficient to block existing valid JWTs.

## Validation status

The regression was committed to the PR branch. It has not yet been executed by an exact-head CI run in this review batch. No connected Supabase project, production Auth user, profile, or session was modified.
