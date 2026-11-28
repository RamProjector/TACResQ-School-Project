# TACResQ RPC authorization runtime regression matrix

**Research date:** 2026-11-26  
**Source audit:** Section 54 of `docs/TACRESQ_UI_FUNCTIONALITY_MASTER_PLAN.md`  
**Test environment target:** disposable local Supabase only  
**Related plans:** `docs/QA_LOCAL_SUPABASE_INTEGRATION_PLAN.md`, `docs/QA_SYNTHETIC_SOS_LIFECYCLE_VERIFICATION.md`

## 1. Purpose

Convert the live public RPC authorization review into executable regression cases without changing the connected project. The key principle is to test with **the same still-valid JWT after the account becomes suspended**. Signing out, deleting the session, or issuing a new token would not prove the active-account guard.

This matrix is a test specification. The connected database inspection reported the current function bodies and grants; this document does not independently execute or change the hosted project.

## 2. Findings that drive the tests

1. The live `public.api_mark_notification_read(bigint)` resolves the caller's profile by `auth.uid()` and updates only a notification where both notification ID and recipient profile match. It does not require `account_status='active'` or call the active-profile helper. The function is caller-owned, but a suspended account retaining a valid JWT can still change its own `read_at` marker.
2. `public.api_unregister_fcm_token(text)` is an intentional suspended-account cleanup exception. It derives the profile from the Auth UUID through `private.tacresq_auth_profile_id_for_token_cleanup()`, and deletes only the exact token belonging to that profile. Do not block this cleanup by adding the normal active-profile helper.
3. The FCM token policy set is intentionally asymmetric: SELECT/INSERT/UPDATE require an active account; DELETE is limited to the caller's own token and may remain available while suspended.
4. `public.tacresq_require_incident_location()` is a trigger function, not an application RPC. Its direct client EXECUTE grants should be absent, while normal trigger execution must continue to work.
5. `public.api_admin_list_audit_logs(integer)` remains authenticated-callable as a legacy compatibility surface; the current Admin web source review did not establish a direct caller. Do not revoke it until Android, web, Laravel and supported external callers have been traced.
6. The Section 54 audit reports 24 public SECURITY DEFINER application entry points and 13 authenticated-callable private SECURITY DEFINER helpers, with anon denied. Runtime tests should validate the exact signature inventory and the authorization behavior, not rely only on naming conventions.

## 3. Runtime test matrix

| Case | Actor / token | Action | Expected result | Evidence |
|---|---|---|---|---|
| NR-01 | Active requester, valid JWT | Mark own notification read | Success; only own row gets `read_at` | RPC response and DB row |
| NR-02 | Active requester, valid JWT | Mark another user's notification read | False/not-found result; other row unchanged | RPC response and DB row |
| NR-03 | Suspended requester, same JWT issued before suspension | Mark own notification read | Denied after active-account fix; no row mutation | RPC response and unchanged DB row |
| NR-04 | Suspended requester, same JWT | List notifications | Denied/empty according to the established active-reader contract; no metadata leak | RPC response |
| NR-05 | Suspended requester, same JWT | Invoke unrelated active-only write | Denied; no mutation | RPC response and DB state |
| FCM-01 | Active user, own token | Register token | Success; token associated with caller profile | RPC response and token row |
| FCM-02 | Suspended user, same JWT | Register or update token | Denied | RPC response and unchanged token row |
| FCM-03 | Suspended user, same JWT | List/read tokens | Denied by active SELECT policy | API/RLS response |
| FCM-04 | Suspended user, same JWT | Unregister exact own token | Success; only that exact caller-owned token is removed | RPC response and token rows |
| FCM-05 | Suspended user, same JWT | Attempt to unregister another profile's token | No deletion; no cross-owner effect | RPC response and both token rows |
| FCM-06 | Suspended user, same JWT | Attempt direct token DELETE outside the self-owned predicate | Denied/no affected row | SQL/API result |
| TRG-01 | Authenticated user | Directly call trigger-only location function | EXECUTE denied | `has_function_privilege` and RPC denial |
| TRG-02 | Valid incident mutation that fires location trigger | Perform allowed operation | Trigger executes normally and enforces location invariant | Transaction result and resulting row |
| TRG-03 | Invalid incident location mutation | Attempt to remove final usable pin/landmark | Trigger rejects transaction | SQLSTATE and unchanged row |
| ADM-01 | Active Admin with AAL2 and TOTP | Call each of eight Admin/audit wrappers | Authorized only after shared MFA/active Admin helper | RPC response and audit effects |
| ADM-02 | Active Admin with AAL1 or no TOTP | Call each Admin/audit wrapper | Denied; no read/mutation | RPC response |
| ADM-03 | Suspended Admin with previously valid AAL2/TOTP JWT | Call each Admin/audit wrapper | Denied by active-account check | RPC response |
| ADM-04 | Non-Admin with AAL2/TOTP | Call each Admin/audit wrapper | Denied by role check | RPC response |
| ADM-05 | Any non-owner | Call legacy audit-list RPC | Verify current grant and bounded data scope; do not change compatibility grant without caller trace | Catalog + HTTP result |
| DEF-01 | anon | Call every client-facing public application RPC not explicitly public | Denied | Signature/grant inventory |
| DEF-02 | authenticated | Directly call private internal definer routines not intentionally exposed | Denied | Signature/grant inventory |
| DEF-03 | Trigger execution context | Fire trigger whose function is not client-callable | Trigger still succeeds/fails according to its business invariant | Actual trigger-driven DML |

