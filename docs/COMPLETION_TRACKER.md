# TACResQ completion tracker

**Updated:** 2026-10-26  
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
| 8 | Persistent in-app notifications and Android inbox | **Verified in CI** | Backend PR #174 and inbox PR #175 full CI passed on their recorded heads; still draft/unmerged |
| 9 | Admin role, dashboard, and audit foundation | In review — not counted yet | PR #173 latest head has a CI run in progress; wait for all checks |
| 10 | Incident-scoped chat | In review — not counted yet | PR #176 backend passed on an earlier head; a concurrency fix needs a fresh run. PR #178 UI passed on an earlier head; latest cleanup changes need a fresh run |

## Current result

**8/10 functional capability groups = 80% implementation coverage.**

This is an implementation milestone across the current repository and draft review branches, not a claim that 80% of production readiness is complete. The notification capability is the eighth counted group because its backend and Android inbox both have complete CI evidence on the recorded heads. Admin and chat remain uncounted until their current heads pass.

## Release gates still outstanding

- Keep all PRs draft until their current-head checks are green and the overlapping notification PR diff is reconciled.
- Do not apply proposed migrations to live Supabase or deploy to Render without explicit approval and a reviewed rollback plan.
- Verify email-confirmation redirects, session renewal, and role assignment in a non-production Supabase environment.
- Test actual GPS permission changes, foreground/background transitions, incident creation, notification refresh/read state, chat access, and retry behavior on physical Android devices.
- Run an end-to-end role matrix: requester, assigned responder, assigned driver, dispatcher, unrelated account, and Admin. Admin must not inherit incident/chat access by default.
- Decide separately whether web notifications/chat or FCM push delivery are required. FCM is not implemented and is not counted as a core capability in this 80% metric.
- Preserve incident privacy: notification IDs are navigation references only; every incident/chat access must be reauthorized server-side.

## Next work

1. Finish the latest CI runs for PR #173, PR #176, and PR #178; fix any failure before promoting those drafts.
2. Reconcile the PR #174/#175 overlapping migration diff before any merge decision.
3. Perform non-production Supabase and physical-device end-to-end validation.
4. Reassess the metric after these gates; do not equate green CI with real-world emergency-service readiness.
