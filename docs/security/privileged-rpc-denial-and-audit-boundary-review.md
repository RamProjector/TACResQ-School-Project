# Privileged RPC denial and audit boundary review

## Scope and decision

This review examines the current Admin RPC enforcement path and selected emergency-workflow RPC boundaries. It focuses on where authorization is enforced and what evidence remains available after an operation is rejected.

The current database controls are materially stronger than client-side role hiding: Admin RPC wrappers require a verified TOTP/AAL2 session and re-check the current Admin profile role; role changes validate the target and active-incident state inside a transaction; client table grants are restricted. However, rejected RPC attempts are not currently a durable security-event stream.

No runtime code, schema, grants, migrations, or production configuration are changed by this review.

## Admin RPC boundary

The migration `20261122050858_require_totp_for_admin_rpcs.sql`:

- Defines private `private.tacresq_require_admin_mfa()`.
- Requires a non-null authenticated subject, JWT assurance level `aal2`, a TOTP method in the JWT `amr` claim, and a current `profiles.role = 'admin'` row linked to that Auth subject.
- Renames the previous implementations to internal functions and revokes client execution from those internal implementations.
- Exposes narrow public wrappers for paginated user listing, role changes, audit listing, and analytics; each calls the shared MFA helper before delegating.
- Restricts the direct audit-table SELECT policy so AAL2 plus TOTP is also required for direct reads.

The dedicated `api_admin_list_audit_logs_page` endpoint separately invokes the same MFA helper and bounds pagination, query length, action, date range, and profile-ID filters.

This means a UI-only check is not the security boundary. The database wrapper and RLS policy are the enforcement points. The existing role-change audit insert is transactionally coupled to the profile role update, so a successful change and its audit row commit together.

## Important evidence gap: rejected calls

The current role-change RPC raises exceptions for invalid role requests, attempts to alter Admin accounts, missing targets, repeated role assignments, invalid reasons, and targets attached to active incidents. The MFA helper also raises an authorization exception for missing/invalid MFA or non-Admin callers.

Those exceptions abort the calling transaction. The current role-change audit table records successful `user_role_changed` events; it does not retain these rejected attempts. The wrapper does not create a separate durable denial record, and a client-side error message is not authoritative audit evidence.

Do not add a denial row to the same transaction immediately before raising an exception: the row would roll back with the rejected operation. A durable denial event needs a separate trusted persistence boundary or a provider/database logging facility whose delivery and retention semantics are verified.

## Emergency workflow boundary

The reviewed migration `20261023103737_per_responder_assignment_status.sql` demonstrates a distinct authorization model:

- The responder status implementation checks the caller's authenticated profile and requires a responder/driver role.
- It locks the incident and the caller's assignment, verifies the caller is assigned, rejects closed incidents, and validates the per-unit state transition.
- The public status endpoints are invoker wrappers around the private implementation.
- The dispatcher queue RPC checks for the dispatcher role.
- The dispatcher unit-list RPC is SECURITY DEFINER and performs a dispatcher-role check before returning field-unit data.
- The location writer validates coordinates and checks the caller's field-unit assignment and current incident/assignment state before recording location.

These controls protect emergency data and state transitions. They should not be weakened or routed through an Admin-only collector merely to centralize logs. Security-event collection must be asynchronous or otherwise bounded and fail-open so it cannot delay SOS creation, assignment, responder status changes, or permitted foreground location updates.

## Event sources and design constraints

Keep these record classes distinct:

| Record class | What it establishes | What it does not establish |
|---|---|---|
| `admin_audit_logs` | Successful administrator role changes | Denied Admin calls, authentication lifecycle, all Admin reads, or operational incident history |
| `api_operation_logs` | Selected operations mediated by the Laravel bridge | Direct Android/browser RPC outcomes or all database authorization denials |
| Supabase Auth events | Provider events that are actually emitted and retained by the configured Auth service | Application-specific authorization decisions unless the provider explicitly records them |
| Incident status and assignment history | Committed emergency workflow transitions | Failed or rejected RPC attempts in general |
| FCM delivery ledger | Recorded notification delivery attempts and outcomes | Authentication or authorization activity |

A future security-event collector should use a separate transaction or provider-supported event stream for denial evidence. It must derive actor identity from trusted Auth/database context, preserve original event source and type, bound payloads and timeouts, deduplicate provider retries, and exclude credentials, tokens, message bodies, incident narratives, and precise coordinates.

## Safe next steps

1. Verify the live Supabase Auth/database logging configuration, retention, export path, and access controls. The repository cannot prove those live settings.
2. Determine whether PostgreSQL logs or supported Auth hooks can reliably capture RPC failures, including SQLSTATE and function name, without recording query parameters or sensitive data.
3. If a supported server-side event path exists, ingest a minimal event envelope into a separate append-only security-event store using a trusted server credential that is never shipped to clients.
4. Add explicit correlation only where a trustworthy request identifier exists; do not accept a client-supplied actor ID as proof of identity.
5. Define retention, read authorization, monitoring of collector failure, and a clear Unknown state when the event source is unavailable.
6. Add disposable-database authorization tests for allowed and denied roles, invalid MFA claims, wrong assignments, invalid state transitions, and failure of the event sink. Do not apply a migration to the live project until the implementation and rollback path are reviewed.

## Non-goals

- Do not change existing Admin MFA or emergency workflow authorization semantics in order to make logging easier.
- Do not make the browser or Android app write directly to a privileged security-event table.
- Do not claim that Admin Audit Logs is a complete security-event viewer.
- Do not add a synchronous external logging dependency to emergency request paths.
- Do not infer that every SECURITY DEFINER RPC is unsafe solely from its security mode; review ownership, search path, grants, and in-function authorization individually.

## Repository evidence reviewed

- `supabase/migrations/20261112215653_admin_role_audit_foundation.sql`
- `supabase/migrations/20261122050858_require_totp_for_admin_rpcs.sql`
- `supabase/migrations/20261125020814_admin_audit_server_pagination.sql`
- `supabase/migrations/20261023103737_per_responder_assignment_status.sql`
- `supabase/migrations/20261122050839_restrict_incident_assignment_table_privileges.sql`
- `tools/test_admin_totp_contract.py`
- `tools/test_rpc_grants.py`

This is a repository-source review. It is not a live database catalog query, runtime authorization test, or verification of configured Supabase logging and retention.
