# TACResQ account suspension and reactivation review

**Status:** repository review and implementation recommendation  
**Scope:** Admin account governance; no production changes  
**Reviewed:** 2026-11-22

## Finding

The current Admin directory does not expose an account-enabled, suspended, or sign-in status. It returns profile identity, role, field-unit duty status, and creation time. The current role-change RPC is protected by a verified administrator TOTP session and writes a role-change audit event. Admin-role provisioning remains an out-of-band trusted-operator task.

Field-unit duty status is a separate operational property. It must not be reused as an account lock or suspension flag.

## Why a profile-only suspension flag is insufficient

Adding `profiles.is_suspended` and hiding a button in the web console would not enforce suspension. The application has multiple authorization boundaries: Supabase Auth sessions, profile and incident table policies, SECURITY DEFINER RPCs, Edge Functions, and Laravel/backend entry points. Every relevant path would need to reject suspended actors server-side. Existing access tokens may remain usable until expiry unless session/token revocation is handled as part of the workflow.

A client-side sign-out or UI-only restriction is not a security boundary. Nor should a suspension operation silently change incident assignments or erase operational history.

## Recommended policy before implementation

1. **Define scope:** distinguish temporary administrative suspension from account deletion, role changes, field-unit off-duty status, and a user's own sign-out.
2. **Protect emergency continuity:** block suspension of responders/drivers with incidents in assigned, en_route, on_scene, or transporting states until a dispatcher completes a documented reassignment or resolution. Do not auto-unassign or silently transfer incidents.
3. **Require a reason:** require a meaningful reason, actor identity, target identity, timestamp, and explicit action (suspended/reactivated). Keep these events append-only and separate from role-change history or label the unified audit action precisely.
4. **Enforce on the server:** centralize a suspension check and apply it consistently to every user-authenticated write/read surface that should be blocked, including RPCs, RLS policies, Edge Functions, and backend APIs. Define whether suspended users can still read limited account/support information.
5. **Revoke sessions:** define and implement refresh-token/session revocation, and account for already-issued access tokens. A database flag alone does not immediately invalidate a JWT.
6. **Prevent lockout:** only an appropriately authorized administrator with fresh MFA may suspend/reactivate ordinary accounts. Admin accounts remain trusted-operator managed. Define recovery if all operational admins are unavailable.
7. **Make state visible:** show a distinct account status and its effective timestamp in the directory; never infer it from duty status. Add filters for active/suspended accounts only after the server supports the status.
8. **Audit and test:** test direct API/RPC attempts, RLS access, active-incident protection, token/session behavior, reactivation, audit immutability, and last-admin/recovery scenarios.

## Suggested staged delivery

- **Stage A — policy and inventory:** agree on who may suspend, what suspended users can access, emergency reassignment rules, and recovery ownership. Inventory every authenticated endpoint and policy.
- **Stage B — backend enforcement:** add the authoritative account state and transaction-safe privileged RPCs; enforce it at every applicable server boundary; add session revocation behavior and append-only audit records.
- **Stage C — Admin UI:** add status, reasoned confirmation, reactivation, filters, and clear error handling. The UI must only reflect server-confirmed state.
- **Stage D — validation:** run SQL/RLS/RPC security contracts, integration checks, and manual role-based workflows before enabling the feature.

## Current decision

Do **not** implement a suspension toggle in the current Admin UI yet. The repository review does not establish complete enforcement coverage across all authentication and backend entry points. A UI-only or profile-only implementation could create a false sense of security and could disrupt active emergency response work.

This document is a design/review artifact only. It does not add a database column, apply a migration, revoke a session, or change production behavior.
