# Account suspension authorization inventory review — batch 18

## Exact-head clean-replay findings

The expanded catalog inventory ran successfully in the clean PostgreSQL migration replay on PR head `01edb2497085ef0ac54547ab67feb9adced3cb68` (workflow run `45091827583`, job `Replay Supabase migrations on clean PostgreSQL`). The following are isolated replay facts, not a live connected-project snapshot.

### Schema creation and usage

- Neither `anon` nor `authenticated` has effective `CREATE` on `public`, `private`, `storage`, or `auth`.
- `anon` has `USAGE` only on `public` among those schemas.
- `authenticated` has `USAGE` on all four schemas, including `private`, `auth`, and `storage`. This is a namespace-resolution capability, not an object grant by itself. It makes the per-object function/table/Storage grant inventories especially important.
- No `anon` or `authenticated` membership in any SUPERUSER or BYPASSRLS role was reported.

### Default privileges

The clean replay reported zero global or schema-scoped default ACL entries. In particular, no default object privileges were granted to `PUBLIC`, `anon`, or `authenticated`.

### Sequence privileges

Five public sequences expose `USAGE` and `SELECT` to `authenticated`, but not `UPDATE`:

- `fcm_tokens_id_seq`
- `incident_status_logs_id_seq`
- `incidents_id_seq`
- `location_logs_id_seq`
- `profiles_id_seq`

No public sequence privilege was reported for `anon`. Sequence access is a separate capability and is not equivalent to table INSERT or UPDATE; the current replay shows no client sequence UPDATE privilege.

### Column privileges

The effective column inventory returned only the same broad SELECT capabilities already reflected by relation access; no client INSERT, UPDATE, or REFERENCES column grants were observed in the captured rows. This report is intentionally based on the full job log, not a hand-entered list of sensitive column names.

## Regression gates added

The migration replay now fails if:

1. either client role gains schema CREATE on the four reviewed schemas;
2. either client role inherits SUPERUSER or BYPASSRLS membership; or
3. any global/schema default ACL grants privileges to PUBLIC, anon, or authenticated.

These assertions protect the observed baseline against future migration drift. They do not replace per-object RLS, RPC, Storage, or account-state tests.

## Validation

The exact-head replay that produced the findings completed successfully before these new assertions were added. The assertions themselves are committed and require a subsequent exact-head CI run. No connected Supabase project was queried or modified.

## Remaining work

Continue classifying effective column/sequence grants and all callable definer implementations. The largest unresolved security gate remains executable denial of protected operations for a suspended user retaining an unexpired JWT, across direct RLS, RPC, Storage, Android, web, and bridge paths. Do not add account-state schema or UI until those tests are ready.
