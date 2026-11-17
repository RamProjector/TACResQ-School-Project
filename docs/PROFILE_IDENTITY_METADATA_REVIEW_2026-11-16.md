# Profile Identity Metadata Immutability Review — 2026-11-16

## Confirmed finding

The live `public.profiles` table grants `authenticated` table-level UPDATE. Its RLS policy limits which row a responder/driver can update, and the existing `tacresq_guard_profile_update()` trigger protects auth identity, name, email, phone, role, and duty status. However, the trigger did not reject changes to the profile primary key `id` or `created_at`.

The live column privilege check confirmed authenticated users have UPDATE privilege on both columns. A primary-key change will fail when existing foreign keys reference the profile, but can succeed for an unreferenced profile; changing `created_at` is not constrained by those foreign keys. This permits self-service metadata tampering and can damage profile references.

## Correction in this PR

Migration `20261116210000_protect_profile_identity_metadata.sql` replaces the current profile guard and adds:
- `NEW.id IS DISTINCT FROM OLD.id` rejection;
- `NEW.created_at IS DISTINCT FROM OLD.created_at` rejection.

It preserves the existing auth identity/contact protections, admin-only operational role changes, prohibition on admin promotion/demotion through the app, field-unit duty-state validation, active-assignment protections, and server-maintained `updated_at`. Identity/creation metadata checks now run at every trigger depth. The `pg_trigger_depth() = 1` guard remains only around user-set role/duty checks because the existing incident-status audit trigger performs a trusted nested update of a field unit's duty status; nested system transitions must remain possible without allowing nested identity edits.

## Validation and limitations

- A focused source-contract test is added to CI.
- Exact-head CI and clean PostgreSQL migration replay must pass before merge.
- No live Supabase migration was applied.
- No production data, Render settings, secrets, deployments, or infrastructure were changed.
- The static test confirms the guard contract; it does not replace a runtime negative test using authenticated field-unit sessions.

## Research comparison

- PostgreSQL row-level security scopes rows, not individual mutable columns. Table-level UPDATE privilege can still permit changes to any column unless column privileges or server-side guards restrict them.
- OWASP API Security recommends server-side validation and protecting object properties from unauthorized modification (mass assignment / broken object property level authorization).

References:
- PostgreSQL privileges: https://www.postgresql.org/docs/current/ddl-priv.html
- PostgreSQL row security: https://www.postgresql.org/docs/current/ddl-rowsecurity.html
- OWASP API Security Top 10: https://owasp.org/www-project-api-security/


## Nested profile-trigger path review — 2026-11-17

The `pg_trigger_depth() = 1` exception was traced against the repository's core migration and the PR's replacement guard.

Confirmed nested path:
1. An authenticated responder/driver advances an incident through the permitted status RPC/transition path.
2. `tacresq_guard_incident_update()` validates that the caller is the currently assigned field unit and that the status transition is allowed.
3. `tacresq_log_incident_status()` performs the existing nested update of `profiles.duty_status` for the assigned unit when the incident moves to `en_route`, `on_scene`, `transporting`, or `resolved`.
4. The profile trigger therefore executes at nested trigger depth; it must allow this trusted operational status synchronization.

The new guard keeps identity checks (`id`, `auth_user_id`, `created_at`, `name`, `email`, `phone`) outside the depth condition, so those properties remain immutable even on nested updates. The depth condition skips only user-set role/duty validation for the nested system transition. The traced incident trigger path validates actor identity and allowed status transitions before the incident statement can commit; an invalid transition raises and rolls back the transaction, including nested profile writes.

No separate authenticated RPC path that can arbitrarily choose a nested profile role/duty update was confirmed in the inspected trigger chain. This is a source-path review, not a proof that no other trigger exists in the full live schema. The exact migration replay and authenticated SQL test should enumerate/verify the relevant trigger behavior before merge. Do not remove the depth exception without replacing the trusted incident-status synchronization path.

Research basis:
- PostgreSQL trigger execution and trigger depth are part of server-side trigger execution semantics: https://www.postgresql.org/docs/current/plpgsql-trigger.html
- Supabase recommends tightly scoped SECURITY DEFINER functions, pinned search_path, and explicit execute grants: https://supabase.com/docs/guides/database/functions

No live database changes, tests, or CI were run in this review.
