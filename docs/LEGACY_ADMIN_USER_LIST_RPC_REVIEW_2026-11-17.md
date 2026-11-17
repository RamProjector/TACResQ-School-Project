# Legacy Admin User Listing RPC Restriction — 2026-11-17

## Finding

The live database still exposes `api_admin_list_users()` to authenticated sessions. It checks that the caller is an administrator, but returns every profile in one JSON response, including names, email addresses, phone numbers, role, duty status, and creation timestamps.

The current Admin Console no longer calls this RPC. It uses `api_admin_list_users_page(limit, offset, query, role)`, which caps each page at 100 rows and validates search length, role filter, and offset bounds. The old RPC remains in the database history and is still typed in the dashboard's Supabase function map, but there is no current UI caller.

## Change

Migration `20261117010000_restrict_legacy_admin_user_list_rpc.sql` revokes EXECUTE from PUBLIC, anon, and authenticated while retaining the function definition for compatibility. The dashboard's stale function type is removed. The paginated RPC remains unchanged.

This is defense-in-depth and data-minimization work, not a confirmed privilege-escalation vulnerability: the legacy RPC already required the admin role.

## Validation and limitations

- A source-contract test verifies the revocation and confirms the Admin Console uses the paginated API.
- Exact-head CI and clean PostgreSQL migration replay are required before merge.
- No live Supabase migration is applied by this change.
- No production data, Render configuration, secrets, or deployment settings are changed.
