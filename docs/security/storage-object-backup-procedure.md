# Storage object backup manifest procedure

This procedure applies to the private `incident-chat-attachments` bucket. It does not change Supabase or copy data to a remote destination.

## 1. Download a point-in-time object copy

Use a trusted operator workstation or a protected runner. Authenticate with the Supabase CLI using an appropriately scoped operator account, confirm the linked project is TACResQ, and download the bucket to a new empty local directory:

```sh
supabase link --project-ref kttguamhznoflzagfutr
supabase storage ls ss:///incident-chat-attachments --recursive --experimental --remote=kttguamhznoflzagfutr
supabase storage cp ss:///incident-chat-attachments ./backup/incident-chat-attachments --recursive --experimental --remote=kttguamhznoflzagfutr
```

The current CLI requires `--experimental` for Storage list/copy and uses `--remote=<project-ref>` for an explicit hosted target. CLI v3 defaults Storage commands to local unless `--remote` is supplied. Confirm the CLI version and command help before production use; recursive copy remains experimental. Do not use `storage mv` or `storage rm` for backups.

## 2. Create and verify a manifest

Run from the repository root:

```sh
python3 tools/create_storage_backup_manifest.py \
  --directory ./backup/incident-chat-attachments \
  --bucket incident-chat-attachments \
  --output ./backup/incident-chat-attachments.manifest.json

python3 tools/create_storage_backup_manifest.py \
  --directory ./backup/incident-chat-attachments \
  --verify ./backup/incident-chat-attachments.manifest.json
```

The manifest records relative object paths, byte sizes, and SHA-256 checksums. Verification fails for missing, unexpected, or changed files. Symlinks are rejected. Keep the manifest with the encrypted backup, but treat it as sensitive because object paths may contain identifiers.

## 3. Encrypt and transfer off-site

Before off-site transfer, encrypt both the object archive and manifest with an approved encryption tool and a key held separately from the backup destination. Do not commit either files or manifests to Git, upload them as ordinary CI artifacts, or write credentials into logs. The destination must be independent of the Supabase project and Render service. Confirm destination ownership, access controls, retention, and recovery of the decryption key before calling the backup complete.

## 4. Restore drill

Restore into an isolated test bucket/project, never over production. Verify the manifest after download, restore the objects with the documented recursive `supabase storage cp` operation, and compare object counts, sizes, and checksums. Confirm bucket privacy, MIME restrictions, object access policies, and representative attachment retrieval through the application before marking the drill successful.

## Current TACResQ baseline

The read-only inventory recorded one private bucket, `incident-chat-attachments`, with one object (79,547 metadata bytes) at the inspection time on 2026-11-19. This count is not a backup and may change. Repeat the inventory at backup time; do not hardcode this count as an expected future total.

## Limitations

- This procedure is operator-run; it is not a scheduled backup service.
- It does not back up database rows, Auth users, project configuration, or Storage policies.
- A successful copy/manifest check is not a restore drill.
- Do not store object bytes, signed URLs, access tokens, service-role keys, or database passwords in Git.
Use [the isolated restore-drill checklist](isolated-restore-drill-checklist.md) for the full cross-component recovery gates and evidence record.
