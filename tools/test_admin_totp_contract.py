#!/usr/bin/env python3
"""Contract checks for mandatory Google Authenticator TOTP on TACResQ Admin."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
gate = (ROOT / "backend/web/src/components/AdminMfaGate.tsx").read_text()
home = (ROOT / "backend/web/src/pages/Home.tsx").read_text()
admin = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
migration = (ROOT / "supabase/migrations/20261122123000_require_totp_for_admin_rpcs.sql").read_text().lower()
audit_page_migration = (ROOT / "supabase/migrations/20261122140000_admin_audit_server_pagination.sql").read_text().lower()

for marker in (
    'factortype: "totp"',
    'friendlyname: "tacresq administrator"',
    "auth.mfa.enroll(",
    "auth.mfa.listfactors()",
    'factor.status === "verified"',
    "auth.mfa.challengeandverify({ factorid, code })",
    'method.method === "totp"',
    'data.currentlevel !== "aal2"',
    'autocomplete="one-time-code"',
    'pattern="[0-9]{6}"',
):
    assert marker in gate.lower(), f"Missing Admin TOTP flow marker: {marker}"

assert "AdminMfaGate" in home and 'profile.role === "admin"' in home
assert "[session?.user.id, session?.access_token]" in home, "Profile role must be rechecked when the auth token/session changes."
assert "<AdminMfaGate key={session.access_token}" in home, "A new auth session must restart the Admin MFA gate."
assert "Administrator access requires Google Authenticator TOTP." in admin
assert "Search recorded administrative, authentication, incident, and responder activity in one read-only feed." in admin
assert "previousFocus = useRef<HTMLElement | null>(null)" in admin, "The role-change dialog must remember its opener for keyboard focus restoration."
assert "previousFocus.current = document.activeElement instanceof HTMLElement" in admin
assert "if (trigger?.isConnected) trigger.focus()" in admin, "Closing the role-change dialog must restore focus to its trigger when it still exists."
assert "complete administrative audit history" not in admin.lower(), "Do not imply that role-change-only records cover every admin or operational event."
assert "page * pageSize >= userPage.total_count" in admin, "User management must recover when the current page becomes out of range after the account set shrinks."
assert "Math.ceil(userPage.total_count / pageSize) - 1" in admin, "Out-of-range user pagination must return to the last valid page instead of showing a false empty state."
assert "const [debouncedQuery, setDebouncedQuery] = useState(\"\")" in admin, "User search must keep a debounced query separate from the live input."
assert "window.setTimeout(() => {" in admin and "setDebouncedQuery(query.trim())" in admin and "}, 250)" in admin, "User directory search must debounce and normalize server queries."
assert "getAdminUsersPage(pageSize, page * pageSize, debouncedQuery, roleFilter)" in admin, "User directory RPC must use the debounced query."
# Overview dependency probes must run independently of the account-directory RPC.
refresh_body = admin.split("const refresh = useCallback(async () => {", 1)[1].split("}, [activeSection, page, debouncedQuery, roleFilter]);", 1)[0]
assert refresh_body.index("void getAdminDependencyHealth()") < refresh_body.index("try {"), "Dependency diagnostics must start before the account-list RPC."
assert "setDependencyHealth(null)" not in refresh_body.split("} catch (cause) {", 1)[1], "A user-directory failure must not erase independently obtained dependency health."
assert "!root?.contains(document.activeElement)" in admin and "(event.shiftKey ? last : first).focus()" in admin, "Role-change dialog must recover focus if keyboard navigation escapes its modal boundary."


# Audit history is a dedicated URL-addressable page, not a capped dashboard panel.
audit_page = (ROOT / "backend/web/src/pages/AuditLogsPage.tsx").read_text()
supabase_client = (ROOT / "backend/web/src/lib/supabase.ts").read_text()
assert 'import AuditLogsPage from "@/pages/AuditLogsPage"' in admin
assert 'const route = new URLSearchParams(search).get("adminPage") ?? "overview";' in admin
assert "resolveAdminSection(window.location.search)" in admin
assert 'window.history.pushState' in admin
assert 'activeSection === "audit" && <AuditLogsPage profile={profile} />' in admin
assert 'getAdminInvestigationEventsPage(' in audit_page and "const PAGE_SIZE = 25" in audit_page
assert "page * PAGE_SIZE >= result.total_count" in audit_page, "Audit pagination must recover when concurrent changes invalidate the current offset."
assert "Math.ceil(result.total_count / PAGE_SIZE) - 1" in audit_page, "Audit pagination must return to the last valid page after the record count shrinks."
assert "getAdminAuditLogsPage" in supabase_client
# Analytics must reject malformed/inconsistent aggregates before rendering them.
analytics_validator = supabase_client.split("export async function getAdminAnalyticsSummary", 1)[1].split("export async function getAdminInvestigationEventsPage", 1)[0]
for requirement in (
    "Number.isFinite(Date.parse(value))",
    'summary.reporting_timezone !== "Asia/Manila"',
    "validDuration(summary.average_assignment_minutes)",
    "validDuration(summary.median_assignment_minutes)",
    "summary.assigned_request_count > summary.total_requests",
    "dailyTotal !== summary.total_requests",
    "summary.pending + summary.active + summary.resolved + summary.cancelled > summary.total_requests",
):
    assert requirement in analytics_validator, f"Analytics response validation omits {requirement}"
assert r"/^\d{4}-\d{2}-\d{2}$/" in analytics_validator, "Analytics day validation must use a real YYYY-MM-DD digit pattern."
assert 'new Date(point.day + "T00:00:00Z")' in analytics_validator, "Analytics day keys must be validated as timezone-neutral dates."
assert 'new Date(point.day + "T00:00:00Z").toLocaleDateString' in admin and 'timeZone: "UTC"' in admin, "Daily analytics labels must not shift based on the administrator's browser timezone."
assert "admin-audit-panel" not in admin
assert "api_admin_list_audit_logs_page" in supabase_client
assert "Technical references" in audit_page, "Internal identifiers must be available only in the separate technical-reference disclosure."
assert "Actor profile ID" not in audit_page and "Target profile ID" not in audit_page, "The normal audit filters must not expose numeric profile-ID fields."
assert "This interface is read-only." in audit_page
assert "Authentication events depend on Supabase Auth Audit Logs Postgres storage" in audit_page
assert "Denied privileged RPC attempts are not yet durably represented in this feed." in audit_page
assert "const lifecyclePageSize = 5;" in audit_page and "getAdminAccountLifecycleEvents(lifecyclePageSize, lifecyclePage * lifecyclePageSize)" in audit_page, "Audit history must load the separate trusted account lifecycle source with bounded pagination."
assert "Account suspension and reactivation" in audit_page
assert "No account suspension or reactivation events have been recorded." in audit_page
assert "Account lifecycle history will appear after the reviewed database migration is applied." in supabase_client
assert "api_admin_list_account_lifecycle_events" in supabase_client

assert (ROOT / "docs/security/admin-session-revocation-and-auth-audit-boundaries.md").exists()

# The Security page reports live Supabase Auth state without exposing factor secrets.
# Initial enrollment must hide the password-equivalent TOTP setup key by default,
# expose it only through an explicit accessible control, and clear it after use.
assert 'type={showSecret ? "text" : "password"}' in gate
assert 'aria-label={showSecret ? "Hide manual setup key" : "Show manual setup key"}' in gate
assert "setSecret(\"\")" in gate, "Initial TOTP enrollment must clear the setup key after verification/cancellation."
assert 'type="text" readOnly value={secret}' not in gate, "Do not render the initial TOTP setup key in clear text by default."
gate_css = (ROOT / "backend/web/src/components/AdminMfaGate.css").read_text()
assert ".mfa-secret-field" in gate_css and "@media (max-width:420px)" in gate_css, "The secret reveal control must remain usable on narrow screens."
security_status = (ROOT / "backend/web/src/components/AdminSecurityStatus.tsx").read_text()
assert 'import AdminSecurityStatus from "@/components/AdminSecurityStatus"' in admin
assert "<AdminSecurityStatus />" in admin
assert "getAuthenticatorAssuranceLevel()" in security_status
assert "listFactors()" in security_status
assert "const refreshVersion = useRef(0)" in security_status, "MFA status refreshes must reject out-of-order responses."
assert "const requestId = ++refreshVersion.current" in security_status
assert "if (requestId !== refreshVersion.current) return" in security_status
assert "refreshVersion.current += 1" in security_status, "MFA status refreshes must be invalidated when the component effect is cleaned up."
assert "currentLevel" in security_status and "usedTotp" in security_status
assert 'typeof method === "string"' in security_status
assert 'typeof method === "string"' in (ROOT / "backend/web/src/components/AdminMfaGate.tsx").read_text()
assert "factor.friendly_name" in security_status and "factor.status" in security_status
assert "factor.secret" not in security_status
assert "auth.experimental.recoveryCodes" not in security_status, "Do not assume experimental recovery codes are enabled for project users."
assert "auth.mfa.enroll(" in security_status and "auth.mfa.challengeAndVerify(" in security_status
backup_enrollment = security_status.split("async function addBackupAuthenticator()", 1)[1].split("async function verifyBackupAuthenticator", 1)[0]
assert "getAuthenticatorAssuranceLevel()" in backup_enrollment, "Backup enrollment must recheck the live session rather than trust a stale rendered MFA snapshot."
assert 'aal.currentLevel !== "aal2" || !usedTotp' in backup_enrollment, "Backup enrollment must require fresh TOTP-verified AAL2 immediately before creating a factor."
assert "enrollment.secret" in security_status and "enrollment.qrCode" in security_status
assert "auth.mfa.unenroll({ factorId: enrollment.factorId })" in security_status, "Only the pending factor created by this enrollment flow may be cancelled."
gate_cancel = gate.split("async function cancelEnrollment()", 1)[1].split("async function verifyCode", 1)[0]
assert "state !== \"enroll\"" in gate_cancel
assert "auth.mfa.unenroll({ factorId })" in gate_cancel, "The sign-in gate may cancel only its own pending factor."
assert "onSignOut()" in gate_cancel, "Cancelling initial MFA setup must sign out rather than leave an unenrolled Admin session active."
assert "auth.mfa.unenroll(" not in gate.split("async function prepareAdminMfa()", 1)[1].split("export default function AdminMfaGate", 1)[0], "Sign-in preparation must not delete pending factors from other devices."
assert "snapshot.factors.length >= 10" in security_status
assert "Backup authenticator" in security_status and "separate device" in security_status
assert "{verifiedFactors.length < 2 && (" in security_status, "The Security page must prominently warn when fewer than two verified factors are enrolled."
assert "Add a backup authenticator to prevent lockout" in security_status
assert '"Primary factor locked" : "Remove"' in security_status, "The sole verified factor must be visibly locked rather than presenting a contradictory Remove action."
assert "A verified factor can be removed only when another verified factor remains" in security_status, "Security guidance must match the supported secondary-factor removal flow."
assert "This page cannot remove verified factors" not in security_status
assert "availableFactors" in (ROOT / "backend/web/src/components/AdminMfaGate.tsx").read_text()
assert "Authenticator device" in (ROOT / "backend/web/src/components/AdminMfaGate.tsx").read_text()
# Global session revocation is limited to the current Admin account and requires
# a fresh TOTP/AAL2 check immediately before the provider call.
assert 'auth.signOut({ scope: "global" })' in security_status
global_sign_out = security_status.split("async function signOutAllDevices()", 1)[1].split("async function cancelBackupEnrollment()", 1)[0]
assert "getAuthenticatorAssuranceLevel()" in global_sign_out
assert 'data.currentLevel !== "aal2" || !usedTotp' in global_sign_out
assert "setConfirmGlobalSignOut(true)" in security_status
assert "Confirm sign out everywhere" in security_status
assert "Already-issued access tokens may remain valid until they expire." in security_status
assert "admin-security-status__danger-action" in security_status
assert 'factorsResult.data.all.filter((factor) => factor.factor_type === "totp")' in security_status
assert 'factorsResult.data.all.filter((factor) => factor.factor_type === "totp")' in (ROOT / "backend/web/src/components/AdminMfaGate.tsx").read_text()
assert "p_actor_user_id: actorUserId" not in analytics_validator

for marker in (
    "private.tacresq_require_admin_mfa",
    "revoke all on function public.api_admin_list_users()",
    "auth.jwt() ->> 'aal'",
    "jsonb_array_elements",
    "method ->> 'method'",
    "method #>> '{}'",
    "when 'string' then",
    "api_admin_list_users_page_internal",
    "api_admin_set_user_role_internal",
    "api_admin_list_audit_logs_internal",
    "api_admin_analytics_summary_internal",
    "admin_audit_logs_require_aal2",
    "as restrictive",
):
    assert marker in migration, f"Missing database MFA enforcement marker: {marker}"

# The shared gate must validate both the verified JWT assurance claims
# and the current database role, not merely the browser's displayed role.
helper_match = re.search(
    r"create or replace function private[.]tacresq_require_admin_mfa[(][)].*?as [$]function[$](.*?)[$]function[$];",
    migration,
    flags=re.DOTALL,
)
assert helper_match, "Missing private Admin MFA helper body"
helper_body = helper_match.group(1)
for requirement in (
    "auth.uid() is null",
    "auth.jwt() ->> 'aal'",
    "method ->> 'method'",
    "method #>> '{}'",
    "when 'string' then",
    "p.role = 'admin'",
):
    assert requirement in helper_body, f"Admin MFA helper omits required check: {requirement}"

# Verify each public wrapper checks MFA before delegating to its private
# implementation; merely mentioning both names elsewhere is insufficient.
wrapper_targets = {
    "api_admin_list_users_page": "api_admin_list_users_page_internal",
    "api_admin_set_user_role": "api_admin_set_user_role_internal",
    "api_admin_list_audit_logs": "api_admin_list_audit_logs_internal",
    "api_admin_analytics_summary": "api_admin_analytics_summary_internal",
}
for public_name, internal_name in wrapper_targets.items():
    match = re.search(
        rf"create or replace function public\.{public_name}\b.*?as \$function\$(.*?)\$function\$;",
        migration,
        flags=re.DOTALL,
    )
    assert match, f"Missing public MFA wrapper body: {public_name}"
    body = match.group(1)
    mfa_check = body.find("perform private.tacresq_require_admin_mfa()")
    delegation = body.find(f"return public.{internal_name}(")
    assert mfa_check >= 0 and delegation > mfa_check, (
        f"{public_name} must enforce TOTP before calling {internal_name}"
    )

    internal_signature = {
        "api_admin_list_users_page": "api_admin_list_users_page_internal(integer, integer, text, text)",
        "api_admin_set_user_role": "api_admin_set_user_role_internal(bigint, text, text)",
        "api_admin_list_audit_logs": "api_admin_list_audit_logs_internal(integer)",
        "api_admin_analytics_summary": "api_admin_analytics_summary_internal(timestamptz, timestamptz)",
    }[public_name]
    assert re.search(
        rf"revoke all on function public\.{re.escape(internal_signature)}\s+from public, anon, authenticated",
        migration,
    ), f"Private implementation remains executable by a client role: {internal_name}"
    assert re.search(
        rf"grant execute on function public\.{public_name}\([^;]+?\)\s+to authenticated",
        migration,
    ), f"Authenticated role lacks the protected wrapper grant: {public_name}"

# The dedicated audit-history endpoint independently enforces the shared TOTP gate
# and bounds/searches/filtering before returning a server-paginated result.
audit_wrapper = re.search(
    r"create or replace function public[.]api_admin_list_audit_logs_page[(].*?as [$]function[$](.*?)[$]function[$];",
    audit_page_migration,
    flags=re.DOTALL,
)
assert audit_wrapper, "Missing dedicated paginated audit RPC"
assert "create index if not exists admin_audit_logs_actor_created_idx" in audit_page_migration
for requirement in (
    "perform private.tacresq_require_admin_mfa()",
    "limit p_limit offset p_offset",
    "strpos(lower(coalesce(a.reason, '')), v_query)",
    "a.actor_user_id::text",
    "a.target_user_id::text",
    "p_from",
    "p_to",
    "p_actor_user_id",
    "p_target_user_id",
    "jsonb_build_object",
    "total_count",
):
    assert requirement in audit_wrapper.group(1), f"Paginated audit RPC omits {requirement}"
assert "p_limit > 100" in audit_wrapper.group(1)
assert "p_offset > 1000000" in audit_wrapper.group(1)
assert "revoke all on function public.api_admin_list_audit_logs_page" in audit_page_migration
assert "grant execute on function public.api_admin_list_audit_logs_page" in audit_page_migration

# Direct table reads must require both the AAL2 claim and a TOTP AMR entry.
policy_match = re.search(
    r"create policy admin_audit_logs_require_aal2(.*?);",
    migration,
    flags=re.DOTALL,
)
assert policy_match, "Missing restrictive audit-log MFA policy"
policy = policy_match.group(1)
assert "as restrictive" in policy and "for select" in policy
assert "->> 'aal') = 'aal2'" in policy
assert "method ->> 'method'" in policy and "method #>> '{}'" in policy

assert "grant execute on function public.api_admin_list_users()" not in migration, (
    "The legacy unbounded Admin user-list RPC must remain revoked."
)
# Sign-in must not silently unenroll pending TOTP factors created on another device.
prepare_start = gate.find("async function prepareAdminMfa()")
prepare_end = gate.find("export default function AdminMfaGate", prepare_start)
assert prepare_start >= 0 and prepare_end > prepare_start, "Missing Admin MFA preparation flow"
prepare_body = gate[prepare_start:prepare_end]
assert "auth.mfa.unenroll(" not in prepare_body, (
    "Admin sign-in must not delete pending factors from another active enrollment."
)

assert "service_role" not in gate.lower(), "The browser must not use a service-role key."
# Role changes must serialize concurrent updates and preserve the audit trail.
role_migration = (
    ROOT / "supabase/migrations/20261112215653_admin_role_audit_foundation.sql"
).read_text().lower()
role_match = re.search(
    r"create or replace function public[.]api_admin_set_user_role[(].*?"
    r"as [$]function[$](.*?)[$]function[$];",
    role_migration,
    flags=re.DOTALL,
)
assert role_match, "Missing Admin role-change RPC implementation"
role_body = role_match.group(1)
row_lock = role_body.find("for update")
role_read = role_body.find("old_role := target_row.role")
assert row_lock >= 0 and role_read > row_lock, (
    "Target profile must be locked before its current role is evaluated"
)
assert "p_role not in ('requester', 'responder', 'driver', 'dispatcher')" in role_body
assert "target_row.role = 'admin'" in role_body
active_incident_guard = role_body.find(
    "status in ('assigned', 'en_route', 'on_scene', 'transporting')"
)
profile_update = role_body.find("update public.profiles")
audit_insert = role_body.find("insert into public.admin_audit_logs")
assert active_incident_guard >= 0 and profile_update > active_incident_guard, (
    "Active-incident eligibility must be checked before changing the profile"
)
assert "public.incident_assignments" in role_body, (
    "Role changes must account for team assignments as well as incidents.assigned_to"
)
assert audit_insert > profile_update, (
    "The role update and its audit event must remain in the same RPC transaction"
)
assert "trim(p_reason)" in role_body and "user_role_changed" in role_body

# The Admin Security page exposes a readable, source-backed permission matrix.
# Keep this as a portal capability summary, not a fabricated live policy scan.
for marker in (
    "Admin access review",
    "Single Admin role",
    "Admin + AAL2 + TOTP",
    "Admin promotion/demotion is trusted-operator-only",
    "Denied privileged RPC attempts and some direct-client operations are not comprehensively recorded",
    "Suspend or reactivate accounts",
    "This is a reviewed portal capability summary, not a live per-user permission calculation",
    "Admin access does not implicitly grant dispatch or field-unit powers",
):
    assert marker in admin, f"Admin access review omits permission boundary: {marker}"

print("Admin TOTP, backup-factor enrollment, role-change atomicity, wrapper ordering, grants, and audit RLS contracts passed.")
