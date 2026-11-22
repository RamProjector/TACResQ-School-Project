# TACResQ Admin MFA lockout and recovery review

**Status:** repository and provider-documentation review  
**Reviewed:** 2026-11-22  
**Scope:** recovery from lost Admin authenticator access and continuity of trusted Admin operations  
**Changes:** documentation only; no Auth settings, users, factors, sessions, or production data changed

## Executive finding

TACResQ has a useful preventive control: the Admin Security panel can enroll additional TOTP factors, and the Admin MFA gate refuses access until a verified TOTP method produces an AAL2 session. The provisioning runbook also requires a second Admin before a planned demotion and refuses to demote the last Admin.

There is still an operational recovery gap. The repository does not define a complete, approved procedure for an Admin who loses every registered authenticator, or for an organization that loses access to every Admin account. The web application cannot solve this after the user has lost the factor needed to enter the portal.

## What the repository currently does

- `AdminMfaGate.tsx` checks the signed-in Admin's TOTP factors, requires a successful TOTP challenge, and only then renders the Admin portal.
- `AdminSecurityStatus.tsx` supports enrolling another TOTP factor, verifies it before reporting success, and permits the current Admin to revoke their own refreshable sessions after a fresh TOTP/AAL2 check.
- The Admin Security panel's factor list distinguishes verified and pending TOTP factors. It allows up to ten factors in the UI guard.
- The Admin provisioning runbook documents trusted-operator initial provisioning and planned Admin rotation. Its demotion procedure refuses to demote the last Admin.
- The existing global sign-out action is self-service for the current account. It is not a recovery mechanism and does not let one Admin reset another Admin's MFA.
- No TACResQ Admin UI or documented operator workflow was found for verified emergency MFA-factor replacement, account recovery after all Admin factors are lost, or recovery when every Admin is inaccessible.

## Important provider distinction

Do not confuse MFA recovery for a Supabase Dashboard account with MFA recovery for a user of the TACResQ Supabase project.

Supabase's current platform-account documentation describes Dashboard-account recovery codes. The project-user Auth MFA documentation and JavaScript client reference describe multiple enrolled factors (up to ten) as the backup approach; the JavaScript client reference says recovery codes are not supported there. Therefore TACResQ must not promise that its Admin users can use Supabase Dashboard recovery codes.

Provider documentation also requires MFA enforcement at database, API, and server boundaries; a frontend challenge screen alone is not sufficient. TACResQ's Admin RPC wrappers and audit-table policy currently require AAL2 and a TOTP authentication method.

## Recommended recovery policy

### 1. Prevent single-device lockout

- Require each Admin to enroll a second verified authenticator on a separate, independently accessible device before treating setup as operationally complete.
- Keep the existing verified factor until the replacement factor has been verified. Never remove the only working factor as a troubleshooting shortcut.
- Give Admins a short setup checklist explaining how to test the backup factor before storing the primary device away.
- Review factor status periodically during a planned access review. Do not expose TOTP secrets or QR payloads in logs, audit rows, screenshots, or support tickets.

### 2. Recover one Admin account

- Start with identity verification through an approved out-of-band process controlled by the project owner/operator. A request made from the locked-out account alone is not sufficient proof.
- Prefer recovery through a still-working, previously verified second factor.
- If no factor remains, require a separately approved trusted-operator recovery procedure using a provider-supported Auth administrative capability. Verify the current provider API and project configuration before writing that procedure; do not assume that the Dashboard's recovery-code process applies to project users.
- Do not create a public reset endpoint, expose a service-role key to the browser, manually edit Supabase Auth internal tables, or weaken the Admin AAL2/TOTP policies to get the user back in.
- After access is restored, enroll and verify at least two factors, revoke refreshable sessions if compromise is suspected, and record the recovery action with operator, target, reason, time, and change reference.

### 3. Recover organization-wide Admin access

- Keep a documented, named trusted project owner/operator responsible for emergency provisioning. This must be a person or role outside the ordinary Admin web portal.
- Maintain at least two individually accountable Admin accounts for normal operations, with separately stored backup factors. Do not share Admin accounts or authenticator secrets.
- Retain the existing last-Admin demotion guard. Any emergency change to the Admin roster must use a reviewed, auditable operator transaction and preserve at least one verified Admin.
- Define a contact and escalation path for the trusted project owner/operator. The app cannot provide recovery if no one can authenticate to it.

### 4. Recovery must not bypass emergency controls

- Never disable TOTP enforcement globally or create a temporary unauthenticated Admin route.
- Never promote an account based only on a matching email address or a user-submitted request.
- Do not delete an Admin profile or alter active incident assignments as part of MFA recovery.
- If the provider cannot safely restore the existing account, use the reviewed trusted-operator provisioning/rotation process rather than an improvised SQL update.

## Implementation recommendations

1. Add a recovery section to the operator runbook only after the provider-supported project-user MFA recovery operation and required permissions are verified against the deployed Auth version.
2. Add an Admin onboarding readiness checklist: two verified factors, backup tested, recovery contact recorded, and no shared secrets.
3. Add a periodic Admin access review that records factor count/status and confirms there are at least two operationally reachable Admin accounts. Do not collect TOTP secrets.
4. Consider a clear, non-secret recovery-contact notice in the Admin MFA gate so a locked-out Admin knows the approved escalation route. It must not reveal account existence or provide a bypass.
5. Add tests for last-Admin protection, verified-factor enrollment, factor-removal boundaries, and AAL2/TOTP enforcement. Keep recovery operations separate from routine role changes and ordinary user support.

## Decision

Do not add a "Reset Admin MFA" button yet. The repository does not establish a verified, supported, least-privilege project-user factor-recovery operation. A UI action without that verified backend operation would either be nonfunctional or risk bypassing the MFA boundary.

The immediate safe improvement is operational: require two verified factors per Admin and document an out-of-band trusted-operator escalation path. Any future technical recovery endpoint must be designed and tested separately, with explicit approval before production use.

## References

- Supabase project-user MFA guide: https://supabase.com/docs/guides/auth/auth-mfa
- Supabase JavaScript Auth MFA reference: https://supabase.com/docs/reference/javascript/auth-mfa
- Supabase TOTP guide: https://supabase.com/docs/guides/auth/auth-mfa/totp
- Supabase platform-account MFA recovery: https://supabase.com/kb/troubleshooting/lost-accessforgot-the-mfa-device-nAPT-7
- Supabase platform-account MFA guide: https://supabase.com/docs/guides/platform/multi-factor-authentication
