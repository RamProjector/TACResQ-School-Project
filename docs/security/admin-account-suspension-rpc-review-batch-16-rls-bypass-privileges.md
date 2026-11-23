# Account suspension authorization inventory review — batch 16

## Scope

The privilege inventory needed to account for operations that are not covered by ordinary row-level policy evaluation, and for role membership that can bypass RLS entirely.

## Change

The read-only post-replay catalog now additionally reports:

- Effective table-level `TRUNCATE`, `REFERENCES`, and `TRIGGER` privileges for `anon` and `authenticated`, alongside SELECT/INSERT/UPDATE/DELETE.
- Effective column-level `REFERENCES` privileges alongside SELECT/INSERT/UPDATE.
- Membership of either client role in any PostgreSQL role that has `SUPERUSER` or `BYPASSRLS`, including indirect membership as evaluated by `pg_has_role(..., 'MEMBER')`.

This matters because `TRUNCATE` is not governed by row-level policies, and a role with `BYPASSRLS` or superuser capability does not follow the ordinary RLS enforcement model.

## Security interpretation

- The inventory is observational; it does not revoke grants or alter role membership.
- A returned privilege is a capability requiring classification, not proof that a particular API endpoint exposes it.
- No row returned by the elevated-role membership query is the expected safe baseline for `anon` and `authenticated`; the exact replay output must confirm this.
- Even with no elevated membership, table owners, SECURITY DEFINER functions, views, and Storage remain separate authorization boundaries.

## Validation status

The query additions are included in the clean migration replay's read-only inventory. They have not yet been confirmed by an exact-head CI run. No connected Supabase project was accessed or changed.

## Remaining work

Review the replay output for any nonstandard table/column capabilities and any client membership in elevated roles. Continue tracing every callable and nested privileged routine to its caller and account-state enforcement point. No account-state migration should begin until the authorization map and same-valid-JWT denial tests are complete.
