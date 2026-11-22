# Admin account suspension: view and derived-read boundary review (batch 10)

## Why this review was added

The clean-replay inventory already listed ordinary public tables, RLS policies, Storage policies, and callable functions. That is not sufficient to establish the complete read surface: PostgreSQL views and materialized views can expose derived data, and ordinary view execution may use the view owner's privileges rather than the caller's base-table RLS context.

## Repository review

The current authorization inventory script now separately lists every public view or materialized view for which `authenticated` has SELECT, including:
- relation kind;
- effective relation options (including an explicit `security_invoker=true` option when present);
- the view definition.

This output is discovery evidence only. It does not automatically classify a view as safe or unsafe, and it does not prove that all underlying source tables are protected for the caller.

## Required classification for each returned relation

For every authenticated-readable view/materialized view in the next clean replay:

1. Record whether it is a regular view or materialized view.
2. For a regular view, inspect whether `security_invoker=true` is set. If absent, treat owner-privilege execution as a potential RLS bypass until proven otherwise.
3. Trace every referenced table, function, and nested view. Check whether the definition filters by `auth.uid()`, actor role, assignment membership, or another caller-specific predicate.
4. Determine whether suspended callers could read protected profile, incident, location, notification, chat, or audit-derived data through the view with an otherwise valid JWT.
5. For materialized views, inspect refresh ownership, refresh path, grants, and whether the stored result contains rows that should be hidden from suspended callers. Base-table RLS is not automatically re-evaluated per reader against already materialized rows.
6. Record an explicit decision: revoke authenticated SELECT, convert to a caller-context view where supported, add a trusted guarded RPC, or retain only with a demonstrated safe data contract.

Do not change view definitions or grants in this inventory-only PR. A view migration can change existing client behavior and must be reviewed against actual Android, web, and Laravel call sites before implementation.

## Validation boundary

The updated read-only SQL inventory is included in clean migration replay. The next exact-head CI run must confirm that the query executes against the replayed PostgreSQL version. No live Supabase project was queried or changed. No connected-project grants, views, rows, or policies were modified.
