# Account suspension authorization matrix — batch 23: requester lifecycle

**Evidence:** clean-replay public callable baseline plus branch migration/crosswalk descriptions. The table separates documented guard implementation from runtime proof.

| Exact public signature | Operation | Lifecycle guard evidence in branch | Remaining proof / decision |
|---|---|---|---|
| `api_create_incident_v4(text,text,double precision,double precision,real,text,timestamptz,text,uuid)` | Create/retry requester incident | The write-entry guard migration is documented as requiring active requester state and locking the profile before idempotent write handling. | Execute same-valid-JWT active/suspended tests; verify replay lookup cannot disclose an earlier result to suspended callers. |
| `api_cancel_my_incident(bigint)` | Cancel own incident | Same write-entry guard migration is documented with active requester state and a row lock. | Prove ownership, status transition, and audit behavior in disposable PostgreSQL; confirm no cross-user cancellation. |
| `api_update_my_incident(bigint,text,text,double precision,double precision,real,text,timestamptz,text,boolean)` | Edit own incident | Listed in the clean-replay callable baseline; no explicit lifecycle guard evidence is established by the cited requester write migration summary. | Treat as **verify**: inspect effective body, nested helpers, idempotency and ownership checks; add a guard or prove delegation to an active-aware helper. |
| `api_claim_incident(bigint)` | Claim incident | Claim entry is listed in the broader guarded callable scope. | Verify responder/driver role, active account, assignment eligibility, row-lock order, and same-valid-JWT denial. |
| `api_claimable_incidents()` | Discover claimable incidents | Read guard migration is documented as requiring account-aware caller resolution. | Verify suspended callers cannot enumerate incidents; run active positive and suspended negative tests. |
| `api_set_incident_claimable(bigint,boolean)` | Change claimability | Identified in the client operation map as a direct-only dispatcher RPC. | Treat as **verify** until the exact effective body and active-dispatcher lock are checked in the replay contract. |

## Required test cases

1. Active requester creates an incident and retries with the same idempotency key; exactly one incident is created and the retry returns only that requester's result.
2. Suspended requester with the same still-valid JWT cannot create, replay, edit, or cancel an incident.
3. A requester cannot edit/cancel another user's incident, whether active or suspended.
4. Suspension racing with create/edit/cancel serializes through the agreed profile/incident lock order.
5. No suspension test may silently change assignment, duty status, or historical incident rows.

**Status:** Source mapping only. This document does not claim that these tests have run.
