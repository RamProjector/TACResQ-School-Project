# Field-unit duty-status lifecycle guard

**Scope:** staged Supabase migration `20261124140000_account_lifecycle_duty_status_guard.sql`.

The `public.api_set_duty_status(text)` RPC now requires the caller to resolve to an active responder or driver. It locks the caller's profile row with `FOR UPDATE` before the authorization decision and holds that lock through the duty-status update. This serializes a duty change against the account-suspension transition, which uses the same profile row as its lifecycle boundary.

The change preserves the existing operational rules:
- Only `available` and `off_duty` may be user-selected.
- Requesters and dispatchers cannot use the field-unit duty control.
- A field unit with an active assigned incident cannot change duty status.
- Suspension does not automatically rewrite duty status, assignments, or incident history.
- The function remains SECURITY INVOKER and authenticated-only.

## Verification added

- PostgreSQL replay contract checks the exact callable signature, invoker/volatile mode, configured search path, active-account predicate, profile lock, active-incident guard, and effective EXECUTE grants.
- Python source contract ties the migration to the replay assertion.

**Validation boundary:** the migration and contracts are committed but have not been executed in disposable PostgreSQL here. A same-valid-JWT suspended field-unit denial test and duty-change-versus-suspension concurrency test remain required. No live Supabase project was queried or modified, and no live migration was applied.
