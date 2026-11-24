# Account suspension authorization matrix — batch 26: Admin and legacy compatibility

**Evidence:** clean-replay callable baseline, Admin web review, Sanctum boundary, and lifecycle-history design.

| Exact callable / surface | Operation | Required/recorded guard | Remaining proof / decision |
|---|---|---|---|
| `api_admin_list_users_page(integer,integer,text,text)` | Admin account directory | Admin role plus AAL2/TOTP required by existing Admin security model. | Confirm active Admin state is checked by shared MFA helper before any account rows are read. |
| `api_admin_set_user_role(bigint,text,text)` | Change role | Existing Admin AAL2/TOTP gate; lifecycle transition must serialize with role changes. | Test suspended Admin denial and concurrent role/suspension changes; preserve immutable audit. |
| `api_admin_set_account_status(bigint,text,text)` | Suspend/reactivate an ordinary account | Shared active-Admin AAL2/TOTP helper; target profile lock; bounded reason; transactional immutable lifecycle audit; Admin targets and self-suspension blocked. | Execute same-valid-JWT active/suspended Admin tests, transition-vs-role/duty concurrency tests, and audit rollback failure injection. |
| `api_admin_analytics_summary(timestamptz,timestamptz)` | Admin analytics | Admin AAL2/TOTP boundary. | Verify active Admin gate and bounded result scope; no aggregate leakage to suspended Admin. |
| `api_admin_list_audit_logs(integer)` | Legacy audit list | Authenticated grant remains in historical catalog despite no direct invocation found in the reviewed web library. | Keep compatibility review open; verify active Admin and AAL2/TOTP or revoke only after supported-client evidence. |
| `api_admin_list_audit_logs_page(integer,integer,text,text,timestamptz,timestamptz,bigint,bigint)` | Paged audit search | Admin AAL2/TOTP gate; restrictive MFA policy must remain restrictive. | Test pagination, filter authorization, and suspended Admin denial. |
| `api_admin_list_investigation_events(integer,integer,text,text,text,timestamptz,timestamptz,bigint,bigint,bigint)` | Investigation search | Admin AAL2/TOTP gate. | Test suspended Admin denial and cursor/filter boundary isolation. |
| `api_admin_list_account_lifecycle_events(integer,integer)` | Immutable lifecycle audit | Separate bounded Admin MFA/TOTP-protected RPC; client table access remains revoked. | Test page-size/offset bounds, active Admin positive access, and suspended Admin denial with a still-valid JWT. |
| Legacy `api_create_incident`, `api_create_incident_v2`, `api_create_incident_v3` | Older requester creation | Clean-replay review found authenticated-callable legacy signatures. | **Compatibility blocker:** inspect exact effective bodies and callers; guard through current implementation or revoke only after Android/web/Laravel compatibility tests. |
| `api_dependency_health()` | Data-free dependency status | Candidate narrow diagnostic exception only. | Decide explicitly; if retained for suspended callers, prove output contains no user, incident, location, or account data. |
| Laravel Sanctum routes | Separate local account domain | Local active-state middleware is documented; Supabase bridge has its own caller-JWT preflight. | No immutable cross-domain identity mapping exists. Never join by email; separate lifecycle state and privileged Admin/audit design remain necessary if Sanctum is supported. |

## Required test cases

1. Suspended Admin with valid JWT and AAL2/TOTP cannot read users, analytics, audit, investigation, or lifecycle history.
2. Active Admin without required AAL2/TOTP is denied; the restrictive MFA policy remains restrictive.
3. Role change and lifecycle transition serialize without losing audit events or creating an Admin lockout.
4. Legacy requester signatures are exercised by compatibility tests before any grant change.
5. A health RPC exception is explicitly documented and tested as data-free, or is denied to suspended callers.
6. Sanctum token denial is tested independently from Supabase JWT denial; no email-based identity joins.

**Status:** Review matrix only. It does not claim these tests have run or that the legacy Sanctum account domain is covered by Supabase suspension.
