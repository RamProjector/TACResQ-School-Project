# TACResQ Admin Session Revocation and Auth Audit Boundaries

**Status:** Admin self-service global session revocation is implemented in the Admin Security panel. Provider Auth Audit Log storage and dashboard configuration remain unverified. No production settings or database migrations were changed.

## Current implementation

- The Admin Security panel can revoke refreshable Supabase Auth sessions belonging to the currently signed-in administrator.
- The action requires a fresh AAL2 assurance check and a TOTP authentication method immediately before calling the Supabase client.
- A second confirmation explains that all TACResQ sessions for that same account are affected, including other browsers and Android app sessions.
- The operation uses the signed-in user's own Supabase session. It does not accept a target user ID, service-role key, or arbitrary JWT.
- The ordinary Sign out action remains local-only.
- The action is not a session inventory and cannot selectively revoke another user's session.

## Provider semantics and limitations

Supabase JavaScript auth.signOut({ scope: "global" }) revokes the user's refreshable sessions across devices. Supabase documents that access-token JWTs already issued to those sessions remain valid until their expiry. Therefore:

- Do not describe global sign-out as instantaneous invalidation of every already-issued access token.
- Protected Admin RPCs continue to require AAL2, a TOTP AMR entry, and a current Admin profile role.
- The access-token lifetime remains a residual exposure window and must be considered in session-lifetime policy.
- Do not change the project's JWT expiry or other Auth settings without researching operational impact and obtaining approval.

## Authentication audit events

Supabase Auth has a separate provider-managed Auth Audit Log covering authentication and MFA events, including sign-in, sign-out, token revocation, factor enrollment and factor removal.

Supabase documents Postgres persistence to auth.audit_log_entries as an optional project setting. The TACResQ application has not verified that setting and does not currently query that Auth schema. The dedicated TACResQ Audit History page therefore remains limited to application role-change events.

Before surfacing provider Auth events in TACResQ:

1. Verify whether Postgres Auth Audit Log storage is enabled in the Supabase project dashboard.
2. Review the actual table schema, grants, RLS behavior, retention, and sensitive fields such as IP address and user-agent metadata.
3. Design a narrow, read-only server-side query or view that exposes only fields necessary for administrator investigation.
4. Require the same Admin + AAL2 + TOTP boundary used by existing Admin audit access.
5. Preserve source labels and event types; do not merge provider events into the role-change table or imply that application role-change events and provider authentication events share the same writer.
6. Confirm whether Auth audit events are available with the project's current plan and configuration before promising retention or completeness.

## Not implemented

- Listing a user's active Supabase sessions and device metadata in TACResQ.
- Selectively revoking another user's Supabase session from the Admin portal.
- Showing Supabase Auth Audit Log events inside the TACResQ Audit History page.
- Immediate invalidation of already-issued access-token JWTs.
- A custom Auth-event ingestion pipeline or alerting system.

## References

- Supabase JavaScript sign-out scopes: https://supabase.com/docs/reference/javascript/auth-signout
- Supabase sign-out guide: https://supabase.com/docs/guides/auth/signout
- Supabase Auth Audit Logs: https://supabase.com/docs/guides/auth/audit-logs
- Supabase session model and JWT lifetime: https://supabase.com/docs/guides/auth/sessions
