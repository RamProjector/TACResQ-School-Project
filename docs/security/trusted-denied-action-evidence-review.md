# TACResQ Admin — Trusted Denied-Action Evidence Review

**Review date:** 2026-11-22  
**Branch:** `admin/remove-unimplemented-pages`  
**Scope:** Determine whether TACResQ can safely record denied privileged RPC/API attempts without trusting the browser or changing emergency workflow authorization.

## Decision

**Do not add a browser-side denial logger or write a denial row inside the transaction that raises the authorization exception.** Neither is trustworthy: a client can fabricate a report, while a same-transaction insert is rolled back when the RPC fails.

The next viable evidence source to investigate is Supabase's managed Postgres logs for failed Data API/PostgREST statements. Supabase's current documentation says failed queries across roles, including PostgREST, are logged with error details and SQLSTATE. Those records are exposed through the Supabase Logs/ClickHouse interface, not as an ordinary application table. This makes them useful for operator investigation, but does not by itself provide a safe, supported, low-cost, in-app Admin feed.

## Options reviewed

| Option | Trust / coverage | Decision |
|---|---|---|
| Browser reports its own RPC denial | Client-controlled; actor and outcome can be fabricated | Reject |
| Insert event then raise inside the same PostgreSQL transaction | Event insert rolls back with the failed transaction | Reject |
| Laravel API operation logger | Trusted server-side metadata for Laravel-mediated calls; best-effort and does not cover direct browser/Android Supabase calls | Retain for its existing narrow purpose |
| Supabase Postgres managed logs | Provider-generated failed-query evidence, including SQLSTATE; available through Logs/ClickHouse and subject to project retention/access | Use for operator investigation only until a supported app read/ingestion path is verified |
| Route all Admin RPCs through a trusted backend gateway | Could record request outcome outside the failed DB transaction, if the gateway validates the user's Supabase JWT and calls narrow RPCs with the correct caller identity | Candidate only after a complete endpoint/auth/identity review |
| Add a separate Edge Function solely to accept client-reported failures | Still trusts the client's claim that an operation was attempted/denied unless the function itself performs the operation | Reject as a standalone logger |

## What managed Postgres logs can establish

The Supabase documentation reviewed on 2026-11-22 states:

- Postgres logs are available through Supabase Logs and the SQL log explorer.
- Failed queries from PostgREST and other roles are logged with error details.
- Error entries expose SQLSTATE, which can be used to classify failures.
- The query text may also be recorded according to `log_min_error_statement`; statements and messages can contain sensitive values.
- Query logging and pgAudit can increase noise, performance impact, and storage use when configured too broadly.
- Data API traffic is not covered by settings intended only for connections using the `postgres` role.

This is promising for incident investigation, but it is not equivalent to a domain audit event. A failed SQL statement may represent a validation error, stale client, expired session, permission denial, application bug, or platform condition. It must not be rendered as a confirmed malicious attempt without careful classification.

## Required checks before any integration

1. Verify the actual project log access available on the current Supabase plan and account. Do not assume Platform Audit Logs or Audit Log Drains are available; current Supabase documentation limits Platform Audit Logs to Team and Enterprise plans.
2. Verify log retention and whether the available interface/API can be queried by a trusted server process without exposing a privileged credential to the browser.
3. Confirm whether the log source returns a trustworthy request/session identifier that can be correlated with a TACResQ profile. Do not infer a profile from IP address or user-agent.
4. Confirm whether SQL query text or bound values can contain incident descriptions, coordinates, tokens, or other sensitive data. Do not ingest raw query text into the Admin event feed.
5. Establish a strict allowlist of relevant RPC names and SQLSTATE classes. Exclude routine validation failures and platform/resource errors unless a separate operational category is justified.
6. Establish bounded polling, deduplication, source freshness, failure visibility, and retention before presenting the source in the Admin website.
7. Test with disposable accounts and known denied calls; verify the actor attribution and classification before enabling the source.

## Backend gateway alternative

If managed logs cannot be queried safely from the application, evaluate moving only the sensitive Admin mutations behind a trusted backend gateway.

The gateway must:
- Validate the incoming Supabase access token and resolve the current user from the verified token, never from a caller-supplied profile ID.
- Re-check the current Admin role and TOTP/AAL2 claims on every request.
- Preserve the existing database authorization checks; a gateway check is not a replacement for the database check.
- Invoke narrowly scoped database operations while preserving the authenticated actor identity. Do not use a service-role call that makes every action appear to be the service account or bypasses the existing actor checks.
- Record a minimal outcome envelope in a separate trusted persistence operation after the database call returns, including request correlation ID, verified actor, fixed operation name, outcome, SQLSTATE/error class, and timestamp.
- Never record access tokens, refresh tokens, TOTP codes, raw request bodies, incident narratives, coordinates, or database query text.
- Use rate limits and bounded timeouts; the event sink must not delay SOS creation, assignment, responder status, or location operations.
- Keep the existing direct database authorization and RLS boundaries intact during rollout.

This gateway is a larger architectural change. Do not implement it until every Admin mutation path and its actor-identity semantics have been inventoried.

## Current TACResQ implementation status

- The Audit Logs page already reads six existing event sources through a bounded Admin + AAL2 + TOTP protected RPC.
- The current source set does not provide a durable, comprehensive record of denied privileged RPC attempts.
- The Laravel `api_operation_logs` writer remains limited to Laravel-mediated provider operations and is best-effort.
- Device security tables remain backend-only until trusted device-verification writers exist.
- Supabase Auth Audit Logs are a separate source; Postgres storage is optional and the project's setting remains unverified.
- No additional source should be shown as active merely because a table or provider log interface exists.

## Implementation decision

**No new database table, migration, project setting, or UI category is justified yet.** First verify whether managed Postgres logs can be accessed safely and whether they provide enough actor/request context. If not, inventory every Admin mutation and design a narrow backend gateway as a separate reviewed change.

This avoids a misleading audit feed, avoids a paid logging dependency, and does not weaken current authorization or emergency-response paths.

## External references

- Supabase, [How to Interpret and Explore the Postgres Logs](https://supabase.com/kb/troubleshooting/how-to-interpret-and-explore-the-postgres-logs-OuCIOj)
- Supabase, [Postgres log configurations](https://supabase.com/docs/guides/database/postgres/postgres-log-config)
- Supabase, [Log slow and failing queries](https://supabase.com/docs/guides/observability/log-slow-queries)
- Supabase, [Query logs with SQL](https://supabase.com/docs/guides/observability/advanced-log-filtering)
- Supabase, [Platform Audit Logs](https://supabase.com/docs/guides/security/platform-audit-logs)
