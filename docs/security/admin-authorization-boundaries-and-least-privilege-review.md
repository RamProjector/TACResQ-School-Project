# TACResQ Admin authorization boundaries and least-privilege review

**Status:** repository review and design recommendation  
**Reviewed:** 2026-11-22  
**Scope:** Admin portal permissions and privileged operations  
**Changes:** documentation only; no roles, policies, grants, or production settings changed

## Executive summary

The current Admin portal uses one application role, `admin`, for its portal-level access. The browser-callable Admin RPCs require both a current Admin profile and a verified AAL2 session containing a TOTP authentication method. The UI's navigation sections are not separate security roles.

The current scope is relatively small: account-directory viewing, ordinary-role changes, audit-history viewing, aggregate analytics, dependency-health checks, and self-service MFA/session controls. The most consequential current mutation is changing an ordinary user's role. Admin promotion/demotion remains a trusted-operator operation outside the portal.

A multi-role Admin hierarchy is not automatically justified by the current feature set. Introducing roles such as Super Admin, Security Admin, Auditor, and User Manager before defining real staff responsibilities would add policy complexity and risk inconsistent enforcement. The better immediate approach is to document action-level permissions, preserve server-side enforcement, and add separation of duties only when there are distinct people and workflows to support it.

## Repository findings

### Existing controls

- The current Admin console has seven sections: Overview, User management, Security, Reports, Activity log, Settings, and Help & about. Settings and Help are presentation/support workspaces, not additional security roles.
- The Admin user-list, role-change, audit-list, and analytics RPCs are wrapped by a private MFA guard. The guard checks the authenticated user, AAL2, a TOTP authentication method, and the current profile's Admin role.
- The audit table has an additional restrictive SELECT policy requiring AAL2 and TOTP, protecting direct table reads as well as the RPC path.
- The role-change RPC accepts only requester, responder, driver, and dispatcher. It rejects Admin promotion/demotion, validates a reason, blocks changes to accounts with active incident assignments, and writes a role-change audit event in the same transaction.
- The Admin directory returns profile identity and operational role/duty fields, not credentials or raw Auth metadata.
- Admin provisioning and rotation are documented as trusted-operator-only. The runbook refuses to demote the last Admin.
- Admin Security includes self-service authenticator enrollment and current-account global sign-out. These are operations on the signed-in Admin's own Auth account, not arbitrary target accounts.
- The navigation and React route state are presentation controls. They are not substitutes for server authorization.

### Current permission shape

| Capability | Current boundary | Risk / note |
|---|---|---|
| View overview and dependency health | Admin console; health probe uses fixed health checks | Monitoring, not operational dispatch |
| View user directory | Admin + AAL2 + TOTP RPC | Contains personal contact fields; minimize exposure |
| Change ordinary account role | Admin + AAL2 + TOTP RPC, reason required | High-impact mutation; active incident guard |
| Promote/demote Admin | Trusted operator runbook | Intentionally unavailable in the portal |
| View application audit history | Admin + AAL2 + TOTP RPC and restrictive table policy | Current history is role-change-only |
| View aggregate analytics | Admin + AAL2 + TOTP RPC | Aggregate data; avoid adding unnecessary identities or precise locations |
| Manage own TOTP factors | Current signed-in account's Supabase Auth session | Keep factor changes scoped to self |
| Revoke own refreshable sessions | Current account + fresh TOTP/AAL2 | Not a target-user session-management capability |

This is an implementation inventory, not a claim that every other TACResQ API or operational table has been exhaustively re-audited in this document.

## Admin portal access review presentation

The Security workspace now presents the capability inventory above as a read-only **Admin access review** matrix. It is intended to make the current portal boundary understandable to operators, including the Admin + AAL2 + TOTP requirement, ordinary-role change restrictions, self-service-only MFA/session operations, and unavailable suspension/reactivation controls.

The matrix is deliberately not a live policy scanner or a per-user effective-permission report. It must not be expanded to claim coverage of every RLS policy, Edge Function, Laravel route, Storage object, or direct-client operation until a separate cross-layer inventory and automated catalog checks establish that coverage. Keep the current single Admin role; do not infer that Settings subpages or navigation sections represent distinct authorization roles.

## Risk and design assessment

### 1. Do not confuse navigation with authorization

Hiding a page or button for a user is useful UX but does not protect an RPC, table, API route, or object. Each sensitive server operation must independently verify the caller and the requested action. The existing Admin MFA wrappers are a good pattern to preserve.

### 2. Keep the current single Admin role for now

