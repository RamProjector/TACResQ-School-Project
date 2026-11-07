# Research: target-2 release and operational readiness

**Date:** 2026-11-07  
**Scope:** TACResQ target 2 before PR #275 merge  
**Status:** Research-only prerequisite for the release-readiness controls that follow

## Question

Which repository-level controls can be completed before target 2 is merged, and which claims still require private credentials, a deployed environment, or physical Android hardware?

## Findings

### FCM worker delivery

The repository already has a trusted server-side HTTP v1 sender, token ownership RPCs, invalid-token cleanup, and a deduplication ledger. The remaining repository-level gap is deployment discipline: the worker must be a separate private scheduled process with server-only Supabase/Firebase configuration, bounded execution, sanitized output, and a check-only command before delivery. A public web process must not receive the service-account file. The inbox remains authoritative; a push is only a hint to fetch authorized data.

Live Firebase credentials, Render worker execution, and device receipt cannot be verified from this sandbox and must remain release gates.

### Observability

The sender already prints aggregate counts and suppresses tokens, provider responses, narratives, and coordinates. Target 2 can document the minimum operational signals: worker run success/failure, notification/token counts, invalid-token cleanup, failed-attempt count, last successful run, and queue age. Logs must use aggregate values and a correlation/run identifier, never bearer tokens, service-account contents, FCM registration tokens, incident text, or coordinates. `/up` is a process-health check only; it is not proof of Supabase, FCM, or emergency-service readiness.

### Release signing

General CI may build an unsigned release artifact when signing secrets are absent so ordinary pull requests remain reproducible. A release-readiness workflow should be manual and fail closed unless all keystore secrets are present, decode a private keystore only on the runner, build the release APK, verify it with `apksigner`, and upload only the artifact and checksum. This proves the workflow is configured when run; it does not authorize distribution or prove Play policy compliance.

### Physical-device QA

A device matrix is required for Android permission, notification, weak-network, process-death, and role-flow behavior. The matrix must use synthetic accounts/incidents and record APK SHA, device/API, backend environment, expected result, observed result, and defect ID. CI/source contracts cannot mark these cases passed. The correct repository outcome before hardware access is **not run / blocked**, not “verified.”

## Implementation boundary

The following controls are safe to implement before merge:

- A protected manual signed-release workflow that fails closed when signing secrets are incomplete.
- A private FCM Render Cron Job example and operator runbook with check-only and rollback steps.
- An aggregate observability contract for FCM runs and a redaction checklist.
- A synthetic physical-device QA matrix with explicit not-run states.
- CI execution of the offline-sync source contract.

No live credentials, Render settings, Firebase project, Supabase data, real incidents, or physical devices will be modified or claimed as verified.

## Acceptance criteria

- Research commit precedes release-readiness implementation commits.
- Signed-release workflow never falls back to an unsigned artifact.
- FCM worker instructions keep credentials off the public web service and provide a check-only path.
- Observability guidance forbids sensitive payload logging and defines aggregate signals.
- QA matrix separates automated evidence from device/live-backend evidence.
- Merge remains blocked until current-head required checks are green.

## References

- `docs/FCM_SETUP.md`
- `docs/FCM_PUSH_DELIVERY_SECURITY_GATE.md`
- `docs/RENDER_DEPLOYMENT.md`
- `docs/COMPLETION_TRACKER.md`
- `app/build.gradle.kts`
- `.github/workflows/ci.yml`
- Android testing guidance: https://developer.android.com/training/testing
