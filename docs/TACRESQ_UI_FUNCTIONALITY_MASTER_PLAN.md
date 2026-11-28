## Section 73 — service-role default ACL and trusted writer inventory

### 73.1 Read-only catalog finding

A read-only catalog review found broad schema-scoped default ACL entries for `postgres/public` and `supabase_admin/public`. These defaults grant future-object privileges to `anon`, `authenticated`, and `service_role`, including table DML, sequence USAGE/SELECT/UPDATE, and function EXECUTE. The effective-privilege snapshot found that `service_role` had at least one DML privilege on **20 public tables**. This is a point-in-time catalog snapshot, not a statement that every role can access every row or that every grant is used by an active writer.

Default privileges apply to objects created by the relevant owner and are not retroactive. They must be interpreted alongside object ownership, RLS, function security mode, PostgREST exposed schemas, and the trusted application writer that actually uses each relation.

### 73.2 Trusted writer inventory

| Writer / path | Repository evidence | Boundary / decision |
|---|---|---|
| `ApiOperationAuditLogger` | Laravel metadata-only API-operation writer records bounded provider/operation/outcome metadata through the server-side Supabase bridge. | Retain the server-only credential boundary; no browser or Android service-role access. Confirm the exact table/column privileges required by the deployed writer before narrowing defaults. |
| `FcmNotificationDispatcher` | Laravel queue worker claims bounded delivery work, records per-token attempts, retry timing, lease state, and terminal outcomes. | Keep the queue/attempt-ledger path distinct from provider acceptance and device receipt. Preserve the required server-side read/write path while the exact relation/column map is verified. |
| `tacresq-notify` | A legacy active Edge Function uses a service-role client for direct FCM sends and does not use the canonical Laravel delivery ledger. | Do not add callers or enable duplicate delivery. Trace deployed callers before disabling or changing the function. |
| `api_record_location` | Legacy service-role location writer identified in the source/catalog review. | Keep separate from current v4/private SECURITY DEFINER location paths; verify callers and exact write requirements before changing grants. |
| Legacy incident v1/v2/v3 writers | Older service-role writer paths were identified and their public RPC EXECUTE paths are revoked by the reviewed branch migration. | Do not infer that the legacy paths are still callable merely because old source files remain. |
| Current incident v4/private SECURITY DEFINER paths | Current repository path uses protected private implementation and narrowly granted public entry points. | Preserve caller/session authorization and do not substitute blanket table grants for the RPC boundary. |

### 73.3 Why a blanket revoke is not safe yet

Do not revoke `service_role` from all public tables in one migration. The role is used by server-side API auditing, FCM queue/attempt processing, Storage and backup operations, and potentially external server consumers. The complete writer-to-table-to-column map and deployment caller topology must be established before changing effective privileges. The direct `service_role` INSERT on `public.incidents` remains under review; do not assume the old RPC EXECUTE path is still available.

### 73.4 Required integration proof

Column-only PostgREST PATCH behavior must be verified with an isolated disposable integration test before narrowing existing table-level grants. The test must prove the exact update-column allowlist, row filtering, trigger behavior, and server writer behavior under the intended role; catalog inspection alone is insufficient. Do not replace the existing broad grants until that integration test demonstrates that every required server-side path continues to work and unauthorized columns/rows remain inaccessible.

### 73.5 Deployment boundary

This section is an evidence inventory and staged least-privilege proposal, not an approved production migration. No hosted ACL, default privilege, table data, migration ledger, or deployment was changed during the review. Re-run the read-only catalog inventory and clean PostgreSQL 16/17 migration contracts after any future approved grant change. Obtain a complete writer map, owner approval, rollback plan, and hosted validation before rollout.

### 73.6 candidate least-privilege service-role matrix (not yet migrated)

| Candidate area | Required next evidence | Current disposition |
|---|---|---|
| API operation audit | Confirm `ApiOperationAuditLogger` writes only its minimized audit relation/columns and that failures remain fail-open. | Candidate for narrow writer privileges after exact column and PostgREST behavior tests. |
| FCM delivery | Trace `FcmNotificationDispatcher`, its claim RPC, token reads, delivery-attempt writes, and any scheduler/worker caller. | Preserve the canonical Laravel queue path; do not widen client roles or activate a duplicate Edge Function path. |
| Legacy notification Edge Function | Confirm every deployed caller and whether it overlaps the Laravel queue. | Keep under separate deployment audit; do not revoke grants or disable the function based only on repository source. |
| Location / incident writers | Reconcile legacy `api_record_location`, retired v1/v2/v3 RPCs, and current v4/private routines. | Keep old EXECUTE revokes; do not blanket-revoke table privileges until all server writers and external consumers are mapped. |

Required phrases for this gate are intentional: `ApiOperationAuditLogger`, `FcmNotificationDispatcher`, `tacresq-notify`, `column-only PostgREST PATCH behavior must be verified`, and `do not replace the existing broad grants until that integration test`.

---


## 75. Admin session inventory loading cancellation

**Observed source defect (2026-11-28):** `AdminAccountSessions.tsx` already has a request-generation cancellation guard. Closing the expanded inventory increments the generation, clears its page/error state, and sets loading false so a late Supabase RPC response is ignored. The header's View/Hide sessions button was nevertheless disabled whenever `loading` was true. This made the existing cancellation path unreachable during the initial request and left the panel impossible to close until the request settled.

**Decision:** Keep the session inventory read-only and make the disclosure control available throughout the request lifecycle. A user may close the panel while loading; the request-generation guard invalidates the pending result. Do not abort or revoke an Auth session as a side effect of closing the UI. Pagination and retry remain guarded against duplicate requests.

**Implementation and regression contract:** PR #370 removes only the loading-based disabled attribute from the disclosure button. `tools/test_admin_session_inventory_ui_contract.py` asserts that the disclosure button remains actionable while loading and that the collapse path increments the request generation, closes the panel, and clears loading state. Existing stale-response guards remain the authority for ignoring late results.

**Acceptance:** The Admin can close the session inventory immediately after opening it, including while the read is pending. Reopening starts a fresh first-page request; an earlier response cannot repopulate a closed or newly opened view. The change does not modify the RPC, grants, session semantics, or any operational Android/SOS flow.

**Validation boundary:** Static contract and exact-head CI are required. A successful build/source test is not a manual browser acceptance run. No hosted Supabase/Render change is authorized or required for this UI-only correction.
