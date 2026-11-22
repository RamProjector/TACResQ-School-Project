# TACResQ Notification Edge Function Security Review

**Reviewed source:** `supabase/functions/tacresq-notify/index.ts`  
**Review scope:** Static source inspection only. No function deployment, secret inspection, database mutation, or runtime test was performed.

## Executive summary

The two credential-pattern findings emitted by the repository's automated first-pass review are not evidence of literal credentials being committed in this source:

- `account.private_key` is read from the parsed `FCM_SERVICE_ACCOUNT_JSON` environment secret.
- `SUPABASE_SERVICE_ROLE_KEY` is read with `Deno.env.get` and is not hard-coded in the function.

These are expected server-side secret references. The function must remain server-side; neither secret may be moved into browser or Android code, logged, returned, or committed as a value.

This does **not** mean the function is fully security-reviewed. Its use of a service-role client creates an elevated authorization boundary that must be maintained explicitly in application logic.

## Verified protections in the inspected source

1. Only POST requests are accepted.
2. A bearer token is required and validated with Supabase Auth `admin.auth.getUser(userToken)` before profile or incident data is queried.
3. The caller's TACResQ profile is resolved through the authenticated Supabase user ID.
4. Only three event names are accepted: `incident_created`, `incident_assigned`, and `incident_status`.
5. The event-specific checks currently require:
   - `incident_created`: caller role is requester and caller profile ID matches the incident requester ID.
   - `incident_assigned`: caller role is dispatcher, incident status is assigned, and an assigned profile exists.
   - `incident_status`: caller role is responder or driver and caller profile ID matches the incident's assigned profile ID.
6. FCM credentials are read from a server environment variable. The source does not expose the private key or Google access token in its JSON response.
7. Invalid/unregistered FCM tokens are removed from the token registry after provider rejection.

## Residual risks and follow-up work

### 1. Service-role authorization boundary

The function uses a service-role Supabase client for profile, incident, and FCM-token queries. Those queries bypass ordinary user RLS policies. The initial `getUser` call validates the bearer token, but it does not cause later service-role database queries to execute as that user.

Consequences:

- Every authorization condition in this function is security-critical.
- New event types or data access must not be added without explicit actor, role, incident relationship, and recipient authorization.
- Do not assume RLS protects these service-role queries.
- Do not accept caller-supplied actor IDs, recipient IDs, device IDs, or arbitrary notification targets as substitutes for deriving them from verified records.

A future hardening change should consider moving authorization and recipient derivation into a narrowly scoped database operation or another server-side boundary with explicit invariants. It must preserve the ability to deliver notifications to other users without exposing service credentials.

### 2. Rate limiting and abuse resistance

The inspected handler does not implement an application-level per-user or per-incident rate limit. Event and relationship checks constrain who can request a notification, but repeated valid requests may still generate duplicate provider sends.

Before adding retries or allowing more event types, design idempotency and rate limiting around authoritative incident state. Do not rely on client-side throttling.

### 3. Notification state freshness

The handler reads the incident's current status and assignment at request time, which is preferable to trusting a client-supplied status. However, the inspected flow does not make the incident read and notification dispatch atomic. State may change after the read and before the provider send.

If stale notifications become a demonstrated issue, use a durable server-side notification/outbox design or re-check the relevant state immediately before dispatch. Avoid introducing a new queue or paid service without approval.

### 4. Provider response handling and operational limits

FCM sends are performed sequentially for the selected registrations. The source has no explicit recipient batch limit or retry policy. Provider failures are logged with status and registration ID, but not persisted as a TACResQ delivery history.

A future improvement should use bounded batches, safe retry rules, and observability that never logs bearer tokens, service-account JSON, private keys, FCM registration tokens, or Google access tokens.

### 5. Dispatcher authority

The current `incident_assigned` check allows a dispatcher-role caller to request a notification for an incident that is assigned. It does not verify a narrower dispatcher-to-incident ownership relationship. Confirm whether TACResQ intentionally grants all dispatchers this authority before introducing dispatcher scoping.

## Recommended next steps

1. Keep `SUPABASE_SERVICE_ROLE_KEY` and `FCM_SERVICE_ACCOUNT_JSON` in server-side function secrets only.
2. Verify the deployed function's secret configuration through the provider dashboard; source inspection cannot establish whether production secrets are configured correctly.
3. Add explicit abuse controls and idempotency before increasing notification event coverage.
4. Decide whether dispatchers are globally authorized or scoped to assigned incidents.
5. Add focused authorization contract tests for each event branch, including wrong-role, wrong-requester, wrong-assignee, missing-profile, unsupported-event, and stale-assignment cases.
6. Review logs and error paths to ensure no credential or FCM registration token is exposed.

## Review limitations

This review did not inspect live Supabase secrets, production rows, deployed function configuration, runtime logs, or provider delivery behavior. It did not run tests or deploy changes. The automated credential-pattern findings should be classified as **secret-reference pattern matches**, not confirmed secret disclosure, unless a literal secret value is found in repository history or runtime artifacts.
