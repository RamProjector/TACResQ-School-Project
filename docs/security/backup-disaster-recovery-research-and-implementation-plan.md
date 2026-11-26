# TACResQ Backup and Disaster Recovery — Research and Implementation Plan

**Status:** Research and operational design only. No backup job, provider setting, retention policy, storage bucket, Render disk, or production data was changed.
**Scope:** Supabase Postgres, Supabase Storage objects, schema/migrations, Render-hosted Laravel portal, and recovery credentials/configuration.

## 1. Current architecture and recovery boundaries

- Supabase is the source of truth for application authentication and operational data.
- Supabase Storage is the intended object store for incident attachments; database backups alone do not preserve the underlying object bytes.
- Render hosts the Laravel administrator portal/API bridge. Its filesystem is ephemeral by default unless a persistent disk is explicitly attached. Do not treat the deployed container filesystem as a backup destination.
- The repository contains versioned Supabase migrations. A schema replay from migrations is useful for rebuilding structure, but it is not a substitute for restoring operational rows, Auth users, or Storage objects.
- The existing device registry and audit/event data are security-sensitive. Backup access and restore access must be restricted and audited.

## 2. Verified TACResQ deployment snapshot (2026-11-19)

Read-only checks were performed against the connected Supabase and Render accounts:

- Supabase project `TACResQ-School-Project` (`kttguamhznoflzagfutr`) is `ACTIVE_HEALTHY`, hosted in `ap-southeast-1` (Singapore), on PostgreSQL 17.
- The only Storage bucket returned by the current inventory is `incident-chat-attachments`. It is **private**, limited to **5 MiB per object**, and allows only JPEG, PNG, and WebP.
- The bucket inventory returned **1 object**, with **79,547 bytes** recorded in object metadata at the time of inspection. This is a point-in-time metadata count, not proof that the object bytes have been backed up or are restorable.
- Render service `TACResQ-School-Project` is a **Free** Docker web service in Singapore, connected to the repository's `main` branch with automatic deploy on commit. The service details returned no persistent-disk configuration. Treat its container filesystem as ephemeral and do not use it as a backup destination.
- The Supabase project details available through the connected account did not expose the project's billing plan or automatic-backup retention. **The Supabase plan and backup entitlement remain unverified**; confirm them in Supabase Billing/Project Settings before relying on provider backups.

These checks were read-only. No storage objects were downloaded or changed, no project settings were modified, and no backup job was created or run.

## 3. Provider facts that affect the design

### Supabase database

- Supabase's current documentation states that automatic daily database backups are available on Pro, Team, and Enterprise plans; the documented retention is 7, 14, and up to 30 days respectively.
- Free-plan projects do not receive automatic database backups. Supabase recommends regularly exporting data with `supabase db dump` and maintaining off-site backups.
- Database backups contain Storage metadata, not the object bytes stored through the Storage API. A database restore cannot recover an object deleted after the selected backup.
- The Supabase CLI `db dump` excludes Supabase-managed schemas including `auth` and `storage`. Its default output is schema-only; row data and custom roles require separate explicit dump options. Do not treat a successful default dump as a complete application backup.
- Point-in-Time Recovery is a paid add-on; it must not be assumed available in the current free-first deployment.

### Render

- Render web services use an ephemeral filesystem by default. Runtime-created files are lost on redeploy or restart unless they are written under an explicitly configured persistent disk mount.
- Persistent disks are a separate infrastructure decision, not an assumed feature of the current TACResQ service. Adding one can affect deployment availability and scaling; it is not proposed as a shortcut for database backups.
- Render disk snapshots, where applicable, are not application-consistent database backups and must not be used to restore a custom database.

## 4. Proposed backup layers

| Layer | What must be preserved | Proposed mechanism | Important limitation |
|---|---|---|---|
| Postgres data | Operational tables, profiles, assignments, audit/security events | Scheduled logical export using Supabase CLI `db dump` on a trusted runner | Free plan requires an externally operated schedule and off-site destination |
| Schema | Migration history and database definitions | Git version control plus clean-database migration replay | Does not preserve production rows |
| Storage objects | Actual uploaded images/video and object metadata | Enumerate objects and copy bytes to a separate encrypted off-site backup destination; maintain a manifest | DB backup alone does not contain object bytes |
| Auth/config | Auth users and identities, Auth configuration, redirect allowlist, email templates, bucket policies, relevant provider settings | Separate provider-supported Auth recovery/export procedure plus versioned configuration inventory | CLI database dumps exclude the managed `auth` schema; user password hashes and provider secrets must not be copied into Git or ordinary backup artifacts |
| Render portal | Source, deployment configuration, environment-variable names, release identity | Git + documented environment inventory + known-good deploy reference | Container filesystem is not durable storage |
| Recovery evidence | Backup job result, checksum, size, timestamp, schema version, restore test | Append-only backup manifest and restricted audit trail | A successful upload is not proof that a backup is restorable |

