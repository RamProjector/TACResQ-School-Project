# TACResQ requester error and fix history

**Scope:** This document records the requester emergency-flow problems reported with `Screenrecording_20261108_162440.mp4` on 2026-11-08 and the fixes/status through 2026-11-10. It covers the observed Home/SOS, request numbering, cancellation, edit, incident chat, notifications, and GPS Map issues. It is a history and verification ledger—not a claim that the production app is fully fixed or operationally ready.

## Executive status

| Reported symptom | Diagnosis and code change | Current status |
|---|---|---|
| Request list showed overall database IDs instead of a requester's own sequence | PR #279 added a stable requester-local sequence and retained the database ID only as a separate reference. | **Fixed in merged code.** |
| Edit reported a mismatch after the server accepted the update | PR #279 added tolerant coordinate comparison for harmless floating-point serialization differences while still checking all reviewed fields. | **Code-level fix merged; no post-release/device retest.** |
| Pending request could not be cancelled; message incorrectly said it was not assigned to a field unit | Live trigger rejected the requester's pending-to-cancelled transition. PR #292 adds a narrowly scoped trigger exception and PostgreSQL regression. | **Fix merged to `main`, but migration is not applied to the connected Supabase project. Live behavior is not fixed until authorized migration/deployment.** |
| Opening incident chat failed with a generic server error | Read-only live schema inspection found the incident-message RPCs absent because required chat migrations are unapplied. | **Root cause confirmed for the connected project; migration/deployment still required.** |
| Notifications allegedly crashed when opened | No stack trace or reproducible crash was supplied. PR #292 added null/blank payload normalization before Compose renders notification text. | **Defensive hardening merged; original crash cause remains unconfirmed.** |
| GPS Map felt cluttered; pins obscured details, labels were hard to read, roads had weak contrast | PR #279 adjusted requester map controls/layout. PR #292 scales app-owned map pin artwork to 75%. | **Some UI fixes merged. Basemap label size and road contrast remain unresolved; no device review was run.** |
| “Unsent draft paused” appeared while starting a request | This is a safety guard while request status is refreshing, unavailable, or an earlier SOS still has an unknown outcome; it preserves the draft and prevents another send. | **Not classified as a defect. Repeated/unexpected frequency still merits device observation.** |

## Original report and recorded symptoms

The requester report arrived on 2026-11-08 at about 16:34 (UTC+08); the supplied recording is named `Screenrecording_20261108_162440.mp4`. The video analysis records these relevant observations:

- At 0:02, the requester saw **“Unsent draft paused”** while beginning an SOS.
- At 0:48, a green snackbar displayed **“SOS sent. Dispatch confirmed the map pin you selected.”** This is only what the UI displayed; the recording does not independently prove backend or operational dispatch.
- At 0:54–0:56, opening incident messages produced **“The server couldn't complete this request.”** and the user tapped Retry.
- At 1:08–1:10, cancellation failed with **“Incident is not assigned to this field unit.”** The request remained active.
- At 1:13–1:21, the user edited the request and corrected the map pin; after saving, the app showed **“The server accepted the edit, but the returned details did not match what you reviewed. Contact dispatch...”**
- During map use, labels such as “Bugsok Food House” appeared small; pins remained large as the map zoomed out; pale roads and land features had weak contrast against the background.
- The user separately reported that opening Notifications crashed, and that request numbers appeared to be general/global rather than requester-specific. The recording itself does not demonstrate the notification crash or the numbering issue.

## Per-issue diagnosis and history

### 1. Requester-local request numbering

**Problem.** The UI exposed the globally allocated incident ID as if it were the requester's personal request number.

