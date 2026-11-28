# Requester incident creation and editing authorization review

**Scope:** Source review of incident creation compatibility RPCs, requester edit RPC, single-active-request trigger, cancellation, assignment/status guards, edit history, and dispatcher edit notifications. No live database catalog or incident data was accessed.

## Creation boundary

- The current idempotent creation path `api_create_incident_v4` requires an authenticated requester, locks that requester's profile row, and ties the client request UUID to a normalized payload. Reusing the same UUID with different content is rejected; retrying the same payload returns the saved response.
- The database trigger serializes inserts on the requester profile row and rejects a second incident unless the previous one is resolved or cancelled. This applies to legacy creation RPCs too because it is an INSERT trigger, not only a check in the newest RPC.
- The pre-migration catalog retained authenticated EXECUTE on v1/v2/v3, but authenticated had no direct INSERT on `public.incidents`; these invoker functions were not a working client compatibility path. Current Android/Laravel use v4 only. PR #370 Section 72 revokes API-role EXECUTE on the old signatures while retaining v3 for the owner-executed v4 call. Keep the signatures; do not drop them without a separate historical-client review.
- Creation is authenticated and requester-role constrained in the newer RPCs. The oldest compatibility RPC is SECURITY INVOKER and its execute grant is restricted to authenticated callers.

## Edit boundary

- `api_update_my_incident` derives the requester from `auth.uid()`; it does not accept a requester ID from the client.
- It locks the caller's profile and the incident row, then verifies that the incident belongs to that requester.
- Editing is allowed only while status is pending, `assigned_to` is null, and no row exists in the normalized `incident_assignments` table. This last check protects against inconsistent legacy assignment state.
- It validates emergency type, text lengths, coordinate pairs/ranges, accuracy bounds, source class, and location timestamp freshness. A manually selected pin cannot claim GPS accuracy.
- Repeated edits are rate-limited by a 60-second cooldown and require explicit review confirmation. A location-only correction can bypass the cooldown when confirmed, which is a deliberate safety exception for an incorrect emergency pin.
- Each successful edit writes before/after values and an urgent-correction flag to `incident_request_edit_logs`. The edit log table has RLS enabled and direct client grants revoked; its precise coordinates and descriptions are therefore not exposed by direct table access in the reviewed source.
- A database trigger sends dispatchers a generic “emergency details updated” notification. It does not copy the description, phone number, or coordinates into the notification body.

## Cancellation and assignment race

- Requesters can cancel only their own pending incident. The cancellation RPC locks the incident row and rejects cancellation after assignment/progress.
- Assignment and cancellation serialize on the incident row; whichever operation obtains the lock and commits first determines the outcome.
- The incident update trigger independently constrains requester cancellation, dispatcher assignment/progress, and field-unit claim/progress. Field units cannot rewrite the original emergency report.
- The requester edit RPC and assignment workflow both lock the incident row, so an edit cannot silently race through after the incident becomes assigned. Recheck this behavior with concurrent integration tests before release.

## Data exposure and provenance observations

- Requester-facing responses include the caller's own emergency coordinates and contact details; dispatcher queue responses include requester contact details for dispatch. Assigned-unit responses include details needed for the assigned response. Keep these projections role-scoped and do not add them to aggregate analytics or general Admin listings.
- The `location_source` field currently records only a source class: `gps`, `manual`, or `none`. It does **not** identify the GPS/map provider, SDK, geocoder, or routing provider that supplied or processed a location. The client-reported capture timestamp is explicitly not proof of physical presence or GPS attestation.
- If provider attribution is a requirement, add a separately named, bounded provider identifier and capture it server-side where possible. Define allowed provider values, provenance semantics, privacy/retention, and whether the field records coordinate capture versus map display/geocoding/routing. Do not overload `location_source` or treat client-supplied provider text as trusted evidence.
- Edit history contains precise coordinates and emergency descriptions. Its current direct-client denial is appropriate, but retention duration, deletion policy, and a future narrowly scoped audit reader still need an explicit owner decision.

## Follow-up tests and release checks

1. Concurrent duplicate SOS submissions with the same and different client request IDs.
2. Concurrent requester edit versus dispatcher assignment; verify exactly one valid state wins and no assigned incident is edited.
3. Cross-user incident ID guessing; requester cannot read, edit, or cancel another user's incident.
4. Reject edits after assignment, including inconsistent legacy assignment rows.
5. Reject invalid type, oversized text, malformed coordinate pair, out-of-range coordinates/accuracy, invalid source, stale/future changed-location timestamp, and manual pin with GPS accuracy.
6. Verify the 60-second edit cooldown and confirmed location-only correction behavior.
7. Verify responders/drivers cannot alter original report fields and dispatchers can only perform allowed status transitions.
8. Verify edit history and raw location provenance remain inaccessible to ordinary client roles.
9. Confirm the mobile client still uses the intended creation RPC version before retiring compatibility signatures.

## Evidence boundaries

Reviewed migration source for incident creation and one-time location, requester single-active/cancellation, requester edit workflow and urgent location correction, latest requester location validation, dispatcher progress, and dispatcher edit notifications. No SQL was executed, no live catalog or user/incident records were queried, no migrations were applied, and no runtime concurrency test was performed.
## Account lifecycle boundary added November 24

The forward migration `20261124120000_account_lifecycle_requester_edit_guard.sql` now moves the existing SECURITY DEFINER edit body behind `private.tacresq_update_my_incident_internal(...)` and revokes client-role EXECUTE on that implementation. The public `api_update_my_incident(...)` signature and default confirmation argument remain available to authenticated clients.

The public wrapper first resolves the caller from `auth.uid()`, requires `account_status = 'active'` and requester role, and holds a `FOR SHARE` lock on the profile row while delegating to the preserved implementation. This serializes an edit with a lifecycle transition that updates the same profile row: if suspension commits first, the edit is denied; if the edit obtains the lock first, the edit completes before suspension can commit.

The private implementation retains the existing incident ownership/assignment checks, field validation, edit cooldown, confirmation requirement, and before/after edit-log insertion. This is a source/migration design claim only until the new migration and SQL replay contract are executed in a disposable PostgreSQL environment. It has not been applied to a connected Supabase project.