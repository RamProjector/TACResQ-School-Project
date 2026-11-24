# Legacy requester incident-create lifecycle compatibility

**Scope:** Source review of the older Android-compatible incident creation RPCs and the account-lifecycle write trigger. This is not a live catalog attestation or a claim that the migration has been applied to a connected Supabase project.

## Compatibility RPCs reviewed

The migration `20261019214218_incident_one_time_location.sql` defines:

- `public.api_create_incident(text,text,double precision,double precision,real)`
- `public.api_create_incident_v2(text,text,double precision,double precision,real,text,timestamptz)`

Both are SECURITY INVOKER functions. They validate requester role and request fields, then INSERT into `public.incidents`. They predate the explicit `account_status = 'active'` check added to the current `api_create_incident_v4` entry point.

Do not remove these compatibility signatures without confirming the minimum supported Android version and its RPC usage.

## Lifecycle protection

Migration `20261123120000_account_lifecycle_write_guard_triggers.sql` installs `tacresq_active_account_write_guard` on every public table. Its private SECURITY DEFINER trigger function calls `private.tacresq_current_account_active()` whenever an end-user JWT is present. The active-state helper resolves the JWT subject against the current profile and takes a `FOR SHARE` lock.

Because the guard runs as a table trigger, it also applies to writes made inside SECURITY DEFINER RPCs that bypass ordinary RLS. For the legacy creation functions, the INSERT into `public.incidents` is therefore rejected when the caller's account is suspended, even if the old RPC body itself does not contain the newer explicit lifecycle check.

The new replay assertion specifically verifies that `public.incidents` has the enabled trigger bound to `private.tacresq_reject_suspended_account_write()`. The existing same-valid-JWT contract also checks that a suspended caller cannot perform protected table writes through a disposable SECURITY DEFINER function.

## Boundaries and remaining verification

- The current v4 creation RPC performs its own active-requester check before idempotency lookup; retain that explicit guard.
- The legacy functions are invoker functions and remain subject to their ordinary grants/RLS as well as the incident INSERT trigger.
- The generic write trigger is a second enforcement layer, not a substitute for reviewing each RPC's role, ownership, validation, and response behavior.
- The trigger intentionally permits trusted server operations that have no end-user `auth.uid()`; those service/Auth paths require their own service-role boundary review.
- Migration replay and same-valid-JWT tests have not been executed here. No live Supabase project was queried or modified.
- Do not claim immediate JWT invalidation. The protection is enforced when a protected operation reaches the database.

## Release checks

1. Replay all migrations in order in a disposable PostgreSQL environment.
2. Verify the enabled incident trigger and its exact function OID in the resulting catalog.
3. Call each compatibility RPC with an active requester and confirm the established response contract remains intact.
4. Reuse the same still-valid JWT after suspension and verify each legacy create RPC fails before an incident row is inserted.
5. Confirm an unrelated active requester and trusted server-side no-user-JWT operations retain their intended behavior.
6. Keep compatibility RPC retirement separate from this lifecycle guard and base it on supported-client evidence.