**Change.** PR [#279 — `fix: improve requester map and request feedback`](https://github.com/RamProjector/TACResQ-School-Project/pull/279) added `requesterSequenceNumber` and `requesterRequestLabel`. Requests are ordered by creation time, with the database ID as a deterministic tie-breaker. The UI now labels a request in the form **“Your request #N · reference #ID”**. The backend ID remains the operation/navigation key; this is a presentation change, not a database ID change.

**Evidence.** `tools/test_requester_edit_workflow.py` checks the sequence/label contract; `tools/test_requester_active_incident.py` checks that requester-local numbering does not replace the database key. PR #279 is merged. This was not separately verified against a newly installed physical-device build.

### 2. Edit accepted but shown as a mismatch

**Problem.** At 1:21, the backend appeared to accept a pin correction, but the client displayed a failure-style mismatch warning.

**Diagnosis.** The client validates the server-returned incident against the user's reviewed values. Exact equality for latitude/longitude can fail after numeric serialization/rounding even when the returned coordinate is effectively the same. The check must remain strict for incident type, description, notes, and location source; it must not silently accept a different material edit.

**Change.** PR #279 introduced `requesterEditResponseMatches`. It tolerates coordinate differences up to `0.000001` degrees, rejects non-finite values, and still checks the other reviewed fields. Existing edit confirmation, ownership, pending-only, cooldown, and audit protections remain in place.

**Evidence and limitation.** `tools/test_requester_edit_workflow.py` verifies the tolerance and required reviewed-field checks. The helper is covered by Android tests/source contracts and PR #279's checks. No live edit was performed to reproduce or verify the original record after the change; treat this as a merged client-side correction, not proof of post-deployment behavior.

### 3. Requester cancellation rejected with a field-unit error

**Problem.** At 1:10, a requester tried to cancel an active request and saw **“Incident is not assigned to this field unit.”**

**Root cause.** Read-only inspection of the connected Supabase project found that `api_cancel_my_incident` already checks requester ownership and pending status, but its status-only `UPDATE` also fires `tacresq_guard_incident_update`. The live guard did not permit the requester's pending-to-cancelled transition, so it fell through to the responder/driver assignment branch and emitted the misleading field-unit error. This root cause was confirmed from the live function/trigger definitions; no incident rows were queried or changed.

**Change.** PR [#292 — `fix(requester): repair cancellation and polish emergency flow`](https://github.com/RamProjector/TACResQ-School-Project/pull/292) adds `supabase/migrations/20261108120000_allow_requester_pending_cancellation.sql`. The exception is limited to a requester who owns a still-pending incident with no legacy assignee and no assignment-team row; it permits a status-only cancellation and requires the incident/report fields and assignment metadata to remain unchanged. Existing dispatcher assignment and responder/driver status-transition guards remain in force. The migration is registered in history validation, and `tools/test_supabase_migration_replay.sh` exercises the allowed cancellation path against isolated PostgreSQL.

**Status.** PR #292 merged as `88f63f89462b71bab617635f21f68c07c9dddd98`. Exact-head CI passed migration replay, Android builds/tests, source checks, security checks, and the RPC-grant contract. **The migration has not been applied to the connected Supabase project. The live cancellation error can therefore persist until the user explicitly authorizes the migration and it is applied.** There is no separate staging project.

### 4. Incident chat server error

**Problem.** At 0:56, opening the incident conversation produced **“The server couldn't complete this request.”**

**Root cause.** Read-only inspection confirmed `api_my_incident_messages` and `api_send_incident_message` do not exist in the connected live database. Its applied migration history is missing:

- `20261026200000_incident_scoped_chat.sql` — core incident-scoped message RPCs;
- `20261030130000_incident_message_rate_limit_index.sql` — chat sender rate-limit support;
- `20261031130000_incident_chat_image_attachments.sql` — image-attachment support.

The Android chat flow and repository migrations exist in the codebase, but a client cannot call RPCs absent from the live schema. This is a backend deployment/schema gap, not a chat UI-only defect.

**Status.** `tools/test_android_chat_contract.py`, migration-history alignment, isolated PostgreSQL replay, and the PR #292 CI run passed for the repository version. **None of those tests applies the migrations to the connected project.** The missing live migrations have not been applied; they require explicit user approval because the only project may be production. The reported chat error will likely continue until the relevant migrations are authorized, applied, and tested against a synthetic account/incident. No live chat message or incident record was inspected or modified.

### 5. Notifications crash report

**Problem.** The user reported that opening Notifications crashes. The recording does not contain a notification-screen crash, and no Android crash stack trace, device build identifier, or reproducible payload was supplied.

**Change.** PR #292 normalizes notification `title`, `body`, and `eventType` at the UI boundary before Compose renders text or chooses a route. This protects against missing/null/blank string fields in malformed JSON despite Kotlin model fields declared non-null. The change is defensive hardening; it does not establish that malformed text caused the reported crash.

**Evidence and limitation.** `tools/test_android_notifications_contract.py` checks normalization and safe rendering/routing; CI and source contracts passed. The original crash cause remains **unconfirmed**. To identify it, capture a sanitized Logcat stack trace and app build/version, then reproduce on a synthetic notification. A physical-device test was not run.

### 6. Map presentation and interaction

**Reported presentation problems.** The recording analysis identified small basemap labels, oversized/congested pins at wider zoom, and weak contrast for pale roads/land features. The map view also exposed location/routing actions that were not appropriate to the requester role, and map controls needed clearer placement.

**Changes.** PR #279 updated map loading/controls and requester-role presentation: a shared loading overlay, removal of inappropriate requester locator/routing actions, use of MapLibre's native compass, and a map-fit control. PR #292 reduces the app-owned incident/responder/location marker artwork to **75%** while retaining transparent geographic anchor padding. These are interface adjustments; they do not change stored coordinates or location-sharing permissions.

**Remaining.** Basemap label typography and pale-road contrast are controlled by the underlying tile/style and were not changed in these PRs. The pin reduction may lessen overlap but does not implement clustering. No device screenshot or visual acceptance review was run. `tools/test_gps_map_fullscreen.py` and map source contracts passed; they verify behavior/contracts, not subjective legibility on a physical screen.

### 7. “Unsent draft paused” while creating a request

**Observation.** The recording shows this notice at 0:02. Current UI displays it when a request form/draft is open but requester actions are temporarily blocked by an incident-status refresh, an unavailable current status, an already active incident, or an earlier SOS with an unresolved outcome.

**Interpretation.** This is an intentional safety state: preserve the draft, do not submit a second SOS while the existing request state is uncertain, and let the user discard the draft explicitly if desired. It is not treated as a send failure or confirmed app defect in the video. The recording alone cannot establish whether the banner appeared too often. If it recurs after status is verified, capture a screen recording plus sanitized state/log evidence; review banner frequency as a separate UX issue without weakening duplicate-SOS/idempotency safeguards.

## Chronology and merge record

- **2026-11-08:** User reported requester issues and provided the screen recording. The initial investigation traced notification, chat, cancellation, edit, numbering, and map surfaces; live Supabase work remained read-only.
- **2026-11-08 — PR #279 merged:** `fix: improve requester map and request feedback` (`e149260ef15ad4fa0834364bab1f5eb8aa10c886`). This delivered requester-local numbering, coordinate-tolerant edit response verification, and map UI/control refinements. An early CI attempt caught use of an unavailable `ZoomOutMap` icon; it was replaced with the supported `LocationOn` vector, after which exact-head checks passed.
- **2026-11-10 — PR #292 merged:** `fix(requester): repair cancellation and polish emergency flow` (`88f63f89462b71bab617635f21f68c07c9dddd98`, exact PR head `2eb8b13baa835c947ddd3222b6e8c8706d12e2cd`). This delivered the requester-cancellation trigger migration/test, notification null-safety hardening, and reduced pin artwork. The branch was rebased onto the then-current `main` and reused PR #279's numbering/edit implementation rather than duplicating it.
- **Exact-head validation for PR #292:** TACResQ CI passed source checks, Android debug/release builds and JVM tests, clean PostgreSQL migration replay, and Laravel/dashboard integration. Security/CodeQL, repository guardrails, frontend review, RPC grant contract, and automated review passed. Aggregated PR result: 11 successful, 2 skipped, 0 failed, 0 pending. Local focused cancellation/notification/map/edit/chat contracts, migration-history alignment, Python compilation, and `git diff --check` passed. Emulator/device checks were **not run**. This validates the repository change, not the live database or a released APK.
- **Live database status as of 2026-11-10:** Realtime notification migration had earlier been applied under separate explicit approval. The requester cancellation migration and three chat migrations above were not applied. No live data changes or deployments were made for this requester bug-fix work.

## Remaining actions and release boundary

1. Obtain explicit user approval before applying the cancellation or missing chat migrations to the connected Supabase project; it may be production and no separate staging project exists. Apply only the exact reviewed migrations that the user authorizes, then verify migration history and the relevant RPC/trigger contracts read-only.
2. Build and distribute an updated APK before assessing the merged client-side changes on the user's device. The code merge itself does not update an installed app.
3. If the Notifications crash recurs, collect a sanitized Logcat stack trace; do not call it fixed based only on defensive null normalization.
4. Verify the corrected edit response, cancellation, chat, request numbering, notification opening, and map readability on a synthetic account/incident after authorized schema deployment. No real emergency records should be used.
5. Consider a separate map-style/clustering review for basemap labels and road contrast; do not claim those visual shortcomings were fixed by pin scaling.

## Related documentation

- [Android client completion research](ANDROID_CLIENT_COMPLETION_RESEARCH.md) — broader settings, privacy, account-control, and product-completion gaps, not a substitute for this defect history.
- [GPS map UI recommendation](MOBILE_GPS_MAP_UI_RECOMMENDATION.md) — map destination, role placement, and location/privacy boundaries.
- [Supabase testing guide](SUPABASE_TESTING.md) — safe test environment and test-account guidance.
- [Current handoff](../CURRENT_TASK.md) — cross-session project status.