## 5. Free-first baseline

1. Run a scheduled Postgres logical export using a trusted runner with narrowly scoped credentials. The current CLI separates role-only, schema-only (default), and data-only output; produce and validate the required components explicitly. The CLI excludes managed `auth` and `storage` schemas. Do not put a database password or access token in source code, workflow logs, command-line arguments visible to other users, or artifacts.
1. Handle Auth independently. Inventory the supported Supabase Auth export/recovery options and determine how identities, providers, MFA factors, and user metadata can be reconstructed. Never assume the CLI dump includes Auth users or that password hashes can be exported/restored. Until this is proven, the Auth recovery objective is not met.
2. Encrypt the dump before it leaves the runner. Store it outside the Supabase project and outside the Render service filesystem.
3. Back up Storage object bytes separately. The manifest should record bucket, object path, size, content type, checksum, and backup timestamp; it must not expose private object URLs or user-facing signed URLs.
4. Keep schema migrations in Git. Record the exact commit SHA and migration version represented by each database backup.
5. Keep backup credentials separate from normal application credentials. The Android app, public web client, and admin browser must never receive backup credentials.
6. Apply retention only after confirming legal, privacy, and operational requirements. Purging a backup must be a controlled, logged operation with a second-person review for production.
7. Alert on missed schedules, failed exports, checksum mismatch, unexpectedly small/empty exports, storage-copy omissions, and insufficient destination capacity.

No paid service is required for this baseline, but an off-site destination must be selected and access-controlled. A free GitHub Actions runner is not itself a backup destination; workflow artifacts have retention/availability limits and must not become the only copy.

## 6. Restore procedure and safety gates

### Before restore

- Declare an incident and record the incident owner, affected service, suspected failure time, and desired recovery point.
- Freeze destructive maintenance and prevent competing writes where operationally safe.
- Identify the newest verified backup from before the failure; verify its checksum, encryption, expected size, schema version, and Storage manifest.
- Preserve the current damaged state where feasible. Prefer restoring into an isolated recovery project/environment first.
- Confirm that the target project, region, extensions, roles, Auth settings, Storage buckets, and redirect configuration are compatible.
- Obtain explicit production-owner approval before replacing or rewinding production data.

### Restore and validate

1. Restore Postgres into an isolated project first; never test restore procedures against the live project. Restore roles, schema, and data components in a reviewed order; verify default privileges and custom-role credentials because they may not be preserved by provider backups.
2. Reconcile custom database roles and reset credentials where the provider's backup format does not preserve them.
3. Restore Storage object bytes independently, preserving bucket/path relationships and private/public access policies.
4. Apply or verify the expected migration state without blindly replaying migrations over restored production data.
5. Validate row counts and key relationships for profiles, incidents, assignments, status history, audit logs, device registry, and idempotency records.
6. Validate that every restored attachment manifest entry has a corresponding object with matching size/checksum.
7. Reconstruct or validate Auth separately using provider-supported mechanisms and test accounts. Run auth sign-in, role authorization, incident creation/read, assignment RPC, notification dispatch, and admin audit-read smoke tests. Do not mark the Auth recovery objective complete while user/identity restoration remains untested.
8. Confirm location and personal-data access policies, RLS, grants, triggers, RPC permissions, and storage policies.
9. Document data loss window, restore duration, validation evidence, and any manual reconciliation.
10. Promote/repoint production only after explicit approval and a successful isolated restore review.

A Supabase project restore can make the project inaccessible during the restore and replaces current database state with the selected recovery point. Treat it as a high-impact production action.

## 7. Proposed service objectives (targets, not current guarantees)

| Priority | Proposed RPO | Proposed RTO | Notes |
|---|---:|---:|---|
| Emergency incident and assignment records | 24 hours maximum for the free-first baseline | 8 hours | Requires daily verified export; tighten only with tested automation and funding |
| Auth/configuration and migration state | 24 hours | 8 hours | Provisional only; Auth user/identity recovery is not yet proven and must be separately tested |
| Incident attachment objects | 24 hours | 8 hours | Requires independent object backup; do not assume DB backup includes bytes |
| Admin portal source/deployment | Git commit-level | 4 hours | Requires documented Render environment reconstruction and smoke tests |

These are proposed planning objectives, not measured capabilities or provider SLAs. If the system cannot meet them during a restore drill, revise the objective or improve the process before making an availability claim.

## 8. Admin feature boundaries

A future Admin → Security & Countermeasures → Backup & Recovery page may show:
- Last successful database export and object backup timestamps
- Backup age, size, checksum status, schema version, and retention expiry
- Last restore drill and validation result
- Failed/missed backup alerts and current recovery readiness
- A documented incident runbook and approval status

