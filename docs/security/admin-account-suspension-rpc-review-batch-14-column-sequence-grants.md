# Account suspension authorization inventory review — batch 14

## Scope

This batch closes a catalog-discovery gap found while reviewing the clean-replay inventory. The existing report checked relation-level privileges, but PostgreSQL can also grant privileges at the column level and on sequences. A table-only report therefore cannot be treated as a complete direct-client privilege inventory.

## Change

The read-only catalog script `tools/check_account_suspension_authorization_surface.sql` now emits three additional sections after the existing function, relation, policy, view, and trigger inventories:

1. **Effective column privileges** for `anon` and `authenticated` on public tables, partitions, views, materialized views, and foreign tables. It reports SELECT, INSERT, and UPDATE independently for every column with at least one effective client grant.
2. **Effective public sequence privileges** for both client roles, reporting USAGE, SELECT, and UPDATE. This makes identifier-generation access visible even when table-level privileges are absent.
3. **Client schema USAGE** for public, private, storage, and auth, to expose the namespace prerequisite alongside object-level grants.

The clean migration replay script already invokes this catalog report at its end, so the new sections become part of the same disposable replay output without touching a connected Supabase project.

## Security interpretation

- These are effective-privilege discovery results, not proof that a row is reachable. RLS, view security semantics, function execution context, and Storage policies still need separate classification.
- Column grants are not interchangeable with relation grants. Reviewers must not infer that a missing table-level grant means every column is inaccessible.
- Sequence grants are not equivalent to permission to insert a row, but they are a distinct client capability and can leak or consume sequence values.
- Schema USAGE alone does not grant access to contained objects.
- The report intentionally does not change grants, revoke privileges, alter RLS, or create an account-state field.

## Validation status

The SQL was added to the existing disposable migration-replay inventory path. It has **not yet been confirmed by an exact-head CI run**. The current PR head had no workflow runs or commit statuses when this batch began. The new catalog queries must be confirmed by the next exact-head clean replay before their output is treated as evidence.

## Remaining review

After clean replay, classify every returned column and sequence grant against its relation's RLS/policy and client call sites. Also inspect default-privilege declarations and non-public exposed schemas where relevant to the deployed PostgREST configuration. Do not start the account-state migration until the effective authorization surface and same-valid-JWT negative tests are complete.
