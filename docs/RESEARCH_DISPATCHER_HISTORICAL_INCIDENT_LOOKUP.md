# Dispatcher historical incident lookup: security review

**Review date:** 2026-11-20  
**Status:** Research and implementation specification; no endpoint or migration added in this review.

## Finding

The dispatcher push-notification path currently resolves the notification through the caller-scoped notification RPC and opens the dispatch queue with the incident ID as a focus hint. The Android dispatcher screen then searches the already-loaded incident collection for that ID. The Laravel dispatcher list endpoint returns a bounded snapshot (maximum 100 records); the Android closed-history panel displays only the latest five terminal incidents from that snapshot.

This is safe against silently loading arbitrary records, but it is not a complete historical deep-link experience: a valid notification referring to an older incident can fail to resolve when the incident is outside the list snapshot. Increasing the list limit or fetching all incidents is not an acceptable fix.

## Recommended design

Add a dedicated, read-only, ID-scoped lookup to each supported data path only after identifying the authoritative path used by the active Android build:

1. **Supabase RPC path:** a narrowly scoped `api_dispatcher_incident_by_id(p_incident_id bigint)` function, with an explicit dispatcher-role check derived from the authenticated session/profile, positive/non-null ID validation, and a single-row result. Return only the fields already required by the dispatcher incident card and audit/history UI. It must not return a general incident collection.
2. **Function hardening:** default to `SECURITY INVOKER` where existing RLS supports the required read. If a definer function is genuinely required to read dispatcher-visible rows, pin an empty search path, schema-qualify every object, explicitly check the caller's dispatcher role inside the function, revoke execute from `PUBLIC` and `anon`, and grant only to `authenticated`. A grant alone does not enforce row-level object authorization.
3. **Laravel bridge path:** if this path is used, add a dispatcher-only `GET /incidents/{incident}` equivalent with object-level authorization and a deliberately bounded response. Do not treat the existence of the route as evidence the current Android release uses it; the current Android repository has both direct Supabase RPC and opt-in Render bridge transports.
4. **Android navigation:** retain the bounded list for the queue. When the notification target is absent after refresh, invoke the ID-scoped lookup, show the record in a read-only historical detail state, and never add it to the pending/active queue or expose assignment/progress actions. Clear the focus state when the user leaves the alert target.
5. **Failure behavior:** distinguish not found/not authorized from temporary network failure without disclosing whether another role owns a record. Provide retry only for transient failure. Do not trust the incident ID or notification payload as an authorization token.

## Required authorization and regression matrix

| Caller / target | Expected result |
|---|---|
| Dispatcher, existing pending incident | Read permitted; queue remains authoritative |
| Dispatcher, assigned or active incident | Read permitted; no state change |
| Dispatcher, resolved incident | Read permitted as read-only history |
| Dispatcher, cancelled incident | Read permitted as read-only history |
| Requester, own incident | Use requester-scoped API, not dispatcher lookup |
| Requester, another requester's incident | Denied |
| Responder/driver, unrelated incident | Denied by dispatcher-only lookup |
| Anonymous / expired session | Denied |
| Dispatcher, nonexistent ID | Not found without data leakage |
| Dispatcher, malformed/zero/negative ID | Rejected |
| Old notification outside list's 100-row snapshot | Exact authorized record can open read-only |
| Forged notification incident ID | Lookup still enforces role and object access |

Add authenticated database tests for role denial and row visibility, Android source contracts for fallback/read-only state, and Laravel feature tests if the bridge endpoint is implemented. CI must run the new contracts. Manual device acceptance remains a later testing phase.

## Important implementation boundary

Do not add a broad security-definer function that returns arbitrary incidents to all authenticated users. Do not expose a generic `incident_by_id` endpoint to every role. Do not change the existing requester edit/cancel rules, active queue filters, or 100-record collection bound. No migration should be applied to production as part of this work.

## Research

- OWASP API Security Top 10 API1:2023 explains that every endpoint accepting an object ID must enforce object-level authorization; a valid session or possession of an ID is not enough: https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/
- Supabase Database Functions guidance says to prefer invoker rights; when `SECURITY DEFINER` is necessary, set a safe search path and explicitly check ownership/authorization inside the function. Function execute grants do not limit which rows a caller can retrieve: https://supabase.com/docs/guides/database/functions
- Supabase RLS guidance recommends an empty search path for definer functions and schema-qualified object references: https://supabase.com/docs/guides/database/postgres/row-level-security

## Current review conclusion

The current behavior fails closed, so this is a usability/completeness gap rather than evidence of an authorization bypass. Implementing the lookup requires matching both Android transports and verifying the actual RPC/RLS contract first. Keep this as a follow-up implementation slice rather than weakening the current bounded queue.