The admin browser must not receive raw database dumps, storage backup credentials, service-role keys, or unrestricted restore controls. A restore request should create an audited approval workflow handled by a privileged server-side operator, not execute a destructive restore directly from the dashboard.

## 9. Implementation sequence

**P0 — Establish reality**
- Verify exact Supabase CLI version and capture a safe dry-run of schema, data, and role export commands; account for the fact that managed `auth` and `storage` schemas are excluded.
- Determine a supported Auth user/identity recovery approach and document which fields/providers cannot be exported or restored.
- Confirm the Supabase plan, project backup availability, Storage buckets, object volume, Render service type, and whether any persistent disk exists.
- Inventory data sensitivity, retention rules, and recovery ownership.
- Verify actual database and Storage export access without changing production data.

**P1 — Build safe backup automation**
- Add a protected scheduled export workflow with least-privilege secrets, encryption, off-site copy, checksum manifest, failure notification, and retention controls.
- Add independent Storage object backup and manifest verification.
- Ensure logs and artifacts never contain credentials or personal incident content.

**P1 — Prove restore**
- Create an isolated recovery environment.
- Automate Postgres restore, Storage object restore, migration compatibility checks, and smoke tests.
- Run and document periodic restore drills.

**P2 — Admin visibility**
- Add read-only backup status and recovery readiness to the admin security area.
- Add approval/audit workflow for restore operations only after privileged server-side execution and authorization are established.

## 10. References

- Supabase Database Backups: https://supabase.com/docs/guides/platform/backups
- Supabase Free Plan backup FAQ: https://supabase.com/kb/faqs/platform/free-plan-backups
- Supabase Production Checklist: https://supabase.com/docs/guides/deployment/going-into-prod
- Render Persistent Disks: https://render.com/docs/disks
- Render FAQ (ephemeral filesystem): https://render.com/docs/faq

**Decision:** First verify the actual Supabase plan, Storage buckets, and Render service configuration. Then implement encrypted off-site database and object exports with a tested isolated restore. Do not claim TACResQ has automated backups or recovery capability until both data layers have passed a restore drill.

## 10. Current operator procedures and offline integrity tools

- PostgreSQL logical export: `docs/security/postgres-logical-backup-procedure.md`
- Storage object export: `docs/security/storage-object-backup-procedure.md`
- PostgreSQL component manifest: `tools/create_database_backup_manifest.py`
- Storage object manifest: `tools/create_storage_backup_manifest.py`

The PostgreSQL procedure explicitly creates schema, data, and custom-role dump components. The Storage procedure separately copies actual object bytes. Both manifest tools operate offline and only verify local file integrity; neither performs a backup, encrypts data, uploads to a destination, or proves restorability.

**CLI version safety:** Supabase CLI v3 changed database and Storage commands to target the local stack by default. Any hosted export must explicitly pass `--remote=<project-ref>`; the CLI prints a `Target:` line that an operator must verify. Pin the CLI version in future automation and validate its help output before relying on flags. Do not add a scheduled workflow until credentials, encryption, destination, retention, and notification paths are approved and tested.



---

## 11. Provider recovery-path update — Supabase restore to a new project (2026-11-26)

Supabase documentation updated 2026-11-23 now describes a beta **Restore to a new project** flow. For eligible paid-plan projects with physical backups enabled, the clone can transfer database schema/data/indexes/roles and Auth user data (including account/authentication records), plus the encryption root key and selected infrastructure configuration.

This is a distinct provider-managed recovery path. It does not change the behavior of `supabase db dump`: logical CLI dumps still exclude managed `auth` and `storage` schemas. The beta clone does not transfer Storage object bytes or settings, Edge Functions, Auth settings/API keys, Realtime settings, database settings, integrations, or all project settings/add-ons. Storage object backup remains a separate required component.

**TACResQ eligibility is not verified.** The connected project detail shows an active PostgreSQL 17 project but does not expose the billing plan or physical-backup entitlement. Confirm both in the Supabase dashboard before relying on the clone flow. The documented feature is beta and paid-plan/physical-backup dependent. No clone or restore was performed.

Update to P0 evaluation:
1. Confirm project billing plan and physical-backup entitlement.
2. If eligible, test Restore to a new project using an approved isolated target and document exactly which Auth user data is transferred.
3. Manually reconstruct/verify Auth settings, API keys, Edge Functions, Realtime, Storage bucket configuration/policies, integrations, and other required project settings.
4. Independently restore Storage object bytes and verify object manifests.
5. Run the full isolated TACResQ application and authorization smoke-test suite before claiming recovery success.

References:
- [Supabase — Restore to a new project (beta)](https://supabase.com/docs/guides/platform/clone-project)
- [Supabase — Database Backups](https://supabase.com/docs/guides/platform/backups)

This update is research only. No backup, project clone, restore, paid upgrade, or provider configuration was changed.
