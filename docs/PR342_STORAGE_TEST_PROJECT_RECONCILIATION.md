# PR #342 — Storage test-project reconciliation plan

**Status:** investigation only; no remote writes authorized  
**Project:** `TACResQ-Storage-Test` (`kyomsyprjdseaghdxhdr`)  
**PR branch:** `security/incident-chat-upload-reservations`

## Verified state

Read-only inspection on 2026-11-23 established:

- `private.incident_chat_upload_policy` exists and contains the singleton row with `reservation_ttl_seconds = 900` and `uploads_per_minute = 20`.
- The live table has `singleton`, `reservation_ttl_seconds`, `uploads_per_minute`, and `updated_at`; it is missing `reservation_enforcement_enabled`.
- The repository migration `20261123045607_20261122210000_incident_chat_upload_reservations.sql` defines `reservation_enforcement_enabled boolean NOT NULL DEFAULT false`.
- Migration history contains version `20261122145444` named `20261122210000_incident_chat_upload_reservations`, plus version `20261122145856` named `test_pr342_storage_policy_review_fixes`. The latter has no matching migration file on this PR branch.
- The live Storage insert policy references `private.tacresq_upload_reservation_allows_insert` and uses `COALESCE(metadata->>'size', metadata->>'contentLength')`; the read policy checks incident-chat participation.
- The bucket is private, limited to 5 MiB, and permits JPEG, PNG, and WebP.
- The read-only schema contract fails closed because the enforcement column is absent.

These observations prove schema/history drift. They do not prove which manual or automated operation produced it. Do not infer or fabricate the contents of the untracked test migration.

## Proposed non-destructive reconciliation

1. **Freeze remote writes.** Do not reset the project, edit migration history, drop objects, or run ad-hoc SQL. Preserve existing test data and Storage objects.
2. **Capture a fresh baseline immediately before any approved change.** Export the relevant catalog definitions and row state for the policy table, reservation table, validator and RPC function definitions, Storage policies, bucket configuration, grants, and the complete migration-history rows. Record timestamps and project ref.
3. **Review the unexplained history entry.** Obtain the exact SQL or deployment record that created `test_pr342_storage_policy_review_fixes`. If its contents cannot be recovered and reviewed, leave the history untouched and treat the project as non-canonical; do not mark the migration as safely reconciled.
4. **Prepare a forward-only migration in source control.** The expected minimal schema delta is adding `reservation_enforcement_enabled boolean NOT NULL DEFAULT false` to `private.incident_chat_upload_policy`, then verifying the singleton row is explicitly false. The migration must be idempotent where safe, must not delete or rewrite reservations, messages, Storage objects, or user data, and must preserve the staged rollout with enforcement disabled. Do not enable enforcement as part of reconciliation.
5. **Rehearse against a disposable database.** Replay the complete repository migration chain and the proposed forward-only reconciliation from a clean baseline. Also test the migration against a fixture representing the observed drift. Verify table constraints, function definitions, grants, RLS, Storage policies, bucket configuration, and the read-only schema checker.
6. **Obtain explicit approval before applying anything to the remote test project.** Apply only the reviewed migration through the project's normal migration process. Do not use dashboard SQL editor or direct ad-hoc SQL as a substitute for a tracked migration.
7. **Re-run read-only verification.** Confirm the new column is NOT NULL with false default, the singleton row is false, the reservation validator and RPC definitions match the reviewed migration, grants and Storage policies remain correct, and `tools/check_incident_chat_upload_schema.sql` passes. Confirm the unexplained history entry has a documented disposition without rewriting historical records.
8. **Perform authenticated Storage acceptance using synthetic data only.** Use a dedicated synthetic account and incident, never real user content. Verify:
   - authorized binary POST with a valid unexpired reservation succeeds;
   - finalization creates exactly one message and consumes the reservation;
   - authenticated participant download succeeds while a nonparticipant download is denied;
   - duplicate finalization and retry are idempotent;
   - wrong MIME, wrong path, expired/consumed reservation, and size mismatch are rejected;
   - oversized files and unsupported MIME types are rejected;
   - failed uploads do not leave misleading chat messages or orphaned state.
