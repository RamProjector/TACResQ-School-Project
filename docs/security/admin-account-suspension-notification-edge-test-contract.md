# Notification Edge Function: account-state enforcement test contract

**Scope:** `supabase/functions/tacresq-notify/index.ts`  
**Status:** acceptance specification only. The current schema has no account lifecycle column, so this document does not add a query for a nonexistent field or alter notification behavior.

## Current source behavior

The function validates the bearer token with `auth.getUser`, loads the actor's `profiles.id,role`, verifies incident/event ownership, resolves target profiles, and loads their FCM registrations through a service-role client. Service-role reads bypass ordinary RLS. The current profile query does not establish account lifecycle state, and recipient selection does not filter suspended profiles.

Consequently, this Edge Function is an explicit future enforcement boundary; it must not be treated as protected by database RLS alone.

## Required implementation after the account-state migration exists

1. Resolve the actor from the verified Auth UUID, never from a client-supplied profile ID.
2. Read the actor's current account lifecycle state from the authoritative profile row before loading incident data or performing notification side effects.
3. Fail closed on missing profile, lookup error, missing/unknown lifecycle state, or any state other than `active`.
4. Keep the existing role and incident relationship checks. An active account is not automatically authorized for an event.
5. Resolve target profile IDs using the existing event-specific logic, then load only active target profiles.
6. Fetch FCM registrations only for those active target IDs. Do not infer recipient eligibility from token existence.
7. Preserve current no-recipient and no-token responses; do not leak whether a suspended target has a token.
8. Do not delete historical FCM registrations or incident records as a side effect of suspension.
9. Do not put service-role credentials in client code or return them in error messages.

## Edge Function negative-test matrix

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
| Active actor, invalid FCM token | Active authorized actor | Active recipient with invalid token | Existing invalid-token cleanup remains scoped to that token |

## Test instrumentation requirements

- Assert that suspended-actor requests do not query incident data, FCM registrations, or Google OAuth.
- Assert that recipient queries include the active-state predicate before FCM token retrieval.
- Assert that a profile lookup error never falls back to role-only authorization.
- Assert that event authorization (role, requester ownership, dispatcher assignment, assigned-unit membership) remains unchanged.
- Assert that service-role credentials are read only from server environment and are never serialized in responses or logs.
- Assert zero outbound FCM requests for every denied/no-eligible-recipient case.

## Release gate

The account-state migration, Edge Function implementation, and these tests must land as a coordinated change. Do not deploy this function against the existing schema with an assumed `account_status` field. Do not claim suspended-account enforcement until tests prove actor denial and recipient filtering on the deployed function using the same still-valid JWT issued before suspension.
