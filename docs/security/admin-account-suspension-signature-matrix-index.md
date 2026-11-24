# Account suspension authorization matrix — index

**Purpose:** Track each database entry point against the clean-replay callable baseline and the source-level lifecycle guard evidence. This is a review register, not proof of runtime enforcement.

## Review rules

- A signature is a separate review unit even when it delegates to another routine.
- Record execution mode, effective client grants, caller identity source, role/MFA requirement, lifecycle state guard, lock behavior, downstream helpers, client call sites, and runtime test evidence.
- Distinguish an implemented source guard from an executed disposable-database test. A source contract does not prove runtime denial.
- A callable marked **verify** must not be described as suspension-safe until its migration, replay contract, and same-valid-JWT runtime test are reviewed.
- Never infer that a legacy signature is unused from current Android call sites alone.
- Preserve emergency continuity: suspension must not silently cancel incidents, clear duty status, or reassign responders/drivers.

## Matrix slices

| Slice | File | Review scope |
|---|---|---|
| Requester lifecycle | [Batch 23](admin-account-suspension-signature-matrix-batch-23-requester.md) | Create, edit, cancel, claim/idempotency edges |
| Responder and dispatcher | [Batch 24](admin-account-suspension-signature-matrix-batch-24-field-operations.md) | Claim, dispatch, assignment, GPS, directory, progress |
| Messaging and notifications | [Batch 25](admin-account-suspension-signature-matrix-batch-25-messaging-notifications.md) | Chat, Storage, FCM, notifications |
| Admin and legacy compatibility | [Batch 26](admin-account-suspension-signature-matrix-batch-26-admin-legacy.md) | Admin MFA/audit, legacy requester RPCs, health exception |

## Baseline

The clean-replay snapshot in `admin-account-suspension-clean-replay-snapshot.md` records 20 public authenticated-callable SECURITY DEFINER signatures, plus invoker routines and private callable functions. Re-run the catalog after migration changes; do not treat the historical snapshot as the current catalog.

The read-only catalog now includes separate effective `anon` callable sections for public and private routines. The anon results must be captured and reviewed from a fresh replay; this index does not assert that the anon result is empty.

## Release gate

PR #347 remains draft until every callable and every direct table/Storage path is classified, isolated Edge Function denial tests are executed, Laravel/Sanctum boundaries are independently validated, and disposable PostgreSQL same-valid-JWT plus concurrency tests pass. No live Supabase project is modified by this matrix.
