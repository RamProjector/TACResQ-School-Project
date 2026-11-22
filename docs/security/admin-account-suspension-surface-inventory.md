# Admin account suspension: preliminary authorization-surface inventory

**Branch:** security/admin-account-suspension  
**Status:** preliminary source inventory; NOT an exhaustive release checklist. No enforcement changes or remote operations are included.

This inventory is intentionally conservative. A path is not marked covered merely because it checks a role or because it uses RLS. Suspension requires an independent current-account-state check. Each row remains **not yet guarded** until an implementation and negative test prove that a suspended caller is rejected.

## Confirmed entry-point groups

| Surface | Repository evidence | Existing control | Suspension status |
|---|---|---|---|
| Current profile | public.api_me() in the core/profile migrations | Returns profile resolved from auth.uid(); current definition is invoker | Not guarded; must deny or return a clearly defined suspended state without exposing protected data |
| Role resolution | private.tacresq_actor_role() in 20261015081107_harden_rpc_exposure.sql | SECURITY DEFINER role lookup by auth.uid(); fixed-search-path hardening exists in later migration | Not guarded; role helper does not currently check account state |
| Incident creation and cancellation | api_create_incident_v4, api_cancel_my_incident and related versioned RPCs | Ownership/role checks and idempotency/active-request rules | Not proven; inspect final effective definitions and direct table grants |
| Incident read and update | api_my_incidents, api_all_incidents, api_update_my_incident, api_update_incident_status | RLS plus caller/dispatcher/assignment checks | Not proven; invoker and definer paths must be distinguished |
| Dispatcher assignment | api_assign_incident_team and private assignment implementation | Dispatcher role, target-unit eligibility, active-incident and locking checks | Not proven; suspended dispatcher and suspended target unit both require rejection |
| Field self-claim | api_claimable_incidents and api_claim_incident | Responder/driver role, duty state, active-assignment exclusion, row locks | Not proven; suspended unit must not see/claim and suspended caller must not mutate |
| Responder progress | api_update_my_assignment_status and private implementation | Authenticated field-unit identity, assignment membership, state-transition checks | Not proven |
| Location write/read | api_record_location_v2 and api_active_responder_locations private implementations | Role, assignment, incident state, coordinate/time/rate constraints; dispatcher-only reader | Not proven; location_logs RLS and definer paths both need review |
| Incident chat | api_my_incident_messages and image-message/finalization RPCs | Incident membership and sender identity; private Storage bucket and reservation validation | Not proven; message RPCs and Storage object read/write must reject suspended accounts |
| Notifications | api_my_notifications, api_my_notification, api_mark_notification_read | Recipient derived from auth.uid(); bounded and ownership-scoped | Not proven |
| FCM token registration | api_register_fcm_token and unregister counterpart | Token ownership derived from auth.uid(); client cannot choose profile ID | Not proven |
| Admin directory and role changes | TOTP/AAL2 wrappers for paginated user list and role changes | Current Admin role, MFA, target restrictions, transactional role audit | Not proven; suspended Admin must fail the Admin MFA helper/role wrapper |
| Admin analytics and investigation | api_admin_analytics_summary, api_admin_list_investigation_events, audit page wrappers | Current Admin checks; TOTP/AAL2 wrapper for investigation/audit surfaces | Not proven |
| Supabase Storage | storage.objects policies for incident-chat-attachments | Private bucket, path/profile ownership, MIME/size, incident membership, reservation check | Not guarded by an account-state check today |
| Laravel Supabase bridge | backend SupabaseBridge and route middleware; calls api_me and scoped RPCs | Verifies Supabase bearer token and profile/incident authorization on selected routes | Not proven; bridge must honor the database's current account state and fail closed |
| Browser Admin portal | backend/web/src/lib/supabase.ts and AdminConsole.tsx | Direct Supabase RPC calls; client is not a trusted authorization boundary | Not guarded; status UI must follow backend enforcement, never lead it |
| Android app | SupabaseTacApi and direct Supabase repositories | Direct Supabase calls for emergency workflows, chat, notifications, and location | Not guarded; direct requests must be rejected server-side, not only hidden in UI |

## RLS and privilege evidence that changes the implementation plan

- The clean migration replay for this branch reports 20 authenticated-callable public SECURITY DEFINER functions (72 authenticated-callable public functions total). See [the clean-replay authorization snapshot](admin-account-suspension-clean-replay-snapshot.md) for the exact signatures and [RPC review batch 1](admin-account-suspension-rpc-review-batch-1.md) for source-traced account-state gaps. This isolated replay count supersedes the older 18-function preliminary review; it is not a live connected-project catalog.
- Five RLS-enabled operational tables were reviewed as intentionally having no direct client policies/grants: fcm_delivery_attempts, incident_messages, incident_request_edit_logs, incident_submission_keys, and user_notifications. Their RPCs/server writers still need caller-state checks.
- Some policies use private.tacresq_actor_role(); others resolve ownership by directly selecting profiles for auth.uid(). Updating the role helper alone is insufficient.
- SECURITY DEFINER functions may bypass ordinary RLS. Each callable definer function needs a current-state guard inside its trusted body.
- Supabase Storage authorization is separate from ordinary Postgres table RLS and must be covered in the same release.
- The Laravel bridge is not the universal request path. Android and the web dashboard call Supabase directly, so Laravel middleware cannot provide complete suspension enforcement.

## Source-traced review progress

- [Batch 1: requester incident lifecycle, field claiming, claimability, incident chat attachments, FCM token registration, and Admin investigation feed](admin-account-suspension-rpc-review-batch-1.md). The reviewed functions currently lack an independent account-state check; each gap and the correct enforcement boundary is recorded there.
- Remaining batches must cover Admin/audit internals, dispatch and location operations, notifications/profile/device security, Storage, and every direct client/bridge path.

## Required completion artifacts

Before the implementation can be called complete, add and review:

1. An exact effective-RPC inventory from the full migration replay/catalog, including signature, execution mode, authenticated EXECUTE grant, current role/ownership guard, and account-state guard.
2. An exact RLS table/policy inventory showing every policy that grants a row to authenticated callers and the restrictive active-account condition.
3. An exact Storage policy inventory for every bucket/path used by the app.
4. A client call inventory from Android, web, and Laravel identifying every direct Supabase operation and its database enforcement point.
5. A machine-checked contract test that fails if a newly introduced authenticated SECURITY DEFINER entry point lacks the active-account check.
6. Disposable PostgreSQL tests using synthetic requester, responder, driver, dispatcher, and Admin accounts, including still-valid JWT contexts and concurrent suspension/action races.
7. Storage HTTP tests for suspended reads and uploads, plus Laravel bridge tests.
8. A documented rollback and reactivation path.

## Decision

Do not add the Suspend/Reactivate button yet. A UI control before complete server-side enforcement would create a false security promise. Continue with exact migration/catalog inventory and a testable enforcement design first.
