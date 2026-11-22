#!/usr/bin/env python3
"""Contract checks for mandatory Google Authenticator TOTP on TACResQ Admin."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
gate = (ROOT / "backend/web/src/components/AdminMfaGate.tsx").read_text()
home = (ROOT / "backend/web/src/pages/Home.tsx").read_text()
admin = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
migration = (ROOT / "supabase/migrations/20261122123000_require_totp_for_admin_rpcs.sql").read_text().lower()

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
assert "Administrator access requires Google Authenticator TOTP." in admin

for marker in (
    "private.tacresq_require_admin_mfa",
    "revoke all on function public.api_admin_list_users()",
    "auth.jwt() ->> 'aal'",
    "jsonb_array_elements",
    "method ->> 'method' = 'totp'",
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
    "method ->> 'method' = 'totp'",
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
assert "method ->> 'method' = 'totp'" in policy

assert "grant execute on function public.api_admin_list_users()" not in migration, (
    "The legacy unbounded Admin user-list RPC must remain revoked."
)
assert "service_role" not in gate.lower(), "The browser must not use a service-role key."
print("Admin TOTP enrollment, verification, wrapper ordering, grants, and audit RLS contracts passed.")
