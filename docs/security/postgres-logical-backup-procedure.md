# PostgreSQL logical backup component procedure

**Status:** Operator-run design and offline integrity tooling only. No database export was run and no credentials or destination were configured.

## Why three separate components

The current Supabase CLI `db dump` reference distinguishes schema, data, and custom-role dumps. Its default is schema-only; use `--data-only` and `--role-only` explicitly for the other components. The CLI excludes managed `auth`, `storage`, and extension schemas. Consequently, these files are not a complete project backup: Auth identities and Storage object bytes need separate recovery paths.

## Confirm the CLI before every run

The Supabase CLI changes flags between major versions. Check `supabase --version` and `supabase db dump --help` first. Current v3 documentation uses `--remote=<project-ref>`; older flags such as `--linked` and `--project-ref` are deprecated and removed in v4. Do not run an unpinned CLI from a mutable `latest` tag in a scheduled job.

## Export commands

Run only from a trusted, access-controlled operator environment. The remote target is pinned to the TACResQ project ref so a mistaken local default cannot silently produce an empty/local dump:

```sh
umask 077
mkdir -p ./backup/postgres
supabase db dump --remote=kttguamhznoflzagfutr --file ./backup/postgres/schema.sql
supabase db dump --remote=kttguamhznoflzagfutr --data-only --file ./backup/postgres/data.sql
supabase db dump --remote=kttguamhznoflzagfutr --role-only --file ./backup/postgres/roles.sql
```

The CLI prints a `Target:` line to stderr. Confirm it identifies the expected project before accepting the export. Supply the database password using the supported secure prompt/secret mechanism for the pinned CLI version; never place it in source, a command argument, shell history, or logs. If the installed CLI does not support the documented flags, stop and update this runbook after checking that version's official reference.

## Verify files offline

```sh
python3 tools/create_database_backup_manifest.py \
  --directory ./backup/postgres \
  --project-ref kttguamhznoflzagfutr \
  --commit "$(git rev-parse HEAD)" \
  --output ./backup/postgres.manifest.json

python3 tools/create_database_backup_manifest.py \
  --directory ./backup/postgres \
  --verify ./backup/postgres.manifest.json
```

The helper requires all three non-empty SQL files and records their sizes and SHA-256 digests. It detects later modification. It does not parse SQL, prove completeness, connect to the database, encrypt data, or prove a restore will succeed. The manifest and dump files are sensitive because operational data can include personal information.

## Encryption and destination

Encrypt the dump files and manifest before transfer. Store encryption keys separately from the backup destination. Do not use Git, public release assets, ordinary CI artifacts, or Render's ephemeral filesystem as the only backup copy. GitHub Actions artifact/log retention is configurable and bounded (default 90 days); artifacts are for workflow handoff, not durable disaster recovery. Choose an independent destination and retention policy only after the owner approves the provider, access model, and cost.

## Restore gate

Restore schema, custom roles, and data into an isolated recovery project. Review role grants/default privileges before restoring data; Supabase warns restored tables can inherit target-project default privileges. Reapply/verify reviewed grants and RLS, validate data relationships, and run TACResQ smoke tests. Never restore over production without explicit approval.

## Still separate and unresolved

- Auth users, identities, MFA and provider configuration: not included in these dumps.
- Storage bucket metadata/custom policies and object bytes: not included as a complete Storage backup; use the separate Storage procedure.
- Project secrets, SMTP settings, redirect allowlists and provider config: inventory and recover separately.
- A successful export and checksum are not a successful restore drill.
