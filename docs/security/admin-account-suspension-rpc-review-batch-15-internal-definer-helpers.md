# Account suspension authorization inventory review — batch 15

## Scope

The previous private-function inventory only listed private functions for which `anon` or `authenticated` had direct EXECUTE permission. That is necessary for direct PostgREST reachability, but it is not sufficient for tracing nested privileged execution: a client-callable `SECURITY DEFINER` wrapper can invoke a private helper that is intentionally not directly executable by the client.

## Change

The read-only catalog script now emits a separate list of **every private SECURITY DEFINER function**, regardless of direct client EXECUTE grants. For each helper it reports:
- full function signature;
- effective `anon` and `authenticated` EXECUTE privileges;
- function-level settings, including pinned `search_path`;
- implementation source.

The earlier directly-callable private-function inventory remains in place. The two lists answer different questions: direct client reachability versus privileged helper implementation review.

## Security interpretation

- A helper with no client EXECUTE grant is not automatically safe; it may execute under the authority of a definer wrapper.
- Reviewers must trace each helper's callers and ensure authorization is enforced at the correct boundary. A helper called only after a trusted wrapper's authorization check may have a different contract from a directly callable RPC.
- This catalog output does not establish call-graph reachability. PL/pgSQL and dynamic SQL dependencies are not completely represented by PostgreSQL's dependency catalog, so source tracing remains required.
- No function, grant, policy, or runtime behavior was changed.

## Validation status

The new section is included in the read-only catalog script run at the end of clean migration replay. It has not yet been confirmed by exact-head CI. No connected Supabase project was queried or modified.

## Remaining work

Classify each private definer helper as directly callable, called by a client-callable invoker wrapper, called by a client-callable definer wrapper, trigger-only, or otherwise internal. Record its caller(s), authorization owner, and current-state guard location. Do not start the account-state migration until that trace and the same-valid-JWT tests are complete.
