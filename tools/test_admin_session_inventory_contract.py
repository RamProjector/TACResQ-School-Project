from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261127020000_admin_session_inventory_read_rpc.sql").read_text()
admin_guard = (ROOT / "supabase/migrations/20261125020826_account_lifecycle_admin_mfa_guard.sql").read_text()
session_guard = (ROOT / "supabase/migrations/20261127040000_admin_sensitive_session_revocation_guard.sql").read_text()
replay = (ROOT / "tools/test_supabase_migration_replay.sh").read_text()
runtime_test = (ROOT / "tools/test_admin_auth_sessions.sql").read_text()
integration_test = (ROOT / "tools/test_supabase_jwt_context.py").read_text()

required = (
    "CREATE OR REPLACE FUNCTION public.api_admin_list_auth_sessions_page",
    "p_target_profile_id bigint",
    "p_limit integer DEFAULT 25",
    "p_before_created_at timestamptz DEFAULT NULL",
    "p_before_session_id uuid DEFAULT NULL",
    "RETURNS jsonb",
    "VOLATILE",
    "SECURITY DEFINER",
    "SET search_path = pg_catalog",
    "PERFORM private.tacresq_require_admin_mfa()",
    "p_limit < 1 OR p_limit > 50",
    "FROM public.profiles AS p",
    "WHERE p.id = p_target_profile_id",
    "SELECT p.auth_user_id",
    "FROM auth.sessions AS s",
    "WHERE s.user_id = v_target_auth_user_id",
    "ORDER BY s.created_at DESC NULLS LAST, s.id DESC",
    "WITH page AS MATERIALIZED",
    "LIMIT p_limit + 1",
    "items AS MATERIALIZED",
    "page_state AS",
    "next_cursor AS",
    "ORDER BY created_at DESC NULLS LAST, id DESC",
    "'session_id', i.id",
    "'last_refreshed_at', i.refreshed_at AT TIME ZONE 'UTC'",
    "'record_status', 'record_present'",
    "'validity_assessment', 'not_evaluated'",
    "'source', 'supabase_auth_sessions'",
    "'source_status', 'available'",
    "'observed_at', statement_timestamp()",
    "'has_more', v_has_more",
    "'next_cursor'",
    "REVOKE ALL ON FUNCTION public.api_admin_list_auth_sessions_page",
    "FROM PUBLIC, anon, service_role",
    "GRANT EXECUTE ON FUNCTION public.api_admin_list_auth_sessions_page",
    "TO authenticated",
    "TO authenticated",
)
for marker in required:
    assert marker in migration, f"Missing protected session inventory contract: {marker}"

# Current Auth session enforcement is split between a read-only RLS
# predicate and a write-capable, row-locking Admin RPC predicate.
for marker in (
    "CREATE OR REPLACE FUNCTION private.tacresq_current_auth_session_present_read()",
    "RETURNS boolean",
    "LANGUAGE plpgsql",
    "STABLE",
    "SECURITY DEFINER",
    "SET search_path = pg_catalog",
    "auth.jwt() ->> 'session_id'",
    "FROM auth.sessions AS s",
    "s.id = v_session_id",
    "s.user_id = v_auth_user_id",
    "CREATE OR REPLACE FUNCTION private.tacresq_current_auth_session_valid()",
    "VOLATILE",
    "FOR SHARE",
    "REVOKE ALL ON FUNCTION private.tacresq_current_auth_session_valid()",
    "FROM PUBLIC, anon, authenticated, service_role",
    "GRANT EXECUTE ON FUNCTION private.tacresq_current_auth_session_present_read()",
    "TO authenticated",
    "IF NOT private.tacresq_current_auth_session_valid() THEN",
    "CREATE POLICY admin_audit_logs_require_aal2",
    "CREATE POLICY incident_assignment_events_admin_require_aal2",
    "AS RESTRICTIVE",
    "private.tacresq_current_auth_session_present_read()",
):
    assert marker in session_guard, f"Missing sensitive-session guard contract: {marker}"

