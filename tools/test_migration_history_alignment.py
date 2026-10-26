#!/usr/bin/env python3
"""Keep repository migration filenames aligned with the applied TACResQ project history."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"

EXPECTED = {
    "20261015080806_tacresq_core_schema.sql",
    "20261015081107_harden_rpc_exposure.sql",
    "20261015081241_rls_and_index_tuning.sql",
    "20261015092225_location_and_push.sql",
    "20261019214218_incident_one_time_location.sql",
    "20261020062532_responder_location_tracking.sql",
    "20261021103127_protect_incident_report_fields.sql",
    "20261021103137_restrict_location_rpc_search_path.sql",
    "20261021103144_restrict_trigger_search_paths.sql",
    "20261021103158_restrict_actor_helper_search_path.sql",
    "20261021103206_restrict_anonymous_rpc_execution.sql",
    "20261021103213_isolate_location_writer.sql",
    "20261021113046_revoke_anon_table_privileges.sql",
    "20261021155540_manual_incident_location_source.sql",
    "20261022040223_fix_incident_status_audit_trigger.sql",
    "20261023031801_durable_sos_idempotency.sql",
    "20261023031810_requester_single_active_and_cancel.sql",
    "20261023034639_requester_edit_workflow.sql",
    "20261023081635_20261023040000_requester_urgent_location_correction.sql",
    "20261023081706_dispatch_team_assignment.sql",
    "20261023082526_harden_dispatch_team_assignment.sql",
    "20261023082939_pin_dispatch_team_search_path.sql",
    "20261023103737_per_responder_assignment_status.sql",
}
PENDING = {"20261026161500_admin_role_audit_foundation.sql"}
actual = {path.name for path in MIGRATIONS.glob("*.sql")}
assert actual == EXPECTED | PENDING, (
    f"Supabase migration history drift. Missing={sorted((EXPECTED | PENDING) - actual)}; "
    f"unexpected={sorted(actual - (EXPECTED | PENDING))}"
)

core = (MIGRATIONS / "20261015080806_tacresq_core_schema.sql").read_text()
for required in (
    "create or replace function public.rls_auto_enable()",
    "create event trigger ensure_rls",
    "set search_path = pg_catalog",
):
    assert required in core, f"Missing Supabase RLS baseline object: {required}"

push = (MIGRATIONS / "20261015092225_location_and_push.sql").read_text()
for required in (
    "create table if not exists public.fcm_tokens",
    "fcm_tokens_token_check",
    "fcm_tokens_profile_idx",
    "create policy fcm_tokens_self",
    "create policy fcm_tokens_insert_self",
    "create policy fcm_tokens_delete_self",
    "create or replace function public.api_register_fcm_token",
    "create or replace function public.api_unregister_fcm_token",
    "grant usage, select on sequence public.fcm_tokens_id_seq to authenticated",
):
    assert required in push, f"Missing baseline push/location object: {required}"

audit_fix = (MIGRATIONS / "20261022040223_fix_incident_status_audit_trigger.sql").read_text()
assert "AFTER INSERT OR UPDATE ON public.incidents" in audit_fix
assert "BEFORE UPDATE ON public.incidents" in audit_fix
assert "INSERT INTO public.incident_status_logs(incident_id, status, changed_by)" in audit_fix

team = (MIGRATIONS / "20261023081706_dispatch_team_assignment.sql").read_text()
for required in (
    "CREATE TABLE IF NOT EXISTS public.incident_assignments",
    "public.api_assign_incident_team(p_id bigint, p_user_ids bigint[])",
    "assigned_user_ids",
    "Select between 1 and 5 different field units",
    "private.tacresq_is_incident_assignee",
):
    assert required in team, f"Missing multi-unit dispatch contract: {required}"

admin = (MIGRATIONS / "20261026161500_admin_role_audit_foundation.sql").read_text()
for required in (
    "CHECK (role IN ('requester', 'responder', 'driver', 'dispatcher', 'admin'))",
    "CREATE TABLE public.admin_audit_logs",
    "ALTER TABLE public.admin_audit_logs ENABLE ROW LEVEL SECURITY",
    "CREATE POLICY admin_audit_logs_read_admin",
    "CREATE OR REPLACE FUNCTION public.api_admin_list_users()",
    "CREATE OR REPLACE FUNCTION public.api_admin_set_user_role(",
    "CREATE OR REPLACE FUNCTION public.api_admin_list_audit_logs(",
    "REVOKE ALL ON FUNCTION public.api_admin_list_users() FROM PUBLIC, anon",
    "REVOKE ALL ON FUNCTION public.api_admin_set_user_role(bigint, text, text) FROM PUBLIC, anon",
    "REVOKE ALL ON FUNCTION public.api_admin_list_audit_logs(integer) FROM PUBLIC, anon",
    "GRANT EXECUTE ON FUNCTION public.api_admin_list_users() TO authenticated",
    "GRANT EXECUTE ON FUNCTION public.api_admin_set_user_role(bigint, text, text) TO authenticated",
    "GRANT EXECUTE ON FUNCTION public.api_admin_list_audit_logs(integer) TO authenticated",
    "INSERT INTO public.admin_audit_logs",
    "Administrator promotion and demotion require trusted operator provisioning",
    "Provide an audit reason between 10 and 500 characters",
):
    assert required.lower() in admin.lower(), f"Missing Admin security contract: {required}"
assert "CREATE POLICY admin_audit_logs_read_admin" in admin
assert "FOR SELECT TO authenticated" in admin
assert "FOR INSERT TO authenticated" not in admin
assert "FOR UPDATE TO authenticated" not in admin
assert "FOR DELETE TO authenticated" not in admin
assert "DROP FUNCTION" not in admin.upper(), "Admin migration must not drop existing functions"
assert "DELETE FROM" not in admin.upper(), "Admin migration must not alter user data"

print(f"PASS: {len(EXPECTED)} local Supabase migrations match applied version history; {len(PENDING)} proposed migration(s) are explicitly pending live application.")
hardening = (MIGRATIONS / "20261023082526_harden_dispatch_team_assignment.sql").read_text()
for required in (
    "ALTER FUNCTION public.api_assign_incident_team(bigint, bigint[]) SET SCHEMA private",
    "SECURITY INVOKER",
    "private.tacresq_assign_incident_team",
):
    assert required in hardening, f"Missing multi-unit dispatch hardening: {required}"

print("PASS: dispatcher team-assignment schema and RPC contract are present.")
search_path_fix = (MIGRATIONS / "20261023082939_pin_dispatch_team_search_path.sql").read_text()
assert "SET search_path = pg_catalog" in search_path_fix

print("PASS: public assignment RPCs are invoker wrappers over a private authorization-checked implementation.")
print("PASS: private assignment RPC pins its search path to pg_catalog.")
print("PASS: missing location_and_push migration recreates FCM storage, RLS, and RPCs.")
print("PASS: incident status logging runs after insert to satisfy the incident foreign key.")
print("PASS: Admin role, role-change RPC, and append-only audit access contracts are present.")
web_client = (ROOT / "backend" / "web" / "src" / "lib" / "supabase.ts").read_text()
home = (ROOT / "backend" / "web" / "src" / "pages" / "Home.tsx").read_text()
admin_ui = (ROOT / "backend" / "web" / "src" / "pages" / "AdminConsole.tsx").read_text()
for required in (
    '"admin"',
    'api_admin_list_users',
    'api_admin_set_user_role',
    'api_admin_list_audit_logs',
):
    assert required in web_client, f"Missing typed Admin client contract: {required}"
assert 'profile.role === "admin"' in home and "<AdminConsole" in home, (
    "Authenticated Admin profiles must route to the Admin console after api_me role verification"
)
for required in (
    "getAdminUsers()",
    "getAdminAuditLogs(100)",
    "setAdminUserRole(target.id, nextRole, reason.trim())",
    "Enter an audit reason between 10 and 500 characters.",
    "Admin accounts cannot be created, promoted, demoted, or edited here.",
):
    assert required in admin_ui, f"Missing Admin UI guard/workflow: {required}"
assert '.from("profiles").update' not in admin_ui, (
    "Admin UI must use the protected role-change RPC, not direct profile writes"
)
assert '.from("admin_audit_logs").insert' not in admin_ui, (
    "Admin UI must not write audit rows directly"
)

print("PASS: Admin UI uses verified role routing and protected RPCs; no direct role/audit table writes.")
assert "const modalRoot = useRef<HTMLDivElement>(null)" in admin_ui
assert 'event.key === "Escape"' in admin_ui and 'event.key !== "Tab"' in admin_ui
assert "getFocusable()[0]?.focus()" in admin_ui
assert "Never keep privileged data visible after an authorization or connection failure." in admin_ui
print("PASS: Admin role dialog has keyboard focus management and failed refresh clears privileged data.")

