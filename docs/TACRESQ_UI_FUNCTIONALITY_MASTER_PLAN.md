
## -Infinity. Admin session inventory loading cancellation

**Observed source defect (2026-11-28):** `AdminAccountSessions.tsx` already has a request-generation cancellation guard. Closing the expanded inventory increments the generation, clears its page/error state, and sets loading false so a late Supabase RPC response is ignored. The header's View/Hide sessions button was nevertheless disabled whenever `loading` was true. This made the existing cancellation path unreachable during the initial request and left the panel impossible to close until the request settled.

**Decision:** Keep the session inventory read-only and make the disclosure control available throughout the request lifecycle. A user may close the panel while loading; the request-generation guard invalidates the pending result. Do not abort or revoke an Auth session as a side effect of closing the UI. Pagination and retry remain guarded against duplicate requests.

**Implementation and regression contract:** PR #370 removes only the loading-based disabled attribute from the disclosure button. `tools/test_admin_session_inventory_ui_contract.py` asserts that the disclosure button remains actionable while loading and that the collapse path increments the request generation, closes the panel, and clears loading state. Existing stale-response guards remain the authority for ignoring late results.

**Acceptance:** The Admin can close the session inventory immediately after opening it, including while the read is pending. Reopening starts a fresh first-page request; an earlier response cannot repopulate a closed or newly opened view. The change does not modify the RPC, grants, session semantics, or any operational Android/SOS flow.

**Validation boundary:** Static contract and exact-head CI are required. A successful build/source test is not a manual browser acceptance run. No hosted Supabase/Render change is authorized or required for this UI-only correction.