assert "session_id" in session_guard
assert "00000000-0000-0000-0000-000000000000" in session_guard
assert "FOR SHARE" in session_guard
assignment_policy = session_guard.split(
    "CREATE POLICY incident_assignment_events_admin_require_aal2", 1
)[1]
assert "(SELECT private.tacresq_actor_role()) IS DISTINCT FROM 'admin'" in assignment_policy
assert "OR (" in assignment_policy and "private.tacresq_current_auth_session_present_read()" in assignment_policy, (
    "The dispatcher branch must remain independent while the Admin branch requires a current session."
)
assert "session_id" not in admin_guard.lower() or "tacresq_current_auth_session_valid" in session_guard

# No direct table grant and no sensitive/session validity overclaim.
for forbidden in (
    "GRANT SELECT ON TABLE auth.sessions",
    "GRANT SELECT ON auth.sessions",
    "s.ip",
    "s.user_agent",
    "s.refresh_token_hmac_key",
    "s.refresh_token_counter",
    "s.scopes",
    "s.factor_id",
    "s.oauth_client_id",
    "access_token",
    "refresh_token",
    "DROP POLICY",
    "DELETE FROM auth.sessions",
    "UPDATE auth.sessions",
):
    assert forbidden not in migration, f"Forbidden session exposure/mutation: {forbidden}"

assert "p.role = 'admin'" in admin_guard
assert "p.account_status = 'active'" in admin_guard
assert "auth.jwt() ->> 'aal'" in admin_guard
assert "method ->> 'method'" in admin_guard
assert "method #>> '{}'" in admin_guard
assert "REVOKE ALL ON FUNCTION private.tacresq_require_admin_mfa()" in admin_guard

# Session record existence is not equated with session validity.
assert "does not by itself prove current validity" in migration
assert "Auth timeout policy is not evaluated" in migration

# Cursor logic handles nullable created_at values without skipping the NULLS LAST tail.
assert "s.created_at IS NULL" in migration
assert "s.created_at = p_before_created_at" in migration
assert "s.id < p_before_session_id" in migration

# Clean replay must define a private Auth-session fixture and execute the runtime
# SQL contract, rather than relying only on source-string checks.
for marker in (
    "CREATE TABLE auth.sessions",
    "ALTER TABLE auth.sessions ENABLE ROW LEVEL SECURITY",
    "tools/test_admin_auth_sessions.sql",
):
    assert marker in replay, f"Migration replay omits the Auth session fixture/test: {marker}"

for marker in (
    "SET LOCAL ROLE authenticated",
    "private.tacresq_current_auth_session_valid()",
    "private.tacresq_current_auth_session_present_read()",
    "session_id",
    "A different user''s session UUID must not authenticate this Admin",
    "A missing session_id claim must fail closed",
    "A malformed session_id claim must fail closed",
    "An all-zero session_id claim must fail closed",
    "A revoked Admin session must lose direct audit-history access",
    "A revoked Admin session must be denied by protected Admin RPCs",
    "api_admin_list_auth_sessions_page",
    "AAL1 session inventory request must be denied",
    "AAL2 without TOTP session inventory request must be denied",
    "Suspended Admin session inventory request must be denied",
    "Auth session table must not be directly readable by API roles",
    "sensitive-refresh-hmac-fixture",
    "Keyset pagination did not advance through the NULL timestamp tail",
    "Empty session source must return an available, empty page",
):
    assert marker in runtime_test, f"Missing runtime session security test: {marker}"

for marker in (
    "private.tacresq_current_auth_session_present_read()",
    "private.tacresq_current_auth_session_valid()",
    "'session_present'",
    "__tacresq_session_valid_probe",
    "def probe_read_only(",
    'method="GET"',
    "A current Auth session must pass both read-only and row-locking session predicates",
    "STABLE predicate in a read-only GET transaction",
    "a validly signed JWT with another user's session_id failed the database ownership check",
    "The revoked session must fail database session checks while its JWT remains accepted",
):
    assert marker in integration_test, f"Missing local Auth/PostgREST session-revocation proof: {marker}"

print("PASS: Admin session inventory authorization, pagination, and privacy contracts are present.")
