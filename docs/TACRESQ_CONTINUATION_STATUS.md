# TACResQ Continuation Status and Implementation Priorities

Last reviewed: 2026-10-25

This document is the durable handoff for future TACResQ work sessions. Read it before starting another feature so work continues from verified repository state rather than assuming unfinished features are complete.

## Product and engineering constraints

- TACResQ is intended to support TACRU emergency-response coordination; do not drift back into the old blood-donor matching concept.
- Preserve the existing Kotlin/Jetpack Compose Android app, Supabase Auth/Postgres RPC backend, and existing Render/Laravel adapter. Do not change stacks or add paid dependencies without explicit approval.
- Use a focused, reviewable change/PR at a time. Inspect the actual repository, research relevant Android/HCI/Supabase guidance where appropriate, and run the existing checks. Do not weaken tests just to make CI green.
- Merge only when the PR head is unchanged and all required checks pass.
- Do not make destructive backend/data changes, change service plans, or introduce paid services without asking.
- Keep the GPS map a principal, prominent app feature. Maintain the five-destination navigation and polished, accessible, responsive behavior. Never imply a stale or one-time location is live tracking.
- CI/builds are not equivalent to physical-device validation. Clearly report native Android emulator/device, GPS, permission, tile loading, and end-to-end backend scenarios as unverified until actually exercised.
- This remains an educational engineering project until operational acceptance and device/backend validation are complete; never tell users to rely on it as their sole emergency channel.

## Latest verified repository changes

- PR #165, “Reject future GPS timestamps before age truncation,” merged. It rejects future responder timestamps before whole-second age conversion and marks future incident timestamps for confirmation.
- PR #166, “Frame incident pins when they arrive after field units,” merged as commit `34e07367c9ef7396e6dd500ec565da7aab11f18a`. It separates initial field-unit framing from incident framing, so incident coordinates that arrive later can trigger one incident-aware frame without repeatedly recentering the dispatcher map.
- PR #166 head `3fb98a03e7dcf4d8419ae3f11f59aa0dbc8f75df` passed the TACResQ CI workflow (Android debug/release builds, source checks, navigation/functions/screenshots, Laravel/dashboard integration, and clean PostgreSQL migration replay), RPC grant contract, and TACResQ Frontend Review before merge.
- PRs #156–#164 were previously merged for GPS-map emphasis, empty states, responsive controls, accessibility announcements, overlapping location-request protection, startup session-restoration state, permission-revocation cleanup, and stale field-unit framing.
- Do not treat these map fixes as proof of real-device GPS/map/network behavior.

## Required feature audit — not yet complete

### 1. Distinct administrator role and authorization

The current app role enum and API documentation list requester, responder, driver, and dispatcher; they do not yet include a distinct admin role. Implement this as a separately authorized role only after auditing every role enum, profile constraint, signup/provisioning path, Android navigation, dashboard, Supabase RPC, RLS policy, and tests.

Security requirements:
- Public registration must continue to create requester-only accounts.
- Users must not self-promote by editing profile fields or user-editable JWT metadata.
- Admin provisioning and sensitive actions must be enforced server-side/database-side, not just hidden in the UI.
- Define the admin's exact powers and keep dispatcher operational duties distinct from account/security administration.
- Add positive and negative authorization tests for every privileged operation. Never use a service-role/secret key in the Android app or browser.
- Do not grant broad database access merely because a user has an admin label.

### 2. In-app notifications, then push notifications

The repository documentation says FCM push notifications are not implemented. First audit whether any partial notification schema/RPC exists in the current migrations, then design the missing pieces without duplicating existing contracts.

Expected behavior:
- Persist important events such as incident status changes, assignments/reassignments, and messages.
- An authenticated user can only read their own notifications; dispatcher/admin visibility must be explicitly scoped.
- Include read/unread state, timestamps, relevant incident deep links, and meaningful empty/loading/error states.
- Use an outbox/retry-safe delivery design so transient network/provider failures do not lose the underlying in-app notification.
- Treat FCM push as a delivery channel, not the authoritative notification store. Handle permission denial, token rotation/removal, sign-out, and duplicate delivery.
- Do not add paid infrastructure or expose server secrets. Check current Firebase/Supabase documentation before implementation.

### 3. Secure in-app incident chat

No complete, verified in-app chat feature has been established yet. Audit migrations and app code before designing tables/screens.

Expected behavior:
- Incident-linked conversations; only the requester and explicitly assigned/authorized response participants can access the relevant thread, according to the agreed workflow.
- Enforce membership and read/write permissions on the server/database with RLS/RPC; never rely on client-side hiding.
- Persist messages, author, timestamp, incident/thread association, and delivery/read state where required.
- Handle loading, empty, offline/send failure, retry, duplicate sends, and stale/revoked membership.
- Avoid exposing unnecessary personal data, and retain an audit trail appropriate to emergency coordination.
- Add tests proving non-participants cannot read or post to another incident's thread.