## 4. Required implementation sequence

### Step 1 — Fix mark-read active-account policy in a new migration

- Replace caller resolution in `api_mark_notification_read(bigint)` with the established active-profile resolver (or an equivalent active-profile check with the same row-locking/concurrency guarantees).
- Preserve the current caller-owned predicate: notification ID **and** recipient profile ID must both match.
- Preserve indistinguishable false/not-found behavior for missing and non-owned notification IDs.
- Keep the function SECURITY DEFINER only if the existing table privilege/RLS architecture requires it; retain a pinned search path and exact EXECUTE grants.
- Add NR-01 through NR-05 to executable SQL/runtime tests.
- Replay from a clean database and test with the same JWT before and after suspension.

### Step 2 — Lock in the FCM cleanup exception

- Do not replace `tacresq_auth_profile_id_for_token_cleanup()` with the active-only profile helper in unregister.
- Add FCM-01 through FCM-06 to prove the exception is narrow.
- Verify the cleanup flag is transaction-local and cleared, and that the trigger exception does not grant broad table mutation.
- Keep FCM provider sends outside this suite; this is token ownership/lifecycle only.

### Step 3 — Verify trigger-only grants and behavior

- Assert no direct EXECUTE for anon, authenticated, or service_role on `public.tacresq_require_incident_location()` if that is the approved intended boundary.
- Execute actual INSERT/UPDATE statements that fire the trigger to prove revoking direct call rights does not disable trigger execution.
- Keep invalid location writes rejected atomically.
- Do not deploy a grant change to the connected project without explicit approval.

### Step 4 — Reconcile the complete RPC/helper inventory

- Assert exact API signatures rather than a raw schema-wide function count that may include extension functions.
- For each public application API, record owner, security mode, pinned search path, anon/authenticated EXECUTE, helper dependencies, active-account requirement, role and ownership rules.
- For private SECURITY DEFINER helpers, record intended callers and deny direct client EXECUTE unless explicitly approved.
- Trace `api_admin_list_audit_logs(integer)` through Android, web, Laravel and supported external callers before any compatibility grant change.

## 5. Local test harness requirements

- Use the local Supabase project and synthetic Auth users only.
- Create active and suspended identities through local Auth; let the Auth trigger create profiles.
- Keep the original JWT in memory, suspend the profile through an owner-controlled fixture connection, then replay that same token against PostgREST.
- Never use the service-role key as the simulated client identity. Use each actor's real local access token and publishable key.
- Execute direct catalog/grant checks through the local database connection.
- Capture only synthetic IDs, RPC names, SQLSTATE/result categories and pass/fail evidence; redact tokens and credentials.
- Run each case inside isolated transactions/fixtures; clean up only current-run rows and destroy the local stack.
- No hosted URL, live service-role key, Render production, FCM send, real device, or production account may be used.

## 6. Acceptance criteria

- [ ] Mark-read is denied for a suspended user with the same still-valid JWT, while active users can mark only their own notifications read.
- [ ] Suspended users can unregister only their own exact FCM token.
- [ ] Suspended users cannot list, register, update, or delete another user's FCM token.
- [ ] Trigger-only functions have no direct client EXECUTE, and their bound triggers continue working.
- [ ] Admin wrappers reject AAL1, missing TOTP, non-Admin and suspended Admin cases.
- [ ] Public/private RPC grant inventory matches exact reviewed signatures.
- [ ] Legacy Admin audit-list grant remains unchanged until compatibility trace is complete.
- [ ] All tests run against clean local Supabase and same-valid-JWT fixtures.
- [ ] No hosted database or live grants were changed.

## 7. Evidence limitations

The Section 54 connected-database audit is catalog/body evidence, not a runtime authorization test. It does not demonstrate that an attacker accessed another user's data. The mark-read behavior is a narrow suspended-account policy inconsistency affecting the caller's own notification marker, not a cross-user read/write exposure. The proposed tests must establish the corrected behavior before any deployment claim is made.
