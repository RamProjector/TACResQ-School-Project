# TACResQ app-flow validation and action-audit coverage

## Validation layers

| Layer | What it proves | What it does not prove |
|---|---|---|
| Python source-contract checks | Critical screens, RPC contracts, privacy boundaries, and test suites remain wired into CI | Runtime behavior |
| Android JVM tests | Repository/API policies, role rules, retries, notifications, and upload request ordering | Real Supabase or physical-device behavior |
| Android Compose instrumentation | Sign-in/register presentation, requester SOS UI, role navigation, active-request chat entry, and accessibility semantics | Real GPS, FCM delivery, backend acceptance, or panel acceptance |
| Clean PostgreSQL migration replay | SQL migrations, RLS/RPC contracts, notification behavior, claim/assignment flows, image reservation policy, and audit triggers execute against the replay schema | Supabase Storage HTTP binary transfer or production configuration |
| Isolated Supabase acceptance | Actual Storage API upload/download, RLS, and signed-in app behavior against a non-production project | Production rollout readiness by itself |
| Physical-device manual acceptance | GPS permissions/fixes, camera/photo picker, keyboard/insets, lifecycle, notification taps, and network-loss behavior | Broad population acceptance or institutional validation |

The standard pull-request CI runs source checks, JVM tests, Android debug/release builds, and clean PostgreSQL migration replay. The emulator UI workflow remains manually dispatched; UI automation is not treated as the only release gate.

## Current journey matrix

| Journey | Automated coverage |
|---|---|
| Sign-in and requester-only registration | `AuthScreenInstrumentedTest` |
| Requester creates one SOS and sees active request | `AuthScreenInstrumentedTest`, durable SOS/idempotency and requester SQL contracts |
| Requester navigation and active-request Messages entry | `NavigationInstrumentedTest` |
| Responder and driver claim eligibility, off-duty rejection | `FakeTacApiClaimIncidentTest`, `test_responder_incident_claiming.sql` |
| Dispatcher team assignment, release, handoff, and lifecycle | dispatcher assignment SQL tests and concurrency scripts in migration replay |
| Requester/assigned responder chat privacy and retry | `FakeTacApiChatTest`, `test_incident_chat_storage_policies.sql` |
| Image reserve → Storage upload → finalize and consumed retry | `SupabaseIncidentImageUploadTest`, `test_incident_chat_storage_policies.sql` |
| Notification ownership, deep links, read state, and recipient scope | `FakeTacApiNotificationTest`, `NotificationsViewModelTest`, `test_notification_rpc_security.sql` |
| GPS/map presentation and location-edit authorization | GPS presentation tests, location contracts, authenticated requester-location SQL test |
| Admin audit and investigation authorization/privacy | `test_admin_investigation_events_contract.py`, app-action audit SQL test, Admin MFA and grant checks |

The inventory guard `tools/test_app_flow_coverage.py` fails CI if required journey tests disappear or stop being included in the clean database replay.

## Action audit: what is recorded

The audit is for meaningful, server-confirmed actions that change application state—not every screen tap or navigation gesture.

The new metadata-only action ledger records:
- Incident chat message successfully sent (text/image classification only)
- Notification successfully marked as read
- Responder/driver duty status successfully changed

Existing append-only event histories continue to cover incident status/creation, responder assignment/release/team closure, claimability changes, requester incident edits (changed-field names only), and Admin role changes. Supabase Auth events appear when the Auth provider emits them into its audit log. Provider-operation logs remain separate.

Each new event stores a generated correlation UUID, actor profile and role when resolvable, action, target type and identifier, incident identifier when relevant, successful outcome, safe metadata, and server timestamp. The table is append-only from the application's perspective and cannot be read or forged directly by client roles. Admin investigation access continues to require the current Admin role plus AAL2 and TOTP.

### Deliberate privacy boundaries

Audit metadata does not store message bodies/captions, image bytes or object paths, passwords, access/refresh tokens, FCM tokens, precise coordinates, raw provider payloads, or request/response bodies. Location fixes are high-frequency sensitive data and remain in their dedicated restricted location store rather than being copied into a general audit feed.

### What is not yet recorded

This is not literal keystroke/click tracking. Read-only page views, map pans, ordinary UI navigation, local validation errors, and RPC failures that roll back their transaction are not added to this success-event ledger. PostgreSQL rolls back an audit insert made in the same failed transaction; logging denied attempts reliably needs a separate trusted logging boundary. Do not present absence of an event as proof that an action was never attempted.

Auth-provider audit coverage depends on the provider's configured event emission. FCM delivery is not yet a complete end-user delivery audit: provider acceptance is not device receipt.

## Release gates still requiring real-world verification

1. Run actual HTTP image upload and download against the isolated TACResQ test project using a signed-in reservation-capable app build.
2. Verify authorized and unrelated accounts against the live Storage API, including expired reservation denial and safe retry.
3. Exercise requester, responder, driver, dispatcher, and Admin flows on physical devices/browser with the correct test roles.
4. Verify real GPS permission denial/recovery, background/foreground transitions, photo picker, network interruption, push delivery, and notification deep links.
5. Keep `reservation_enforcement_enabled=false` during staged deployment so legacy clients retain upload-first finalization; enable it only after all supported clients use reservations and older builds are retired or blocked.

No production migration, production data, production Storage policy, or deployment is authorized by this test plan.
