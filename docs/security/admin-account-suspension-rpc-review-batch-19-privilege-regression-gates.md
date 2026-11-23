# Account suspension authorization inventory review — batch 19

## Change

The clean-replay privilege baseline now has executable regression assertions for the high-impact grants exposed by batches 14–18. The migration replay fails if future migrations introduce any of these client capabilities:

- table-level TRUNCATE, REFERENCES, or TRIGGER on public application tables for `anon` or `authenticated`;
- column-level REFERENCES on public application relations for either client role;
- any public sequence privilege for `anon`;
- authenticated UPDATE on a public sequence (including `setval` capability).

The existing baseline assertions also continue to reject client schema CREATE, client membership in SUPERUSER/BYPASSRLS roles, and default ACL entries granting future object privileges to PUBLIC or either client role.

The checks use PostgreSQL effective-privilege functions rather than searching only explicit GRANT statements, so inherited grants are included.

## Why these checks matter

- TRUNCATE is not governed by row-level security.
- REFERENCES and TRIGGER are separate table/column capabilities and should not appear as accidental client grants.
- Sequence UPDATE permits sequence mutation and is distinct from the USAGE/SELECT grants observed for five sequences in the clean replay.
- Sequence USAGE/SELECT remains present for authenticated on five current sequences; this batch does not revoke or change those existing grants.
- The checks are regression guardrails, not a substitute for reviewing SELECT/INSERT/UPDATE/DELETE grants, RLS policy composition, SECURITY DEFINER code, or Storage policies.

## Validation status

The baseline assertions are committed after the successful exact-head clean replay on the preceding revision. They have not yet run on the new head. The next CI replay must confirm the expected current grants satisfy these assertions.

No account-state column, suspension RPC, UI, production grant change, or connected Supabase operation was introduced.

## Remaining work

The primary blocker remains the per-entry-point current-account-state contract and executable same-valid-JWT denial tests. Continue with a callable-function/call-graph matrix, then design a minimal shared guard that does not break the established profile/incident lock order or emergency operational continuity.
