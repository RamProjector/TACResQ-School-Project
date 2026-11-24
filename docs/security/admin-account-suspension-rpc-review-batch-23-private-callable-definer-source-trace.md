# Account-suspension private callable-definer source trace — batch 23

**Scope:** the eleven private `SECURITY DEFINER` functions that the clean replay reports as directly executable by `authenticated`. This is a source-level trace of current authorization assumptions, not a claim that suspension is enforced.

## Function-by-function findings

| Private helper | Current authorization behavior observed in source | Suspension gap / implementation requirement |
|---|---|---|
| `private.tacresq_current_account_active()` | Resolves the caller through `auth.uid()`, locks the matching profile `FOR SHARE`, and returns true only while the account is active. It is a directly authenticated-callable SECURITY DEFINER helper used by restrictive direct-table RLS policies. | Keep the exact authenticated-only grant and pinned search path. This helper protects direct table access but does not replace independent checks inside SECURITY DEFINER RPCs or Storage authorization paths. |
| `private.tacresq_auth_profile_id()` | Resolves `profiles.id` by `auth.uid()`. It does not check role, duty, or account state. Used by the chat authorization helper and incident-chat Storage policies. | Must not return a usable identity to a suspended account for protected chat/Storage operations. Preserve identity lookup only where a narrowly defined cleanup or suspended-state flow needs it. |
| `private.tacresq_auth_can_access_incident_chat(bigint)` | Resolves the profile through `tacresq_auth_profile_id()`; returns false only when no profile exists, then delegates to the shared incident-chat membership/access predicate. | A suspended profile currently remains eligible for the downstream membership predicate. Guard before delegation; also guard Storage read and insert policies so a client cannot bypass message RPC checks. |
| `private.tacresq_actor_role()` | Returns the caller's profile role for RLS and RPC role decisions. The inspected definition is a SECURITY DEFINER profile lookup by `auth.uid()`. | Returning `dispatcher`, `requester`, `responder`, or `driver` for a suspended profile can preserve authorization in policies and wrappers. Define an explicit suspended-account result and test every policy that consumes it. |
| `private.tacresq_is_incident_assignee(bigint)` | Returns whether the current authenticated profile is present in `incident_assignments` for the incident. Used by incident and assignment RLS policies. | Membership alone is not active-account authorization. A suspended assigned unit must not retain incident read/update access through this predicate. |
| `private.tacresq_assign_incident_team_guarded(bigint,bigint[])` | Authenticated-callable SECURITY DEFINER guard; resolves the dispatcher profile, requires `account_status = 'active'`, holds `FOR SHARE`, then delegates to the preserved SECURITY DEFINER internal implementation, which has authenticated/anon EXECUTE revoked. | Keep this as the only callable private assignment boundary; verify suspended actor denial and active target eligibility under the existing incident/profile lock order. The underlying `private.tacresq_assign_incident_team(bigint,bigint[])` remains SECURITY DEFINER but has authenticated/anon EXECUTE revoked. |
| `private.tacresq_dispatch_update_incident_status(bigint,text)` | Privileged dispatcher progress path. It checks the caller's dispatcher role and applies shared incident/team status progression. | Require an active dispatcher at the locked authorization boundary. Preserve incident and all-member status updates atomically. |
| `private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamptz)` | Derives caller from `auth.uid()`, requires a responder/driver profile assigned to the incident while it is `en_route`, validates coordinates/accuracy/timestamp, locks the incident and per-actor throttle, then inserts the location. | Add an active-account check before the location write. Do not weaken assignment, incident-state, freshness, accuracy, lock, or eight-second throttle checks. |
| `private.tacresq_active_responder_locations()` | Privileged location reader checks dispatcher role and returns the latest validated, fresh location for active en-route incidents/team members. | Require an active dispatcher before exposing live responder coordinates. Keep the existing en-route and freshness filters. |
| `private.tacresq_upload_reservation_allows_insert(text,text,bigint)` | Resolves the caller profile, then returns false if absent. When reservation enforcement is disabled, it returns true after that identity check; when enabled, it validates a live matching reservation and chat membership. | It has no independent account-state check. The Storage INSERT policy must reject suspended accounts before compatibility-mode bypass; preserve MIME/size/path and reservation validation. |
| `private.tacresq_release_incident_assignment(bigint,bigint)` | Resolves caller profile and requires dispatcher role; locks the incident and membership, prevents releasing the last unit, and performs a controlled team handoff. | Require active dispatcher before mutation. Do not auto-release suspended responders as a side effect of suspension; preserve dispatch continuity and require an explicit, audited reassignment/release workflow. |

## Transitive paths that must be tested

1. **Chat and Storage:** `api_my_incident_messages`, `api_send_incident_message`, `api_send_incident_image_message`, and `api_reserve_incident_image_upload` use profile resolution and/or chat membership checks. The Storage object SELECT/INSERT policies also invoke the private profile/chat helpers. A suspended account must be denied by both RPC and direct Storage access using the same still-valid JWT.
2. **RLS role/assignment paths:** policies call `tacresq_actor_role()` and `tacresq_is_incident_assignee()`. Guarding only public RPC bodies will not close direct PostgREST table reads or writes.
3. **Dispatcher mutations:** team assignment, progress update, and assignment release must each check active dispatcher state inside the privileged function, not rely only on a UI or invoker wrapper.
4. **Responder GPS:** the private writer must deny suspended responders even when their assignment and incident remain active. The reader must independently deny suspended dispatchers.

## Concurrency and operational requirements

- Preserve existing incident-row locks, assignment-row locks, and per-profile advisory locks. Suspension checks must occur inside the same transaction and before protected reads or writes where feasible.
- Suspension must not silently change duty status, incident status, team membership, or responder GPS consent.
- A suspended field unit may remain assigned until a dispatcher performs an explicit continuity decision. Any future release/reassignment must be audited and must not orphan an active incident.
- Test both a still-valid access token issued before suspension and a token issued after suspension. A successful refresh/re-login is not a substitute for same-JWT denial.
- Include direct RPC, direct table/RLS, Storage upload/download, and concurrent request tests.

## Validation boundary

This batch records source behavior and the guard locations required by the next implementation. It adds no account-state column, migration, runtime guard, UI, or live Supabase change. The eleven-function SQL inventory gate only ensures the callable surface does not silently drift; it does not prove authorization safety.
