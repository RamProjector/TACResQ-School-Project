# Notification Edge Function: account-state enforcement test contract

**Scope:** `supabase/functions/tacresq-notify/index.ts`  
**Status:** actor and recipient account-state checks are implemented in the current PR branch. A CI source-order contract now verifies the authorization gates precede incident/token reads and OAuth/FCM calls. Isolated Supabase Edge Function tests and deployment verification are still required; no live function was deployed.

## Current source behavior

The function validates the bearer token with `auth.getUser`, loads the actor's `profiles.id,role,account_status`, and rejects any actor not currently active before loading incident data. Existing role and incident relationship checks remain. After event-specific target resolution, it filters target profiles to `account_status = active` before loading FCM registrations. These service-role reads bypass ordinary RLS, so the explicit filters are required.

Consequently, this Edge Function is an explicit future enforcement boundary; it must not be treated as protected by database RLS alone.

## Implemented branch behavior

1. Resolve the actor from the verified Auth UUID, never from a client-supplied profile ID.
2. Read the actor's current account lifecycle state from the authoritative profile row before loading incident data or performing notification side effects. **Implemented.**
3. Fail closed on missing profile, lookup error, missing/unknown lifecycle state, or any state other than `active`. **Implemented.**
4. Keep the existing role and incident relationship checks. An active account is not automatically authorized for an event.
5. Resolve target profile IDs using the existing event-specific logic, then load only active target profiles. **Implemented.**
6. Fetch FCM registrations only for those active target IDs. Do not infer recipient eligibility from token existence. **Implemented.**
7. Accept numeric incident IDs supplied as numbers or digit-only strings, then require a positive safe integer. **Implemented.**
8. Bound the request body to 8 KiB using both the declared Content-Length (when present) and the actual UTF-8 byte length before JSON parsing. Oversized bodies return HTTP 413. **Implemented.**
7. Preserve current no-recipient and no-token responses; do not leak whether a suspended target has a token.
8. Do not delete historical FCM registrations or incident records as a side effect of suspension.
9. Do not put service-role credentials in client code or return them in error messages.

## Automated source-level coverage

`tools/test_account_lifecycle_notification_edge_contract.py` checks that the actor account-state lookup and fail-closed decision precede incident lookup; recipient active-state filtering precedes token lookup; token lookup precedes Google OAuth and FCM send calls; and request parsing enforces the body cap and incident-ID validation. This is a source-level regression check only. It does not execute Deno, mock network calls, or prove provider behavior.

## Remaining Edge Function negative-test matrix

Run against an isolated Supabase test project with synthetic Auth users and FCM tokens. Mock the FCM HTTP endpoint; never send real notifications during tests.

| Scenario | Actor | Recipient | Expected |
|---|---|---|---|
| Normal incident-created event | Active requester | Active dispatcher | Existing authorization and mocked delivery work |
| Stale token after suspension | Suspended requester, same valid JWT | Active dispatcher | 403; zero FCM calls |
| Assignment notification | Active dispatcher | Suspended assigned responder | No delivery; zero token lookup for suspended target |
| Status notification | Active assigned responder | Suspended requester | No delivery |
| Missing profile row | Valid Auth user without TACResQ profile | Any | 403; no incident or token query |
| Lifecycle lookup failure | Active-looking JWT; profile query fails | Any | Fail closed; 403/503; zero FCM calls |
| Unknown lifecycle value | Authenticated actor | Any | Fail closed |
| Active actor, wrong role/incident | Active but unauthorized actor | Any | Existing 403 behavior remains |
| Active actor, no active recipients | Active authorized actor | Suspended/non-provisioned targets | `sent: 0`; no FCM call |
| Oversized request body | Authenticated actor | Any | 413 before JSON parsing or database/provider work |
| Numeric incident ID as digit-only string | Active authorized actor | Active recipient | Same behavior as positive safe-integer numeric ID |
| Non-digit, zero, negative, or unsafe incident ID | Authenticated actor | Any | 400; no incident/recipient/provider work |
| Active actor, invalid FCM token | Active authorized actor | Active recipient with invalid token | Existing invalid-token cleanup remains scoped to that token |

## Test instrumentation requirements

- Assert that suspended-actor requests do not query incident data, FCM registrations, or Google OAuth.
- Assert that recipient queries include the active-state predicate before FCM token retrieval.
- Assert that a profile lookup error never falls back to role-only authorization.
- Assert that event authorization (role, requester ownership, dispatcher assignment, assigned-unit membership) remains unchanged.
- Assert that service-role credentials are read only from server environment and are never serialized in responses or logs.
- Assert zero outbound FCM requests for every denied/no-eligible-recipient case.

## Release gate

The migration and actor/recipient source checks now land together in this PR. Before deployment, run the isolated Edge Function negative-test matrix, verify zero FCM/Google OAuth calls for denied cases, and test with the same still-valid JWT issued before suspension. The function has not been deployed.
