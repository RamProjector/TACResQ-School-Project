# Dispatcher claimability and responder-location lifecycle guard

**Scope:** staged Supabase migration `20261124130000_account_lifecycle_claimability_and_responder_read_guard.sql`. This closes two callable gaps found while checking the dispatcher authorization matrix.

## Claimability mutation

`public.api_set_incident_claimable(bigint,boolean)` now requires the dispatcher profile to remain active in the locked profile re-read. The existing incident-then-profile lock order is preserved, so a suspension transition and a claimability change serialize on the same profile row.

The implementation retains the existing incident lifecycle allowlist, no-op behavior, `updated_at` behavior, and transactional `incident_claimability_logs` entry. The initial role precheck remains an inexpensive rejection only; the locked active-profile read is the authorization decision.

## Responder coordinate read

`private.tacresq_active_responder_locations()` remains the callable helper used by the public invoker RPC, but is now an active-dispatcher SECURITY DEFINER wrapper with a profile `FOR SHARE` lock. The previous freshness-filtered coordinate query is preserved as `private.tacresq_active_responder_locations_internal()` and client EXECUTE is revoked from that internal routine.

The existing query's en-route assignment filter, coordinate bounds, accuracy validation, two-minute freshness window, and per-incident/per-responder latest-row selection are not rewritten.

## Verification added

- PostgreSQL replay catalog assertions check the claimability routine's active-state predicate, pinned search path, VOLATILE SECURITY DEFINER mode, authenticated-only grant, and audit behavior.
- Replay assertions check the responder reader wrapper's active dispatcher lock and confirm the preserved internal query is not client-callable.
- Python source contracts bind the migration and SQL replay assertions together.

**Validation boundary:** these are committed migration and test definitions. The disposable PostgreSQL migration replay and same-valid-JWT runtime denial cases have not been executed here. No connected Supabase project was queried or modified, and no live migration was applied.
