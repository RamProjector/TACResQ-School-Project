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


## Implementation-path correction — 2026-11-20

A deeper source review corrected an important assumption in the initial finding: the 100-record Laravel endpoint is **not** the only or default Android queue path.

### Actual Android and server transports

- The active Supabase implementation is `SupabaseTacApi`. Its `allIncidents()` method calls `api_all_incidents()` directly through PostgREST by default.
- The same Android class supports an opt-in Render bridge transport. When enabled, it calls `bridge/rpc/api_all_incidents`; the Laravel bridge forwards the caller's own Supabase JWT and checks the function against the explicit `bridge_rpc_allowlist`.
- The separate Laravel `TacApi` contract exposes `GET /incidents`, whose route enforces the dispatcher role and caps the response at 100. This bound does **not** apply to the direct Supabase RPC or its Render RPC-bridge equivalent.
- The dispatcher screen sets `focusedIncident` by searching `state.incidents` for `focusedIncidentId`. If absent after refresh, it displays a message that the incident is not in the current authorized queue. It does not perform an ID-specific fetch today.

### Database behavior and security posture

The current `api_all_incidents()` definition is in `20261023081706_dispatch_team_assignment.sql` (replacing the original core-schema definition). It is `STABLE SECURITY INVOKER`, pins `search_path = pg_catalog, public`, explicitly checks that the authenticated profile role is `dispatcher`, and returns the incident collection using `jsonb_agg`. The aggregate query has no `LIMIT` or cursor parameter.

The incidents SELECT RLS policy also permits the authenticated dispatcher role to read incident rows; requester and assigned field-unit branches are separately scoped. The invoker function therefore retains RLS rather than bypassing it. The function is granted to authenticated callers, but the explicit role check and RLS remain the data-access boundaries.

**Corrected conclusion:** the historical notification gap is present on the actual Android path, and the queue's unbounded aggregate is a separate scalability/availability concern. The bounded Laravel endpoint does not solve either issue for the default Supabase RPC path. This is not evidence of an IDOR: current code fails closed when the target is absent, and the RPC checks dispatcher role.

### Revised implementation sequence

1. Add a dispatcher-only `api_dispatcher_incident_by_id(p_incident_id bigint)` RPC using `SECURITY INVOKER`, a positive-ID guard, explicit dispatcher-role validation, and a single-row response. Keep the incidents RLS policy as an independent authorization layer. Return the same minimal incident fields used by the dispatcher card/history.
2. Register the RPC in the Render bridge allowlist only if the matching bridge method is implemented. The bridge must continue forwarding the user's JWT, never a service-role token for this business read.
3. Add a repository operation and wire both direct and opt-in bridge methods. Validate IDs client-side for usability, but rely on SQL authorization for security.
4. When a notification target is not in the current queue snapshot after refresh, fetch it by ID and render a read-only historical detail card. Never append it to the active queue or expose assignment, claimability, or progress controls. Keep the notification ID as a focus hint only.
5. Separately replace the unbounded `api_all_incidents()` collection with a bounded, deterministic queue contract (or an explicitly paged endpoint) in a backward-compatible rollout. Do not silently change the return shape expected by existing Android clients. A single-ID lookup is not a substitute for queue pagination.
6. Keep the Laravel `GET /incidents` limit of 100. If that separate backend is still used by any client, add a matching ID-scoped route only with dispatcher object-level authorization and a feature-test matrix.

### Source-contract coverage added

A lightweight CI contract now characterizes the actual direct Supabase RPC, opt-in Render bridge, dispatcher focus behavior, and separate bounded Laravel route. It intentionally does not claim to execute PostgreSQL authorization or Android navigation. Authenticated migration-replay tests and manual device acceptance remain required before release.
