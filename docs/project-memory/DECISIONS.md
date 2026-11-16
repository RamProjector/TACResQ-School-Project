# TACResQ Durable Decisions and Constraints

**Last reconciled:** 2026-11-16  
**Source of product truth:** [Canonical product scope](../PRODUCT_SCOPE.md)

This file records decisions that should survive new development sessions. A new decision should include its date, rationale, and the affected system boundary.

## Product identity and safety

1. TACResQ is a general-purpose emergency GPS assistance and incident-response coordination school project, with a geographic focus on Tacloban City, Leyte, Philippines.
2. TACResQ is not LifeLink and is not a blood-donor application. Do not transfer LifeLink's donor terminology, user roles, or workflows into TACResQ.
3. TACResQ is an educational/engineering prototype, not an emergency-services provider. It must not claim guaranteed response, official agency adoption, emergency-service certification, or replacement of local emergency hotlines.
4. Location, assignment, delivery, arrival, ETA, and resolution must only be shown as confirmed when supported by the relevant server/device evidence.
5. Distinguish one-time requester GPS capture, manual location selection, device-only map preview, and consented foreground responder location reporting. Do not label these as continuous/background tracking.
6. Routing output is a static route preview, not guaranteed road passability, turn-by-turn guidance, flood/closure awareness, emergency-vehicle access, or a guaranteed ETA.

## Roles and authority

- Requester submits and manages their own permitted incident workflow.
- Responder/driver acts only on incidents to which the account is individually assigned or has valid claim authority.
- Dispatcher reviews the queue and coordinates assignments.
- Administrator functions are limited to the implemented server-side user-management and audit operations. Do not infer broader operational authority from the role name.
- A multi-member incident assignment is not a standing roster. The legacy `assigned_to` field is not proof of a formally appointed team leader.
- Per-responder assignment status is authoritative for individual progress. Chat messages are not a substitute for server-side assignment/status transitions.

## Architecture and data authority

1. Supabase Auth and Postgres RPCs are the primary Android data path. The in-memory fake backend is for explicit demo/testing use, not production data.
2. Supabase is the source of truth for TACRU dashboard authentication, incident/unit reads, and assignment RPCs. Laravel hosts the dashboard and its runtime configuration; its temporary PostgreSQL database is not the active source of truth for the Supabase-backed flow.
3. Use Supabase publishable keys in clients. Never place service-role/secret keys, Firebase service-account files, or other server secrets in Android, dashboard assets, or source control.
4. Keep authorization in server-side role/ownership/assignment checks and restrictive RLS/grants. Do not solve an advisor warning by adding broad client policies or grants.
5. Prefer additive, forward-only migrations. Do not rewrite applied migrations, drop application RPCs, delete production data, or alter production settings without explicit approval and a reviewed rollback plan.

## Notifications and emergency reliability

1. Persistent in-app notifications are the authoritative record. FCM is supplementary and best-effort.
2. FCM must not be described as guaranteed or life-critical delivery. Firebase's current guidance explicitly excludes life-critical use cases from its intended service scope.
3. The FCM delivery endpoint/ledger implementation does not mean scheduled production dispatch is active. Production activation requires explicit operator approval and verified secrets, extensions, schedule, and test-device behavior.
4. Do not enable high-priority FCM or switch to auto-rendered notification payloads without a separate security/privacy review and physical-device tests. The client currently validates the intended recipient before display.
5. Never send real emergency incidents as test notifications. Use dedicated synthetic accounts and devices.

## Location and routing privacy

1. Requester GPS is an optional one-time fix attached to the incident. A no-GPS path must remain available where the workflow supports a manual pin or landmark.
2. Responder location sharing is consent-gated and foreground-only in the implemented flow. Do not add background tracking or request background location permission merely to improve map freshness.
3. Route preview is disabled by default on Android and by the backend kill switch. Enabling it requires provider configuration, privacy review, synthetic route tests, and explicit approval.
4. The backend currently selects Geoapify by default when routing is enabled; private OSRM is an explicit alternative. Do not silently fail over between providers because that would disclose coordinates to another recipient.
5. Do not store or claim the exact underlying GPS source (satellite/Wi-Fi/cellular) when Android's fused provider does not expose that provenance. The existing incident source field distinguishes broad capture mode, not provider hardware attribution.

## Development and delivery constraints

- Work one focused issue at a time where practical; research and review before implementation.
- Prefer small, reviewable PRs with regression coverage and an explicit evidence trail.
- Preserve existing stack and architecture unless a researched, tested reason justifies a change.
- Prefer free-tier-compatible solutions. Do not add paid services or recurring costs without approval.
- Do not require an OpenAI API key or introduce OpenAI API dependencies.
- Never expose credentials or real personal/emergency data in code, logs, screenshots, test fixtures, or PR descriptions.
- Do not claim a test, review, merge, deployment, migration, device validation, or production activation occurred unless exact evidence confirms it.
- CI compilation/static contracts are not physical-device acceptance. Manually validate GPS, Android permission, notification, background, and poor-network behavior on test hardware.
- Do not activate production FCM Cron, enable production extensions, change Render secrets, send live notifications, or deploy infrastructure without explicit approval.

## Decision maintenance

When a later user instruction changes one of these constraints, update this file with the date and retain the prior decision in a short history note rather than silently deleting context.
