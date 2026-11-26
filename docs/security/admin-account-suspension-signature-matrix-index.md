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

The historical clean-replay snapshot's raw public-schema count is extension-schema-dependent and is not the application API baseline. The connected TACResQ Supabase catalog (2026-11-26) contains 38 authenticated-callable public API functions (24 SECURITY DEFINER and 14 SECURITY INVOKER), plus one trigger-only function with unnecessary client EXECUTE grants; there are 13 authenticated-callable private SECURITY DEFINER helpers. The current SQL contract now counts explicit api_ signatures, not every public-schema function. See Sections 29–30 of the canonical master plan and the live-catalog reconciliation in the callable crosswalk. Exact-head clean replay remains required to verify the new contract.

The read-only catalog now includes separate effective `anon` callable sections for public and private routines. The anon results must be captured and reviewed from a fresh replay; this index does not assert that the anon result is empty.

## Release gate

PR #347 remains draft until every callable and every direct table/Storage path is classified, isolated Edge Function denial tests are executed, Laravel/Sanctum boundaries are independently validated, and disposable PostgreSQL same-valid-JWT plus concurrency tests pass. No live Supabase project is modified by this matrix.

`api_admin_set_account_status(bigint,text,text)` is documented in the Admin matrix as a staged SECURITY DEFINER routine with client EXECUTE revoked; it is not part of the authenticated-callable count.
