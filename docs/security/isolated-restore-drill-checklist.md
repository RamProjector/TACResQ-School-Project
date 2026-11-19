# TACResQ isolated backup restore drill

**Status:** Procedure and evidence template only. No restore has been run. Do not use a production project or production database as the drill target.

## Purpose and safety boundary

A verified SHA-256 manifest proves that the checked files match the manifest. It does not prove that SQL can be restored, that Supabase-managed services are recoverable, or that the application works after recovery.

Run this drill only after the project owner approves an isolated recovery target, operator access, and any provider cost. Use a separate Supabase recovery project or an equivalent isolated environment with no production users, traffic, push credentials, or external integrations. Never point the application at the recovery project in a way that can notify real users.

**Stop immediately** if the target project reference is not the approved recovery target, if any command resolves to production, if secrets would be written to logs/history, or if the operator cannot confirm the target before a destructive command.

## Required inputs

- Approved recovery project reference and region (record only the non-secret project reference).
- PostgreSQL schema, data, and role dump files plus their manifest.
- Storage object copy plus its manifest, if attachment recovery is in scope.
- Separate, approved access to recover Auth users/configuration and project settings; these are not included in the PostgreSQL dump.
- Current application release/commit intended for validation.
- Named operator and reviewer.

Keep dump files, object bytes, decrypted archives, credentials, and user data out of Git, issue comments, ordinary CI artifacts, and this drill record.

## Database restore sequence

1. Confirm the recovery target in the Supabase dashboard and CLI output. Record the target reference in the evidence record below.
2. Verify the PostgreSQL manifest offline with `tools/create_database_backup_manifest.py --verify`. Stop on any error.
3. Review the dump files and the recovery target's existing roles/extensions/default privileges. Do not blindly replay production grants into a shared or pre-populated environment.
4. Restore custom roles and prerequisites first, then schema, then data, using the commands and supported CLI/client version documented for the approved recovery environment. Confirm command help and target selection before each operation.
5. Review ownership, grants, default privileges, RLS enablement and policies. Supabase-managed schemas (including Auth and Storage) are not reconstructed by these three logical dump components.
6. Run read-only integrity checks appropriate to the restored schema: required tables/functions exist; expected constraints and indexes exist; row counts are plausible; foreign keys and critical relationships validate; reviewed RPC privileges match the repository contracts.
7. Run the repository's migration replay and privilege contract tests against a disposable clean PostgreSQL database where appropriate. Do not apply pending migrations to production as part of this drill.

Do not invent expected row counts. Compare with a separately recorded, privacy-safe backup-time inventory or source-system totals where available.

## Storage restore sequence

1. Verify the Storage manifest against the downloaded object directory. Stop on any error.
2. Confirm the isolated target bucket exists with the intended private visibility and upload restrictions before copying objects.
3. Restore object bytes into the isolated bucket using the documented recursive Storage copy command and the pinned CLI version.
4. Re-download or independently enumerate the restored objects and verify the manifest against the restored copy.
5. Confirm bucket privacy, MIME restrictions, size limits, access policies, and representative attachment retrieval through an authenticated test account. Do not create public object URLs for validation.

The object manifest does not contain bucket policies, signed URLs, or database metadata. Validate those separately.

## Application and operational validation

- [ ] Authentication recovery is handled separately; no production identities or provider credentials are exposed to the drill.
- [ ] Requester can sign in using a designated test identity.
- [ ] A synthetic test incident can be created and viewed only inside the isolated environment.
- [ ] Role boundaries and assignment/claim/release RPC authorization behave as expected.
- [ ] Admin-only pages and audit views remain restricted.
- [ ] Private attachment retrieval succeeds for an authorized test user and fails for an unauthorized user.
- [ ] Health probes report the isolated environment accurately without exposing credentials or private diagnostics.
- [ ] Notifications, SMS, email, routing, and external integrations are disabled or safely redirected; no real recipient is contacted.
- [ ] No unexpected writes, production requests, or cross-project references are observed.
- [ ] Recovery limitations and any failed checks are documented; do not label a partial drill successful.

## Evidence record

Copy this section into a restricted operational record after an approved drill. Do not place personal data, dump contents, object paths, tokens, passwords, connection strings, or signed URLs here.

| Field | Record |
|---|---|
| Drill date/time (UTC) | Not run |
| Operator / reviewer | Not run |
| Approved recovery project reference | Not run |
| Application commit | Not run |
| Database manifest verification | Not run |
| Storage manifest verification | Not run / out of scope |
| Database restore result | Not run |
| Storage restore result | Not run / out of scope |
| Application smoke checks | Not run |
| Start/end time and elapsed duration | Not run |
| Estimated recovery point (RPO) | Not measured |
| Achieved recovery time (RTO) | Not measured |
| Failed checks / deviations | Not run |
| Follow-up owner and due date | Not run |
| Reviewer approval | Not run |

## Completion criteria

A drill is successful only when the approved isolated target was used, all in-scope database and Storage restore checks passed, application authorization and attachment checks passed, external side effects were contained, and a reviewer accepted the evidence. Record RPO/RTO only from measured backup and restore timestamps. A manifest check or successful SQL import alone is not a completed restore drill.

## Current implementation boundary

This repository contains offline manifest tooling and operator procedures. It does not schedule backups, create off-site copies, manage encryption keys, restore Supabase Auth identities or project settings, automate a recovery project, or prove that TACResQ can currently meet an RPO/RTO target.
