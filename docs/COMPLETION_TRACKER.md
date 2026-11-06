# TACResQ completion tracker

**Updated:** 2026-11-07
**Measurement:** functional implementation coverage, not production readiness.

## How the 80% target is measured

The project tracks ten major functional capability groups at equal weight. A group counts as verified only when there is implementation in the repository/review branches and relevant CI evidence. Work that is still waiting for its latest CI run is not counted. This percentage does **not** mean the system is deployed, live-tested, or ready to operate real emergencies.

## Capability status

| # | Capability | Status | Evidence / remaining gate |
|---:|---|---|---|
| 1 | Authentication, session handling, and role-based routing | Verified in CI | Android source/build checks; live auth redirect still needs non-production verification |
| 2 | Requester emergency lifecycle | Verified in CI | Existing requester/API tests and Android build |
| 3 | Dispatcher queue, assignment, and team workflow | Verified in CI | Dashboard integration and PostgreSQL replay |
| 4 | Responder/driver assignment and status workflow | Verified in CI | Android JVM tests and migration replay |
| 5 | GPS map, location permission, and foreground lifecycle | Verified in CI | Lifecycle/source tests; physical GPS behavior remains unverified |
| 6 | Supabase schema, RPC authorization, and direct-table privilege boundaries | Verified in CI | Clean PostgreSQL replay, RPC grant checks, and migration-history checks |
| 7 | Web dispatcher/Laravel dashboard integration | Verified in CI | Laravel/dashboard integration job |
| 8 | Persistent in-app notifications, Android inbox, and FCM sender foundation | **Verified in CI** | FCM client registration and trusted HTTP v1 sender merged in PR #268; live worker/device delivery remains unverified |
| 9 | Admin role, dashboard, and audit foundation | **Verified in CI** | Android Admin access is removed; protected server-side Admin foundations remain. Live operator provisioning still needs non-production validation |
| 10 | Incident-scoped chat | **Verified in CI** | Android and backend chat paths are merged; live authorization and attachment behavior still need device/backend validation |

## Current result

**10/10 functional capability groups = 100% implementation coverage.**

This score reflects repository implementation and CI evidence only. It is not a claim of production readiness. FCM delivery, GPS behavior, routing, offline recovery, and emergency-service operation still require live and physical-device validation.

## Release gates still outstanding

- Keep new PRs blocked until their current-head checks are green and the deployed schema is reconciled with the repository migration history.
- Live Supabase migrations require an explicit change record and reviewed rollback plan; the notification and FCM ledger migrations are now applied to the connected TACResQ project.
- Verify email-confirmation redirects, session renewal, and role assignment in a non-production Supabase environment.
- Test actual GPS permission changes, foreground/background transitions, incident creation, notification refresh/read state, chat access, and retry behavior on physical Android devices.
- Run an end-to-end role matrix: requester, assigned responder, assigned driver, dispatcher, unrelated account, and Admin. Admin must not inherit incident/chat access by default.
- FCM client registration, trusted sender code, and the delivery ledger are implemented. Render worker execution and physical-device push delivery remain release gates.
- Preserve incident privacy: notification IDs are navigation references only; every incident/chat access must be reauthorized server-side.

## Next work

1. Reconcile remaining live Supabase migrations and verify deployed RPC/schema definitions.
2. Run the Render FCM worker configuration check and one synthetic device delivery test.
3. Perform non-production role-matrix, GPS, notification, chat, routing, and weak-network validation on a physical Android device.
4. Reassess release readiness; do not equate green CI with real-world emergency-service readiness.
