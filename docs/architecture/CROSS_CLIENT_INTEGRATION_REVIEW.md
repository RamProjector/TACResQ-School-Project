# TACResQ cross-client integration review

**Review date:** 2026-11-22  
**Scope:** Android requester/responder/dispatcher flows, Laravel backend, Supabase data and authorization, chat/storage, notifications/FCM, and Admin web portal.  
**Evidence boundary:** This is a source-repository review. It does not establish that the connected Supabase project, Render deployment, Firebase sender, or physical Android devices behave identically to the checked-in source.

## Product and client boundary

TACResQ is an emergency-response Android application supported by Supabase Auth/Postgres/RPC and a Laravel service. The browser frontend is an Admin portal. Dispatcher operations belong in the Android application; dispatcher web access is intentionally denied. Laravel remains a backend service and must not be described as a dispatcher dashboard.

## Cross-client workflow inventory

| Workflow | Android client | Backend/data authority | Important boundary |
|---|---|---|---|
| Requester SOS | Staged request, explicit location capture/manual pin, encrypted draft and pending idempotency envelope | Authenticated incident-creation RPC and database constraints | An uncertain submit outcome must retain the original idempotency identity; do not create a replacement SOS automatically |
| Requester edit/cancel | Owner-facing edit and cancellation actions gated on fresh status | Owner-scoped RPCs, row locks, status/assignment guards, edit history | Actions are restricted to pending/unassigned incidents; stale cached status is not authority |
| Responder/driver duty and assignment | Shared field-unit workflow, per-assignment status, explicit duty control | Assignment and duty RPCs, incident membership/history | Duty availability is distinct from account enabled/suspended state |
| Dispatcher operations | Android queue, unit availability, team assignment, progress and closed-history views | Dispatcher-authorized RPCs and server-side transition guards | Do not restore a dispatcher web console |
| GPS sharing/navigation | User-started foreground location service, approximate responder fixes, explicit stop/resume, optional route preview | Authenticated location-write RPC and assignment/state validation | Sharing is consent-gated and assignment-scoped; routing is optional and does not replace location sharing |
| Incident chat | Incident-scoped text and image attachments, paging and retry | Membership-checked message APIs and private Storage policies | Attachment objects are stored separately from message records; Storage access must follow incident membership |
| Notifications | Authenticated inbox, Realtime refresh hints, periodic inbox refresh, push recipient validation | Caller-scoped notification RPCs and delivery ledger | Inbox is authoritative; push is supplemental and does not prove receipt or acknowledgement |
| Administration | Admin-only web portal for user governance, security, analytics and audit | AAL2/TOTP-protected Admin RPCs and restricted audit access | Admin web portal is not the dispatcher interface |

## Confirmed source-level safeguards

### Requester reliability
- SOS creation uses a client request UUID and server idempotency. A retry with the same identity/payload returns the saved result; reusing the identity with different content is rejected.
- Requester drafts and unresolved SOS envelopes are encrypted and account-bound.
- A bounded offline status cache is informational only. Create, edit, cancel and message actions require a successful server refresh.
- Automatic offline replay of emergency writes is intentionally excluded because the requester's location and circumstances may have changed.

### Field-unit location
- Requester location is a one-time user-initiated fix; stale last-known location is not silently substituted.
- Responder sharing uses approximate location and a foreground service started through an explicit user action.
- The service periodically rechecks the authenticated user and the user's own en-route assignment. It stops when authorization, permission, location settings, or assignment conditions no longer permit sharing.
- Location writes are server-authorized and validated against assignment, incident state, coordinate/timestamp bounds, and rate limits.
- Optional routing can fail without stopping authorized location sharing.

### Chat and notification boundaries
- Chat attachment paths are constrained and private Storage policies prevent unrelated accounts from reading or uploading into another incident conversation.
- Notification Realtime broadcasts contain refresh hints, not trusted notification content; Android refreshes the authenticated inbox after joins/reconnects and on a periodic fallback.
- FCM token registration/removal is caller-scoped and account-switch aware.
- The FCM sender uses a server-only delivery ledger with bounded claims, leases, retries and terminal outcomes.

## Cross-system gaps and operational limits

### P0 — FCM automatic delivery is not active by default
The source contains the sender and a signed trigger endpoint, but the Render web-service startup does not run the sender continuously. The documented Supabase Cron + pg_net trigger is not enabled in the connected project. Do not describe automatic push delivery as operational. The in-app inbox remains the reliable application surface; FCM is best-effort until the trigger and device delivery are validated.

**Safe next step:** verify the already documented free-compatible trigger prerequisites in a non-production/staging context and plan operator-authorized activation. Do not add a paid worker or alter production secrets/settings without approval.

### P1 — Multiple transports create an observability blind spot
Android still calls several Supabase RPCs and Storage directly; the optional Laravel bridge is not the complete RPC/Storage surface. Those direct calls are not represented in Laravel's API-operation log. Therefore, Laravel telemetry must not be treated as a complete application activity or security audit.

**Safe next step:** maintain an explicit transport inventory and ensure security-critical events are recorded by trusted database/backend paths, not inferred from one HTTP logger.

### P1 — Legacy notification sender requires retirement evidence
A deployed `tacresq-notify` Edge Function was mirrored into the repository for review. It bypasses the canonical FCM delivery ledger and lacks some bounded/idempotent controls of the canonical sender. Repository search found no obvious tracked caller, but that does not prove there are no external invocations.

**Safe next step:** inspect invocation logs and external schedules over a representative period before disabling it. Do not enable both sender paths for the same event family.

### P1 — Live integration and device behavior remain unverified
Source contracts cannot prove:
- Supabase Realtime authorization and reconnect behavior in the deployed project.
- Firebase credential validity, scheduled invocation, Doze behavior, or actual push receipt.
- Android foreground-service behavior across OEM battery restrictions, process death, permission changes, and navigation.
- Storage object access and upload/download behavior against the deployed bucket policies.
- End-to-end requester-to-dispatcher-to-field-unit coordination on real devices.

These are release verification gaps, not permission to claim failure or success.

### P2 — User-facing failure recovery needs end-to-end review
The code contains retry, stale-state, offline, permission, and service error handling. A remaining system-level task is to ensure the messages and recovery actions are consistent across requester, dispatcher, responder/driver, chat, and notification destinations. In particular, notification taps to incidents outside a bounded queue must fail closed until an ID-scoped authorized detail route exists.

## Prioritized whole-system work

1. Preserve the Android-only dispatcher workflow and Admin-only web portal boundary.
2. Resolve the FCM activation/legacy sender operational plan without introducing paid services or duplicate delivery.
3. Build a transport-to-audit coverage matrix for Android direct Supabase, optional Laravel bridge, Laravel APIs, Storage, Edge Functions, and database triggers.
4. Review end-to-end incident coordination for stale queue, assignment conflict, location freshness, and terminal-state handling.
5. Review chat attachment lifecycle, upload failure/retry, membership revocation, and retention.
6. Review user-facing recovery and accessibility across every role, not only Admin pages.
7. Validate integration and device behavior only in an authorized test environment; do not treat source-level tests as physical-device evidence.

## Explicit exclusions

- No dispatcher web dashboard restoration.
- No paid infrastructure or service-plan change.
- No production migration, Edge Function disablement, secret rotation, cron activation, or live incident mutation.
- No claim that CI, device testing, Firebase delivery, or live integration passed as part of this source review.