9. **Keep rollout staged.** Leave `reservation_enforcement_enabled = false` until supported Android versions have been verified to reserve before uploading and the authenticated HTTP acceptance has passed. Enabling enforcement is a separate operational decision requiring explicit approval.

## Current release disposition

- Clean PostgreSQL migration replay and repository CI validate the checked-in migration chain; they do not repair or certify the separate remote project's drift.
- The actual Supabase Storage binary POST/download path has not yet been exercised with an authenticated test account. The CodeRabbit review thread requesting that acceptance remains open.
- No remote schema, migration history, bucket, object, or data has been modified as part of this investigation.
- PR #342 must remain open until exact-head CI is green and the Storage acceptance/reconciliation gates are satisfied. No production migration, deployment, or merge is authorized by this plan.


## 2026-11-23 — Follow-up read-only baseline

A second read-only check was performed before considering any migration write:

- The policy singleton row is present and remains `singleton = true`, `reservation_ttl_seconds = 900`, and `uploads_per_minute = 20`.
- The table still has no `reservation_enforcement_enabled` column. The existing row timestamp is `2026-11-22 14:54:44.167344+00`.
- The migration ledger still contains the repository reservation migration and the additional `test_pr342_storage_policy_review_fixes` record.
- The checked-in reservation migration defines the missing column as `boolean NOT NULL DEFAULT false`; its semantics are staged compatibility, not immediate enforcement.

**Decision:** Do not apply the column-only migration yet. The unexplained history entry remains unrecoverable from the repository branch and no deployment SQL or execution record has been supplied. Applying another migration before identifying whether that entry changed related functions or Storage policies could mask an incomplete or conflicting test-project state. Preserve the existing schema, data, Storage objects, and migration history. The project remains non-canonical for HTTP acceptance until the extra history entry is explained and the full live catalog baseline is reviewed.

No remote write, migration repair, schema reset, policy replacement, bucket change, upload, or deletion was performed.


## 2026-11-23 — Root cause recovered from PostgreSQL logs

The previously unexplained history entry was recovered from the project's PostgreSQL statement logs (2026-11-22 14:58:56 UTC). The log records the full SQL submitted under `test_pr342_storage_policy_review_fixes`. It:

- created the incident reservation index if absent;
- replaced `private.tacresq_upload_reservation_allows_insert` with a validator that checked the caller, reservation path, MIME, size, unconsumed state, expiry, and incident access, but did **not** read `reservation_enforcement_enabled`;
- recreated the Storage INSERT policy using the `size` / `contentLength` fallback.

The earlier original migration statement logged at 14:54:44 UTC also confirms that the live policy table was created without `reservation_enforcement_enabled`. This differs from the current checked-in migration, which includes the column and staged validator. The live catalog independently confirms the older validator is still installed, while the current Storage INSERT policy already has the expected `contentLength` fallback.

**Root cause:** the test project was initialized from an older reservation-migration SQL payload, then received a test-only patch that replaced the validator and Storage INSERT policy. This is now supported by the recorded SQL, not an inference from migration names alone.

## Forward-only source correction

Added `supabase/migrations/20261123045609_20261122213000_reconcile_incident_chat_upload_policy.sql` in commit `95cf2bd5b7f15f1ca465efe20d4d03e8753de167`.

The migration:
- adds the missing boolean column with `NOT NULL DEFAULT false` if absent;
- repairs null values only, preserving any existing true value;
- enforces the false default and NOT NULL constraint;
- restores the staged validator behavior from the current PR migration;
- does not drop/recreate tables, rewrite migration history, modify reservations/messages/objects, change the bucket, or enable strict enforcement.

The existing live Storage INSERT policy was read-only verified to already use the expected `COALESCE(size, contentLength)` behavior, so the forward migration deliberately leaves that policy untouched.

**Validation boundary:** this is now a tracked source migration, but it has not yet passed exact-head clean replay or been applied to the remote test project. The remote database remains unchanged. Apply only after the new migration passes branch CI and a fresh baseline confirms the same catalog state.