## Suggested order of work

1. Finish the current GPS/map regression queue and keep the working tree/PR state accurate.
2. Audit the current Supabase migrations, role enum/profile constraints, dashboard authorization, and all role-checked RPCs; produce a privilege matrix before adding the admin role.
3. Implement the admin role in a focused migration + client/dashboard updates + security tests, only after the privilege model is explicit.
4. Design and implement persistent in-app notifications and event creation with RLS/RPC tests.
5. Add FCM push as an optional delivery channel using free-tier-compatible configuration where feasible.
6. Implement incident-linked chat with membership security, persisted messages, and offline/error handling.
7. Run integration tests and perform native Android emulator/physical-device QA when available: authentication/redirect, role isolation, GPS permission grant/revoke, no-location SOS, map tile/network failure, delayed incident/unit data, notification permission/token lifecycle, chat authorization, sign-out, and reassignment.
8. Continue screen-by-screen HCI and Android lifecycle review without replacing the map-first emphasis or making unsupported “live” claims.

## Reporting standard

For each continuation, state what was actually inspected/changed, provide the PR/commit link, list passed and failed/pending checks, and name any device/end-to-end tests not run. A planned feature is not an implemented feature; a passing build is not end-to-end validation.


## Live Supabase audit observations (read-only, 2026-10-25)

The connected Supabase project was inspected with read-only schema, migration, and advisor queries. No production database changes were made.

- The live `public.profiles.role` constraint currently permits only `requester`, `responder`, `driver`, and `dispatcher`. An admin role is not yet supported by the live schema.
- The live public schema has `fcm_tokens`, but no table whose name indicates notifications, messages, chat, or conversations. FCM token storage alone is not push notification delivery or an in-app notification system.
- The live project currently has 23 recorded migrations through `20261023103737_per_responder_assignment_status`.
- Supabase security advisors currently report two RLS-enabled tables with no policies: `incident_request_edit_logs` and `incident_submission_keys`. No policies means direct table access is denied under RLS, which may be intentional if access is exclusively through carefully constrained RPCs; verify actual intended access and RPC behavior before changing it.
- Security advisors also flag five authenticated-callable `SECURITY DEFINER` RPCs: `api_cancel_my_incident`, `api_create_incident_v4`, `api_record_location_v2`, `api_units`, and `api_update_my_incident`. These findings require function-by-function review of definitions, ownership, grants, and caller/role checks. Do not blanket-revoke or switch functions to `SECURITY INVOKER` without tracing the app contract; some exposed per-user operations may be intentionally privileged but must validate the caller and inputs.
- The Auth advisor reports leaked-password protection is disabled.
- Performance advisors report two unindexed foreign keys and five policies with repeated auth/current-setting evaluation. These are follow-up hardening/performance items, not authorization proof by themselves.

Official Supabase documentation reviewed: [Securing your API](https://supabase.com/docs/guides/api/securing-your-api) and [Database Advisor: authenticated SECURITY DEFINER functions](https://supabase.com/docs/guides/observability/advisors?queryGroups=lint&lint=0029_authenticated_security_definer_function_executable). Grants determine which roles can reach tables/functions; RLS governs row access for tables, while function EXECUTE grants and function security mode must be reviewed separately.

### Next security-first step

Before implementing the admin role, prepare a non-destructive privilege matrix for every exposed RPC: purpose, caller role, function owner/security mode, EXECUTE grants, data touched, explicit caller checks, and tests. Add adversarial tests for requester self-promotion, dispatcher/admin privilege boundaries, cross-incident data access, and unauthorised RPC calls. Keep the existing production database untouched until the reviewed migration and its tests are ready.


### Follow-up review for privileged RPCs

The first comparison against older migrations looked like drift, but inspection of the latest recorded migration, `20261023103737_per_responder_assignment_status.sql`, explains the live definitions: that later migration intentionally redefines `api_units()` as `SECURITY DEFINER` so the dispatcher payload can include each unit's own assignment status, and redefines `api_record_location_v2` to validate the individual member's assignment status. The live catalog therefore matches the latest migration for these functions; do not report this as unexplained schema drift.

The hardening question remains valid: the latest migration places `public` and `pg_temp` on the search path of the public location-writing SECURITY DEFINER function, and the public dispatcher RPC is also SECURITY DEFINER. The inspected functions have explicit role/assignment checks and anonymous EXECUTE is false, so this is not proof of an exploitable vulnerability. Review whether the location writer can be safely refactored into the non-exposed `private` schema while preserving the newer per-responder assignment checks, and whether both functions can use a minimal `pg_catalog` search path with all application/auth objects schema-qualified. Add regression and authorization tests before any migration; do not change the live database directly or blindly switch these functions to invoker mode.
