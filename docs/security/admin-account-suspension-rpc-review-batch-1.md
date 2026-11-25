# Account suspension RPC review — batch 1

**Branch:** `security/admin-account-suspension`  
**Review basis:** effective function definitions in the current repository migration chain, checked against the clean-replay catalog snapshot.  
**Scope:** requester incident lifecycle, field claiming, dispatcher claimability, chat attachments, FCM token registration, and the protected Admin investigation feed.  
**Status:** source review only. No suspension column, enforcement helper, policy, or UI is introduced by this document.

A function is not considered suspension-safe merely because it checks `auth.uid()`, a role, duty status, incident ownership, MFA, or chat membership. Those controls answer different authorization questions from whether the account is currently active.

## Findings

| Callable entry point | Existing checks observed in effective source | Account-state finding | Enforcement point required |
|---|---|---|---|
| `api_create_incident_v4(...)` | Requires authenticated UID; locks the caller's profile; requires requester role; uses a requester/client-request idempotency key; delegates incident creation in the same transaction. | **No current account-state check.** A suspended requester with a still-valid JWT would still pass the current identity/role gate if the role remains requester. | Check current account state after resolving and locking the profile, before creating or returning a saved SOS response. Ensure retry/idempotency does not return protected incident data to a suspended caller. |
| `api_cancel_my_incident(bigint)` | Requires UID; locks caller profile; requires requester role; locks the owned incident; permits cancellation only while pending. | **No current account-state check.** Ownership and pending-state checks do not reject a suspended requester. | Check current account state before returning or mutating incident data. Preserve the profile → incident lock order used by this RPC and test races with suspension. |
| `api_claim_incident(bigint)` | Requires UID; resolves responder/driver; locks incident, then locks/re-reads profile; requires responder/driver role and available duty status; checks existing assignments, active work, team capacity, and incident lifecycle. | **No current account-state check.** Duty status is independent and must not be repurposed as suspension. | Check account state after the locked profile re-read and before assignment insertion. Preserve incident → profile lock order and team-capacity/active-assignment invariants. |
| `api_claimable_incidents()` | Requires UID; reads profile role and duty status; returns no results to non-field or unavailable users; excludes already-assigned units and full/closed incidents. | **No current account-state check.** A suspended field unit could still receive incident queue data if its role and duty status remain eligible. | Reject/return an empty result for suspended callers before querying incident data. Also ensure every direct read path to the same queue is guarded. |
| `api_set_incident_claimable(bigint,boolean)` | Requires UID; prechecks dispatcher role; locks incident; locks/re-reads dispatcher profile; enforces lifecycle allowlist; writes a claimability event transactionally. | **No current account-state check** for the dispatcher actor. | Check active account state after the locked actor re-read and before mutation. Do not weaken the incident → dispatcher-profile lock order. |
| `api_send_incident_image_message(...)` | Resolves profile from UID; validates attachment metadata and canonical object path; verifies the actual Storage object MIME and byte size; checks incident-chat membership; serializes idempotency/rate limits; inserts message. | **No current account-state check.** Successful object upload alone does not authorize a suspended caller to finalize/send a message. | Guard before object verification/message insertion. Storage INSERT and SELECT policies need separate account-state checks; a message RPC guard alone cannot block object upload/download. |
| `api_my_incident_messages(bigint,integer,bigint)` | Resolves profile from UID; validates page parameters; checks incident-chat membership; returns bounded message history and attachment metadata. | **No current account-state check.** A suspended member could still read chat history using a valid JWT. | Guard before membership check and data retrieval. Preserve bounded pagination and membership checks. |
| `api_register_fcm_token(text)` | Requires UID; validates token length; resolves profile from UID; upserts only the caller's installation token. | **No current account-state check.** It does not send a push or choose recipients, but a suspended account can still register/update a token. | Reject suspended callers before upsert. Review unregister separately; it may need to remain callable for cleanup/sign-out even when suspended, subject to strict self-ownership. |
| `api_admin_list_investigation_events(...)` | Calls the shared Admin MFA helper before validating filters and reading the bounded, allowlisted investigation feed. | **MFA and Admin role are not account-state enforcement.** The shared helper currently checks UID, AAL2, TOTP AMR, and Admin role, but has no account-state predicate. | Extend the trusted Admin authorization boundary to require an active Admin. Keep TOTP/AAL2 and the restrictive audit-log RLS policy. |
| `private.tacresq_auth_profile_id()` and `private.tacresq_auth_can_access_incident_chat(bigint)` | Both are SECURITY DEFINER helpers with authenticated EXECUTE grants; they derive the caller from `auth.uid()` and return profile identity or chat-access result. | These are directly callable private entry points, not merely internal implementation details. Their current return values do not establish account activity. | Include them in the callable-private-function inventory. Prefer a shared active-account guard inside the trusted helper chain, and test direct authenticated invocation as well as invocation through public RPCs. |

## Important distinctions

1. **Role is not account state.** Keep requester/responder/driver/dispatcher/Admin role unchanged when suspending an account.
2. **Duty status is not account state.** Do not set a responder or driver to `off_duty` as a substitute.
3. **Incident assignment is an operational safety guard.** A field unit assigned to active work must not be suspended through the normal Admin flow until dispatch safely resolves/reassigns the work.
4. **Storage is a separate authorization plane.** The incident-chat object policies must check current account state for both upload and read; finalization must also check it.
5. **Valid JWTs remain relevant.** Negative tests must retain the caller's existing access token after the account-state transition and prove that Postgres/RPC/Storage reject the request.
6. **Lock order must remain explicit.** Add state checks without introducing profile/incident lock inversions against claim, assignment, cancellation, and claimability workflows.

## Next review batches

- **Batch 2 — Admin and audit:** all Admin RPC wrappers, internal functions, role-change/analytics/audit pagination, and direct audit table RLS.
- **Batch 3 — Dispatch and field operations:** assignment, release, status transitions, responder location reads/writes, duty-state operations, and their private implementations/triggers.
- **Batch 4 — notifications and profile:** profile reads/updates, notification RPCs, FCM unregister, device/session/security RPCs, and direct RLS grants.
- **Batch 5 — Storage and bridge:** all Storage buckets/policies, Android direct PostgREST/Storage calls, web direct RPC calls, and Laravel bearer-token bridge endpoints.
- **Only after all batches:** implement a single account-state contract with tests covering current and still-valid JWTs, concurrency, audit atomicity, and operational continuity.

## Source files inspected for this batch

- `supabase/migrations/20261023031810_requester_single_active_and_cancel.sql`
- `supabase/migrations/20261122050813_harden_field_claim_exclusivity.sql`
- `supabase/migrations/20261122050818_harden_dispatch_claimability_lifecycle.sql`
- `supabase/migrations/20261114153550_fcm_token_account_switch.sql`
- `supabase/migrations/20261108092703_incident_chat_image_attachments.sql`
- `supabase/migrations/20261122050858_require_totp_for_admin_rpcs.sql`
- `supabase/migrations/20261122120710_admin_investigation_events.sql`

No connected Supabase project was read or modified for this source review. This is a repository finding, not a claim that a live project has these exact deployed definitions.
