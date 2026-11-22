# Incident and responder-location read-boundary review

**Scope:** Repository migration and caller review on the Admin/security work branch. This is a source review, not a live Supabase catalog attestation or deployment verification.

## Executive result

The inspected incident and responder-location read paths have explicit role/relationship boundaries. No confirmed cross-user incident or GPS disclosure bypass was identified in the reviewed definitions. The most sensitive GPS reader is dispatcher-only; the field-claim queue is intentionally broader and returns emergency coordinates to eligible on-duty responders/drivers so they can decide whether to claim.

This review does not establish that the migrations are applied to any connected Supabase project.

## Read surfaces

| Surface | Execution model | Intended access boundary | Data returned |
|---|---|---|---|
| api_my_incidents() | SECURITY INVOKER | Requester, legacy lead unit, or assigned team member, additionally constrained by incident and assignment RLS | Incident report, coordinates/location metadata, requester contact fields, assignment membership/progress |
| api_all_incidents() | SECURITY INVOKER | Explicit current dispatcher-role check plus underlying RLS | Dispatcher incident queue, report/location data, requester contact fields, team progress |
| api_claimable_incidents() | SECURITY DEFINER | Authenticated responder/driver with available duty status and no active assignment | Claimable pending/assigned incidents, including exact coordinates and report details |
| api_active_responder_locations() | SECURITY INVOKER wrapper to private SECURITY DEFINER reader | Private reader requires current dispatcher role | Latest valid location for each assigned unit whose assignment and incident are both en route |

## Controls confirmed in repository definitions

- Incident SELECT RLS includes requester ownership, legacy assigned_to, multi-member assignment membership, or dispatcher role.
- The assignment table has a participant/requester/dispatcher SELECT policy. Client roles have SELECT only; mutations are routed through reviewed assignment RPCs.
- api_my_incidents() and api_all_incidents() are invoker functions, so their queries remain subject to caller RLS. The dispatcher queue also performs its own role check.
- The claimable queue fails closed to an empty result for non-field roles, off-duty users, and field units already assigned to an active incident. It limits candidates to pending/assigned incidents, excludes incidents the caller already joined, and accounts for a legacy lead assignment when enforcing the five-member cap.
- The active GPS reader checks dispatcher role inside its private privileged implementation. It returns only locations for current en-route incidents and en-route team members, with coordinate/accuracy validation and a two-minute freshness window in the inspected reader migration.
- GPS insertion is routed through a private SECURITY DEFINER writer. It checks the authenticated responder/driver identity, team membership, both incident and member en-route states, coordinate/accuracy/timestamp bounds, and an eight-second server-receipt rate limit. Direct client INSERT on location_logs is revoked.
- The public location writer and reader wrappers have explicit authenticated EXECUTE grants and PUBLIC/anon revocations. The private helpers have explicit authenticated EXECUTE grants and PUBLIC/anon revocations.
- private.tacresq_is_incident_assignee(bigint) now has a forward migration pinning its SECURITY DEFINER search path to pg_catalog; its body uses schema-qualified relations and auth.uid().

## Privacy and operational trade-offs

The claimable queue deliberately discloses precise emergency coordinates and report details to all eligible on-duty field units, not only units already assigned. That is a product-level dispatch/volunteer-claim decision, not an accidental consequence of the incident RLS policy, because the queue function is SECURITY DEFINER. Retain this behavior only while field self-claiming is an approved workflow. If that workflow changes, narrow the queue payload and access boundary together; do not silently remove coordinates needed to assess a response.

The dispatcher GPS endpoint is appropriately narrower than the incident queue: it exposes only current en-route unit points. The Android map also filters stale points for presentation, but client-side freshness filtering is not a substitute for the server-side two-minute bound.

The claimable queue is currently unpaginated. If incident volume grows, add deterministic bounded pagination and a continuation contract without silently truncating emergencies or omitting urgent requests.

## Follow-up controls

1. Keep negative authorization tests for requester, unrelated responder, assigned responder, off-duty responder, and dispatcher across each read surface.
2. Keep GPS tests for assignment removal, member status leaving en route, incident status leaving en route, stale capture time, future capture time, invalid coordinates, and rate-limit contention.
3. Keep the private GPS reader/writer unreachable to anon and PUBLIC; preserve exact wrapper signatures and grants during future refactors.
4. Consider an explicit product decision on whether every eligible on-duty unit should see full coordinates before claiming, or whether the initial queue should expose approximate location and reveal exact coordinates after claim.
5. Add pagination only with a completeness/ordering contract and dispatcher safety review.

## Evidence boundaries

Reviewed source files include the multi-responder assignment migration, per-responder status and location migrations, location writer/reader isolation migrations, claim exclusivity and claimability lifecycle migrations, and operational table privilege restrictions. The review did not execute SQL, query live catalog state, apply migrations, inspect production incident rows, or verify deployed behavior.
