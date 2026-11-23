# Account suspension authorization inventory review — batch 17

## Scope

The existing post-replay report listed current object privileges and client schema USAGE. That still left two important questions unanswered:

- Could a client role create objects in a schema, changing the attack surface directly?
- Would future tables, sequences, functions, types, or schemas inherit grants from owner-level default privileges even if current-object grants are restrictive?

## Change

The read-only inventory now reports:

1. Effective `CREATE` and `USAGE` privileges for `anon` and `authenticated` on the reviewed `public`, `private`, `storage`, and `auth` schemas.
2. Every global and schema-scoped default ACL entry, including object owner, schema scope, object type, grantee (including `PUBLIC`), privilege, and grant option.

The default-ACL query covers PostgreSQL default privilege object types for tables, sequences, functions, types, and schemas. It reports the configured defaults rather than assuming that current table/function grants predict privileges on future objects.

## Security interpretation

- Schema `USAGE` permits name resolution; it does not grant access to contained objects.
- Schema `CREATE` is a separate capability and must be treated as a high-risk privilege for client roles.
- Default privileges apply to objects created by the relevant owner and are not retroactive. A restrictive current catalog does not prove that future migrations will remain restrictive.
- A default ACL entry is not itself proof of a currently exposed endpoint; it must be interpreted with object ownership, schema exposure, PostgREST configuration, and the actual effective grants after object creation.
- PostgREST's configured exposed-schema list is deployment configuration and is not inferred from schema USAGE alone. Confirm that configuration separately for the deployed environment before declaring a schema unreachable.
- This inventory is observational only. It does not revoke grants, alter default ACLs, change schema exposure, or modify connected Supabase projects.

## Validation status

The query is included in the clean migration replay's read-only inventory. Exact-head CI must execute the updated SQL and the reviewer must inspect the resulting rows before these new sections count as validated evidence. No connected Supabase project was queried or changed for this batch.

## Required follow-up

1. Confirm no client role has schema CREATE or elevated default privileges that grant future client access unexpectedly.
2. Classify every default ACL by owner and schema; identify which migration/owner establishes it.
3. Compare schema USAGE and object privileges with the deployed PostgREST exposed-schema configuration.
4. Reconcile any default grants with the RPC, RLS, view, Storage, and Auth-trigger inventories.
5. Add a regression assertion for client schema CREATE and any unsafe default ACL discovered by replay.
6. Keep account-state enforcement implementation blocked until the full inventory and still-valid-JWT negative tests are complete.
