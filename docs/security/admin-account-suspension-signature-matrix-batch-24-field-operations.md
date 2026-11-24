# Account suspension authorization matrix — batch 24: field operations

**Evidence:** clean-replay callable baseline and November 24 dispatcher guard migrations documented in the callable crosswalk.

| Exact callable / signature | Operation | Branch guard evidence | Remaining proof / decision |
|---|---|---|---|
| `api_assign_incident_team(bigint,bigint[])` | Assign multiple units | Public SECURITY DEFINER wrapper requires active dispatcher and profile `FOR SHARE`; delegates to preserved private implementation. This signature is included in the 23-function public definer inventory. | Replay exact authenticated-only grant, target-unit active checks, and no anon/PUBLIC execute; test concurrent suspension and assignment. |
| `api_assign_incident(bigint,bigint)` | Legacy one-unit assignment alias | Documented as routed through guarded team-assignment wrapper. | Verify exact signature/grant and test alias parity; no direct internal bypass. |
| `private.tacresq_assign_incident_team(bigint,bigint[])` | Private assignment implementation | Moved behind guarded wrapper; client EXECUTE is expected revoked. | Verify effective anon/authenticated EXECUTE false and inspect all trigger side effects. |
| `private.tacresq_release_incident_assignment(bigint,bigint)` | Release assignment | Guarded wrapper requires active dispatcher and profile share lock; preserved implementation is internal. | Verify intended actor role and emergency release semantics; test assignment/duty continuity. |
| `private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamptz)` | Field GPS write | Guarded wrapper requires active responder/driver and share lock; internal writer is not client-callable. | Test assigned-unit requirement, coordinate/time validation, throttling, and suspended same-JWT denial. |
| `api_update_incident_status(bigint,text)` | Dispatcher progress | Private implementation guard requires active dispatcher and share lock; public invoker remains a separate entry point. | Test status transition authorization and all assignment/duty synchronization branches. |
| `api_units()` | Field-unit directory | Public wrapper uses active dispatcher lock and delegates to a private implementation. | Test no roster/contact disclosure to suspended dispatcher; verify internal grant revocation. |
| `api_all_incidents()` | Dispatcher queue | Invoker path uses shared active-aware actor-role helper. | Test RLS/policy composition with suspended valid JWT and confirm no queue rows leak. |
| `private.tacresq_active_responder_locations()` | Active responder coordinates | Historical review identifies this private callable as dispatcher-only; no current guard proof is established in this matrix. | **Verify** direct grants, active dispatcher check, profile lock, and same-JWT read denial. |
| Duty-status mutation RPC (exact signature intentionally unresolved) | Duty state | Client operation map lists duty-status mutation as a distinct path, but the reviewed callable snapshot in this matrix does not establish its exact signature. | Resolve the exact signature from a fresh replay and trace its caller before classifying it; require active field-unit authorization without silently clearing duty during suspension. |

## Required test cases

- Active dispatcher assignment/release and active field-unit GPS succeed.
- Suspended dispatcher cannot assign, release, inspect roster, or read the incident queue.
- Suspended responder/driver cannot claim or write GPS using a still-valid JWT.
- A suspended unit cannot be selected as a new assignment target.
- Suspension races with assignment, release, GPS, and progress do not produce a post-commit unauthorized operation or orphan assignment.
- Internal implementations are not directly executable by anon/authenticated.

**Status:** Guard evidence is based on branch documentation/source contracts. Runtime replay and concurrency results remain outstanding. The duty-status row deliberately requires a fresh exact-signature lookup rather than guessing its SQL signature.
