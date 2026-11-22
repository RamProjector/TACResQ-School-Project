#!/usr/bin/env python3
"""Contract checks for mandatory Google Authenticator TOTP on TACResQ Admin."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
gate = (ROOT / "backend/web/src/components/AdminMfaGate.tsx").read_text()
home = (ROOT / "backend/web/src/pages/Home.tsx").read_text()
admin = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
migration = (ROOT / "supabase/migrations/20261122123000_require_totp_for_admin_rpcs.sql").read_text().lower()

for marker in (
    'factorType: "totp"',
    'friendlyName: "TACResQ Administrator"',
    "auth.mfa.enroll(",
    "auth.mfa.listFactors()",
    'factor.status === "verified"',
    "auth.mfa.challengeAndVerify({ factorId, code })",
    'method.method === "totp"',
    'data.currentLevel !== "aal2"',
    'autoComplete="one-time-code"',
    'pattern="[0-9]{6}"',
):
    assert marker in gate, f"Missing Admin TOTP flow marker: {marker}"

assert "AdminMfaGate" in home and "profile.role === \"admin\"" in home
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

for rpc in (
    "api_admin_list_users_page",
    "api_admin_set_user_role",
    "api_admin_list_audit_logs",
    "api_admin_analytics_summary",
):
    assert f"create or replace function public.{rpc}" in migration, f"Missing MFA wrapper for {rpc}"

assert "grant execute on function public.api_admin_list_users()" not in migration, "The legacy unbounded Admin user-list RPC must remain revoked."
assert "service_role" not in gate.lower(), "The browser must not use a service-role key."
print("Admin TOTP enrollment, verification, and server-side enforcement contracts passed.")