The current portal has one small set of Admin operations and no documented organizational split between auditors, account managers, and security operators. Multiple Admin roles would only be worthwhile if the project defines:
- which real staff members need each permission;
- which exact operations each group may perform;
- who approves and reviews access;
- how permissions are provisioned and revoked;
- what happens during emergency staffing or lockout.

Until those requirements exist, keep the Admin role and strengthen operation-level controls instead of adding speculative roles.

### 3. Separate high-impact actions from routine viewing

The ordinary-role mutation is more consequential than viewing aggregate analytics. Future sensitive actions—account suspension, arbitrary session revocation, security-policy changes, data export, or Admin roster changes—must not automatically inherit the same broad authority merely because they appear in an Admin page.

Before adding any such action:
- classify its impact and data sensitivity;
- specify the required permission and fresh-authentication level;
- require a reason and confirmation where appropriate;
- enforce all conditions server-side in the same transaction as the mutation;
- record an attributable, append-only audit event;
- protect against self-escalation and last-Admin lockout;
- define an independent recovery path.

### 4. Treat audit administration as a distinct trust concern

Reading audit events is not the same as modifying or deleting them. The current application audit table is append-only for client roles, and the portal only exposes read access. Preserve that boundary. Do not give ordinary Admin UI operations the ability to edit or erase historical evidence.

A future separation-of-duties model could make audit review read-only for designated reviewers while restricting account mutations to designated operators. That should be introduced only with an actual staffing and governance requirement; the current portal does not yet implement those roles.

### 5. Limit personal data exposure

The directory currently includes name, email, phone, profile ID, role, duty status, and creation date. These fields support account identification and role administration, but access should remain restricted. If a future read-only reviewer role is introduced, consider whether it needs phone numbers or the complete directory at all. Analytics should remain aggregate unless a clearly justified incident-investigation workflow requires identified records.

## Recommended permission model if the project grows

Do not implement this model now. Use it as a future design only if distinct responsibilities are confirmed.

| Potential responsibility | Possible permissions | Explicit exclusions |
|---|---|---|
| Admin viewer / auditor | Read approved aggregate overview, analytics, and audit history | No account mutation, no security-factor management for others |
| Account operator | Read directory; change approved ordinary roles with reason | No Admin promotion/demotion, no audit deletion, no arbitrary session revocation |
| Security operator | Review security status and perform separately approved security actions | No broad incident dispatch or role mutation by default |
| Trusted project owner | Out-of-band Admin provisioning, emergency recovery, platform configuration | Never expose service-role credentials to the client |

This is a conceptual split, not a proposed new set of database roles. If implemented, permissions should be represented as explicit capabilities and enforced at every server boundary, rather than inferred from page names or client state.

## Prioritized recommendations

1. **P0 — Maintain current server-side checks:** keep Admin RPC wrappers, direct-table RLS protections, and current-profile role checks. Any new Admin RPC must have an explicit grant and a matching authorization guard.
2. **P0 — Build an action-to-boundary inventory:** enumerate every Admin and privileged operation across Supabase SQL/RLS, Edge Functions, Laravel routes, and web/mobile clients. Record the caller, resource, operation, required role, MFA requirement, and audit event.
3. **P1 — Add an authorization contract for each new privileged operation:** deny by default; verify the actor and target server-side; reject self-escalation; protect active emergency assignments; audit the completed mutation.
4. **P1 — Review directory data minimization:** determine whether every listed personal field is necessary for the role-management task and whether future read-only roles should see less.
5. **P1 — Define separation of duties before adding more Admin powers:** especially for suspension, arbitrary session revocation, exports, and security configuration.
6. **P2 — Consider read-only reviewer permissions only when staffing justifies them:** do not multiply roles solely to make the Admin interface appear more granular.

## Decision

Keep the current single Admin role for this release. Do not add a Super Admin / Security Admin / Auditor role hierarchy yet. The highest-value next engineering task is a complete cross-layer authorization inventory and regression contract, not a cosmetic role selector. Any future privileged feature should receive a dedicated server-side permission boundary before it receives a UI control.

## External guidance

- OWASP Authorization Cheat Sheet: least privilege, deny by default, validate permissions on every request, enforce authorization server-side, and test authorization rules: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- OWASP Authorization Patterns Cheat Sheet: keep enforcement close to the protected resource and require the policy enforcement point to enforce the decision: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Patterns_Cheat_Sheet.html
- NIST SP 800-171 Rev. 3: separation of duties can reduce abuse of authorized privileges, including separating access-control administration from audit administration where organizationally appropriate: https://csrc.nist.gov/pubs/sp/800/171/r3/final
