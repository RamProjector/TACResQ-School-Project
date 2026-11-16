# Profile Identity Metadata Immutability Review — 2026-11-16

## Confirmed finding

The live `public.profiles` table grants `authenticated` table-level UPDATE. Its RLS policy limits which row a responder/driver can update, and the existing `tacresq_guard_profile_update()` trigger protects auth identity, name, email, phone, role, and duty status. However, the trigger did not reject changes to the profile primary key `id` or `created_at`.

The live column privilege check confirmed authenticated users have UPDATE privilege on both columns. A primary-key change will fail when existing foreign keys reference the profile, but can succeed for an unreferenced profile; changing `created_at` is not constrained by those foreign keys. This permits self-service metadata tampering and can damage profile references.

## Correction in this PR

Migration `20261116210000_protect_profile_identity_metadata.sql` replaces the current profile guard and adds:
- `NEW.id IS DISTINCT FROM OLD.id` rejection;
- `NEW.created_at IS DISTINCT FROM OLD.created_at` rejection.

It preserves the existing auth identity/contact protections, admin-only operational role changes, prohibition on admin promotion/demotion through the app, field-unit duty-state validation, active-assignment protections, and server-maintained `updated_at`.

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
