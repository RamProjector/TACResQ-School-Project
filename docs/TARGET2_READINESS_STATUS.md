# TACResQ target-2 readiness status

**Updated:** 2026-11-07  
**PR:** #275  
**Interpretation:** repository and CI readiness, not operational emergency-service readiness

## Repository controls completed in this PR

- Offline requester read synchronization has a research decision, a pure safety policy, and a unique constrained WorkManager refresh for the encrypted requester incident-status cache.
- Automatic sync rejects SOS, incident, location, chat, and notification mutations.
- Routing has an independent backend kill switch and staged rollback documentation.
- FCM has a private-worker deployment example, check-only configuration path, aggregate observability requirements, invalid-token cleanup guidance, and rollback instructions.
- A manual signed-release workflow fails closed unless all keystore inputs are present, verifies the APK with `apksigner`, and publishes a checksum.
- A synthetic physical-device QA matrix records the exact evidence needed for permissions, weak networks, process death, FCM, routing, role isolation, and release artifacts.
- Source contracts run offline synchronization and target-2 release-control checks in CI.

## Explicitly not verified by this PR

- Live Render Cron Job execution or schedule reliability.
- Firebase credential validity, FCM OAuth, push delivery, or a notification appearing on a physical device.
- Live Supabase role/RPC behavior beyond repository migration/integration checks.
- Physical GPS, notification permission, OEM background behavior, process death, airplane-mode recovery, map tiles, or live routing.
- A signed release artifact, unless an authorized operator manually runs the protected workflow with the real signing secrets.
- Privacy, retention, support, incident-response, or emergency-service acceptance by TACRU operators.

## Merge decision

PR #275 may merge only after its current-head required CI checks are green and the implementation review confirms that the controls remain fail-closed. Merging these controls does not enable live routing, FCM delivery, a production worker, or emergency-service operation. Those actions require separate authorized deployment and QA work using synthetic accounts/data.
