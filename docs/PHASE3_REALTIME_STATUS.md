# Phase 3 readiness status

- [x] Phase 2 safe offline read synchronization merged in PR #275.
- [x] Persistent in-app notifications and FCM sender foundation exist.
- [x] Existing polling remains the documented fallback and source-of-truth refresh path.
- [x] Choose and implement one authenticated notification/assignment broadcast signal.
- [x] Add server-side publication/channel authorization and guarded migration replay coverage.
- [x] Add reconnect reconciliation and explicit connection-state UI while retaining polling fallback.
- [x] Validate duplicate/out-of-order/revoked-membership behavior with synthetic JVM data.
- [ ] Run staging and physical-device background/foreground notification tests.

The migration and Android transport are implemented. Synthetic duplicate, ordering, and membership-revocation behavior is covered by `NotificationsViewModelTest`; this does not claim live Supabase authorization, realtime delivery, Firebase receipt, or operational notification readiness until staging and device gates pass.
