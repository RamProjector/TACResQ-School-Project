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
    "20261105235009_persistent_in_app_notifications.sql",
    "20261105235016_fcm_delivery_attempts.sql",
    "20261107234752_notification_realtime_broadcast.sql",
    "20261108092044_incident_scoped_chat.sql",
    "20261108092648_incident_message_rate_limit_index.sql",
    "20261108092703_incident_chat_image_attachments.sql",
    "20261112170112_add_my_notification_lookup.sql",
    "20261112215429_isolate_per_responder_location_writer.sql",
    "20261112215513_location_logs_received_rate_index_concurrently.sql",
    "20261112215554_isolate_multi_responder_location_reader.sql",
    "20261112215653_admin_role_audit_foundation.sql",
    "20261112215712_admin_server_side_user_pagination.sql",
    "20261112215721_require_incident_location_fallback.sql",
    "20261112215734_require_requester_signup_contact.sql",
    "20261112215748_bound_requester_signup_contact_lengths.sql",
    "20261112215815_allow_requester_pending_cancellation.sql",
    "20261114153546_fcm_delivery_queue_hardening.sql",
    "20261114153550_fcm_token_account_switch.sql",
    "20261116042927_responder_incident_claiming.sql",
}
# Repository migrations not yet confirmed in the live Supabase ledger are
# allowlisted so independent PRs/deployment batches can proceed in either order.
PENDING = {
    "20261116150000_pin_api_units_search_path.sql",
    "20261116153000_optimize_rls_policy_initplans.sql",
    "20261116170000_api_operation_audit_and_fk_indexes.sql",
    "20261116180000_harden_field_claim_exclusivity.sql",
    "20261116190000_validate_requester_location_edits.sql",
    "20261116200000_harden_dispatch_claimability_lifecycle.sql",
    "20261116210000_protect_profile_identity_metadata.sql",
    "20261117010000_restrict_legacy_admin_user_list_rpc.sql",
    "20261117234000_dispatch_managed_incident_progress.sql",
    "20261118001000_allow_dispatcher_progress_duty_sync.sql",
    "20261118003000_dispatch_request_edit_notifications.sql",
    "20261118010000_incident_assignment_event_history.sql",
    "20261118011000_dispatcher_release_incident_assignment.sql",
    "20261118090000_restrict_incident_assignment_table_privileges.sql",
    "20261118092000_restrict_operational_table_writes.sql",
    "20261118100000_restrict_core_table_destructive_privileges.sql",
    "20261119090000_device_security_registry_foundation.sql",
    "20261119100000_bound_device_registry_metadata.sql",
    "20261119110000_public_data_free_dependency_health_rpc.sql",
    "20261120070000_notify_dispatcher_of_requester_cancellation.sql",
    "20261122090000_admin_analytics_summary.sql",
    "20261122123000_require_totp_for_admin_rpcs.sql",
    "20261122140000_admin_audit_server_pagination.sql",
    "20261122150000_pin_incident_assignee_helper_search_path.sql",
    "20261122153000_pin_dispatch_progress_rpc_search_path.sql",
    "20261122160000_admin_investigation_events.sql",
    "20261122200000_admin_directory_contact_minimization.sql",
    "20261122210000_incident_chat_upload_reservations.sql",
    "20261122230000_app_action_audit_coverage.sql",
    "20261122213000_reconcile_incident_chat_upload_policy.sql",
    # Follow-on account-lifecycle callable guards are staged and not confirmed in
    # the live Supabase migration ledger.
    "20261123220000_fcm_delivery_claim_active_recipient_guard.sql",
    "20261124070000_account_lifecycle_dispatch_progress_guard.sql",
    "20261124080000_account_lifecycle_team_assignment_guard.sql",
    "20261124090000_account_lifecycle_assignment_release_guard.sql",
    "20261124100000_account_lifecycle_location_writer_guard.sql",
    "20261124110000_account_lifecycle_dispatcher_directory_guard.sql",
    "20261124120000_account_lifecycle_requester_edit_guard.sql",
    "20261124130000_account_lifecycle_claimability_and_responder_read_guard.sql",
    "20261124140000_account_lifecycle_duty_status_guard.sql",
    # Account-lifecycle runtime PR #347 migrations are pending review/deployment;
    # allow them on that branch without implying they exist in the live ledger.
    "20261123090000_account_lifecycle_foundation.sql",
    "20261123100000_account_lifecycle_restrictive_rls.sql",
    "20261123110000_account_lifecycle_identity_helpers.sql",
    "20261123120000_account_lifecycle_write_guard_triggers.sql",
    "20261123130000_account_lifecycle_admin_mfa_guard.sql",
    "20261123140000_account_lifecycle_read_rpc_guards.sql",
    "20261123150000_account_lifecycle_assignment_helper_guard.sql",
    "20261123160000_account_lifecycle_helper_volatility.sql",
    "20261123170000_account_lifecycle_admin_transition_rpc.sql",
    "20261123180000_account_lifecycle_chat_idempotency_guards.sql",
    "20261123190000_account_lifecycle_write_rpc_entry_guards.sql",
    "20261123200000_admin_account_lifecycle_audit_reader.sql",
    "20261123210000_account_lifecycle_fcm_token_guards.sql",
    "20261124190000_prevent_suspending_assigned_field_units.sql",
    "20261124200000_guard_active_assignment_targets.sql",
    "20261124190000_prevent_suspending_assigned_field_units.sql",
    "20261124200000_guard_active_assignment_targets.sql",
    # Additional account-lifecycle enforcement migrations are staged on PR #347;
    # allowlisted as repository files only, not as proof of live application.
    "20261123220000_fcm_delivery_claim_active_recipient_guard.sql",
    "20261124070000_account_lifecycle_dispatch_progress_guard.sql",
    "20261124080000_account_lifecycle_team_assignment_guard.sql",
    "20261124090000_account_lifecycle_assignment_release_guard.sql",
    "20261124100000_account_lifecycle_location_writer_guard.sql",
    "20261124110000_account_lifecycle_dispatcher_directory_guard.sql",
    "20261124120000_account_lifecycle_requester_edit_guard.sql",
    "20261124130000_account_lifecycle_claimability_and_responder_read_guard.sql",
    "20261124140000_account_lifecycle_duty_status_guard.sql",
    "20261124150000_reconcile_active_account_rls_inventory.sql",
    # PR #348 FCM self-unregister RPC is staged for review/deployment; do not
    # treat repository presence as proof that the live Supabase ledger applied it.
    "20261124170000_secure_fcm_self_unregister_rpc.sql",
    "20261124180000_secure_fcm_delivery_token_cleanup_rpc.sql",
}
actual = {path.name for path in MIGRATIONS.glob("*.sql")}
assert EXPECTED <= actual, (
    f"Applied migration files missing from repository. Missing={sorted(EXPECTED - actual)}"
)
assert actual <= EXPECTED | PENDING, (
    f"Untracked migration files found. Unexpected={sorted(actual - (EXPECTED | PENDING))}"
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


notifications = (MIGRATIONS / "20261105235009_persistent_in_app_notifications.sql").read_text()
for required in (
    "CREATE TABLE IF NOT EXISTS public.user_notifications",
    "user_notifications_one_unread_assignment_idx",
    "ALTER TABLE public.user_notifications ENABLE ROW LEVEL SECURITY",
    "REVOKE ALL ON TABLE public.user_notifications FROM PUBLIC, anon, authenticated",
    "private.tacresq_notify_assignment_insert",
    "private.tacresq_notify_legacy_assignment_update",
    "private.tacresq_notify_status_log_insert",
    "public.api_my_notifications(p_limit integer DEFAULT 50)",
    "public.api_mark_notification_read(p_notification_id bigint)",
    "SET search_path = pg_catalog",
    "GRANT EXECUTE ON FUNCTION public.api_my_notifications(integer) TO authenticated",
    "GRANT EXECUTE ON FUNCTION public.api_mark_notification_read(bigint) TO authenticated",
):
    assert required in notifications, f"Missing in-app notification security contract: {required}"

notification_lookup = (MIGRATIONS / "20261112170112_add_my_notification_lookup.sql").read_text()
for required in (
    "CREATE OR REPLACE FUNCTION public.api_my_notification(p_notification_id bigint)",
    "SECURITY DEFINER",
    "SET search_path = pg_catalog",
    "WHERE n.id = p_notification_id",
    "AND n.recipient_user_id = actor_profile_id",
    "REVOKE ALL ON FUNCTION public.api_my_notification(bigint) FROM PUBLIC, anon",
    "GRANT EXECUTE ON FUNCTION public.api_my_notification(bigint) TO authenticated",
):
    assert required in notification_lookup, f"Missing caller-scoped notification lookup contract: {required}"



chat = (MIGRATIONS / "20261108092044_incident_scoped_chat.sql").read_text()
for required in (
    "CREATE TABLE IF NOT EXISTS public.incident_messages",
    "ALTER TABLE public.incident_messages ENABLE ROW LEVEL SECURITY",
    "REVOKE ALL ON TABLE public.incident_messages FROM PUBLIC, anon, authenticated",
    "private.tacresq_can_access_incident_chat",
    "public.api_my_incident_messages(",
    "public.api_send_incident_message(",
    "client_message_id",
    "pg_advisory_xact_lock(actor_profile_id)",
    "GRANT EXECUTE ON FUNCTION public.api_my_incident_messages(bigint, integer, bigint) TO authenticated",
    "GRANT EXECUTE ON FUNCTION public.api_send_incident_message(bigint, text, uuid) TO authenticated",
    "'incident_message'",
):
    assert required in chat, f"Missing incident chat security contract: {required}"

attachments = (MIGRATIONS / "20261108092703_incident_chat_image_attachments.sql").read_text()
for required in (
    "ADD COLUMN IF NOT EXISTS attachment_path text",
    "public.api_send_incident_image_message(",
    "private.tacresq_auth_profile_id()",
    "tacresq_incident_chat_attachment_insert",
    "tacresq_incident_chat_attachment_read",
    "o.metadata->>'mimetype' = p_attachment_mime_type",
    "(o.metadata->>'size')::bigint = p_attachment_size_bytes",
):
    assert required in attachments, f"Missing private incident-image attachment contract: {required}"
print("PASS: incident chat image attachments use private Storage policies and server-verified object metadata.")

api_audit = (MIGRATIONS / "20261116170000_api_operation_audit_and_fk_indexes.sql").read_text()
for required in (
    "CREATE TABLE public.api_operation_logs",
    "actor_auth_user_id uuid",
    "ALTER TABLE public.api_operation_logs ENABLE ROW LEVEL SECURITY",
    "REVOKE ALL PRIVILEGES ON TABLE public.api_operation_logs FROM PUBLIC, anon, authenticated, service_role",
    "GRANT SELECT, INSERT ON TABLE public.api_operation_logs TO service_role",
    "REVOKE ALL PRIVILEGES ON SEQUENCE public.api_operation_logs_id_seq FROM PUBLIC, anon, authenticated, service_role",
    "CREATE INDEX fcm_delivery_attempts_profile_id_idx",
    "CREATE INDEX incident_assignments_assigned_by_idx",
    "CREATE INDEX incident_request_edit_logs_requester_id_idx",
    "CREATE INDEX user_notifications_incident_id_idx",
):
    assert required in api_audit, f"Missing API audit/index hardening contract: {required}"
assert "GRANT ALL" not in api_audit.upper()
assert "GRANT INSERT ON TABLE public.api_operation_logs TO authenticated" not in api_audit

print(f"PASS: {len(EXPECTED)} applied migration files match history; {len(actual - EXPECTED)} additional migration file(s) are allowlisted and not assumed applied to live Supabase.")
hardening = (MIGRATIONS / "20261023082526_harden_dispatch_team_assignment.sql").read_text()
for required in (
    "ALTER FUNCTION public.api_assign_incident_team(bigint, bigint[]) SET SCHEMA private",
    "SECURITY INVOKER",
    "private.tacresq_assign_incident_team",
):
    assert required in hardening, f"Missing private dispatch RPC hardening: {required}"

print("PASS: dispatcher team-assignment schema and RPC contract are present.")
search_path_fix = (MIGRATIONS / "20261023082939_pin_dispatch_team_search_path.sql").read_text()
assert "SET search_path = pg_catalog" in search_path_fix

print("PASS: public assignment RPCs are invoker wrappers over a private authorization-checked implementation.")
print("PASS: private assignment RPC pins its search path to pg_catalog.")
print("PASS: missing location_and_push migration recreates FCM storage, RLS, and RPCs.")

account_switch = (MIGRATIONS / "20261114153550_fcm_token_account_switch.sql").read_text()
for required in (
    "CREATE OR REPLACE FUNCTION public.api_register_fcm_token(p_token text)",
    "SECURITY DEFINER",
    "SET search_path = pg_catalog",
    "WHERE p.auth_user_id = auth.uid()",
    "ON CONFLICT (token) DO UPDATE",
    "REVOKE ALL ON FUNCTION public.api_register_fcm_token(text)",
    "GRANT EXECUTE ON FUNCTION public.api_register_fcm_token(text)",
):
    assert required in account_switch, f"Missing FCM account-switch ownership contract: {required}"

print("PASS: FCM token ownership is transferred only to the authenticated profile.")
print("PASS: incident status logging runs after insert to satisfy the incident foreign key.")

admin = (MIGRATIONS / "20261112215653_admin_role_audit_foundation.sql").read_text()
for required in (
    "CREATE TABLE public.admin_audit_logs",
    "ALTER TABLE public.admin_audit_logs ENABLE ROW LEVEL SECURITY",
    "CREATE OR REPLACE FUNCTION public.api_admin_list_users()",
    "CREATE OR REPLACE FUNCTION public.api_admin_set_user_role(",
    "CREATE OR REPLACE FUNCTION public.api_admin_list_audit_logs(",
    "REVOKE ALL ON FUNCTION public.api_admin_set_user_role(bigint, text, text) FROM PUBLIC, anon",
    "GRANT EXECUTE ON FUNCTION public.api_admin_set_user_role(bigint, text, text) TO authenticated",
    "Provide an audit reason between 10 and 500 characters",
):
    assert required.lower() in admin.lower(), f"Missing Admin security contract: {required}"
assert "FOR INSERT TO authenticated" not in admin
assert "FOR UPDATE TO authenticated" not in admin
assert "FOR DELETE TO authenticated" not in admin
assert "DROP FUNCTION" not in admin.upper()
assert "DELETE FROM" not in admin.upper()
pagination = (MIGRATIONS / "20261112215712_admin_server_side_user_pagination.sql").read_text()
assert "LIMIT p_limit OFFSET p_offset" in pagination
assert "SET search_path = pg_catalog" in pagination
assert "DROP FUNCTION" not in pagination.upper()
assert "DELETE FROM" not in pagination.upper()
print("PASS: Admin RPC authorization, append-only audit, and bounded pagination contracts are present.")

location_fallback = (MIGRATIONS / "20261112215721_require_incident_location_fallback.sql").read_text().lower()
for required in (
    "create or replace function public.tacresq_require_incident_location()",
    "set search_path = pg_catalog",
    "latitude and longitude must be supplied together",
    "a location pin or landmark/access detail is required",
    "an emergency description is required for the other type",
    "create trigger incidents_require_location_before_write",
    "before insert or update of latitude, longitude, location_notes, type, description on public.incidents",
):
    assert required in location_fallback, f"Missing required SOS location database guard: {required}"
assert "security definer" not in location_fallback
print("PASS: SOS inserts and location/type edits require a complete pin or a nonblank landmark/access detail.")

signup_validation = (MIGRATIONS / "20261112215734_require_requester_signup_contact.sql").read_text().lower()
for required in (
    "create or replace function public.tacresq_profile_on_signup()",
    "security definer",
    "set search_path = pg_catalog",
    "a full name is required for requester registration",
    "a valid mobile number is required for requester registration",
    "requester mobile numbers must contain 7 to 15 digits",
    "'requester'",
):
    assert required in signup_validation, f"Missing server-side requester signup requirement: {required}"
assert "raw_user_meta_data ->> 'role'" not in signup_validation
print("PASS: public signup enforces name/contact requirements and always provisions requester role.")


# Device registry remains backend-only until server-side proof verification exists.
device_security = (MIGRATIONS / "20261119090000_device_security_registry_foundation.sql").read_text()
for required in (
    "CREATE TABLE IF NOT EXISTS public.user_devices",
    "CREATE TABLE IF NOT EXISTS public.device_sessions",
    "CREATE TABLE IF NOT EXISTS public.device_challenges",
    "CREATE TABLE IF NOT EXISTS public.device_security_events",
    "CREATE TABLE IF NOT EXISTS public.device_integrity_checks",
    "ALTER TABLE public.user_devices ENABLE ROW LEVEL SECURITY",
    "ALTER TABLE public.device_sessions ENABLE ROW LEVEL SECURITY",
    "ALTER TABLE public.device_challenges ENABLE ROW LEVEL SECURITY",
    "ALTER TABLE public.device_security_events ENABLE ROW LEVEL SECURITY",
    "ALTER TABLE public.device_integrity_checks ENABLE ROW LEVEL SECURITY",
    "REVOKE ALL PRIVILEGES ON TABLE",
    "FROM PUBLIC, anon, authenticated, service_role",
    "TO service_role",
    "challenge_hash ~ '^[0-9a-f]{64}$'",
    "auth_session_id uuid NOT NULL UNIQUE",
    "ON DELETE RESTRICT",
    "GRANT USAGE, SELECT ON SEQUENCE",
    "device_security_events_id_seq",
    "device_integrity_checks_id_seq",
):
    assert required.lower() in device_security.lower(), (
        f"Missing device-security registry contract: {required}"
    )
assert "GRANT SELECT, INSERT, UPDATE ON TABLE public.user_devices TO authenticated" not in device_security
assert "GRANT SELECT ON TABLE public.user_devices TO authenticated" not in device_security
assert "CREATE POLICY" not in device_security.upper(), (
    "Device registry must remain backend-only until verified server endpoints are implemented"
)
assert "DELETE FROM" not in device_security.upper()
assert "DROP TABLE" not in device_security.upper()
assert "private_key" not in device_security.lower()
assert "access_token" not in device_security.lower()
assert "refresh_token" not in device_security.lower()
print("PASS: device registry is RLS-enabled, backend-only, challenge-hash based, and contains no stored secrets.")

core_privileges = (ROOT / "tools/test_core_table_privileges.sql").read_text()
for required in (
    "has_column_privilege('authenticated', table_oid, 'duty_status', 'UPDATE')",
    "has_column_privilege('authenticated', table_oid, 'role', 'UPDATE')",
    "has_column_privilege('authenticated', table_oid, 'status', 'UPDATE')",
    "has_column_privilege('authenticated', table_oid, 'requester_id', 'UPDATE')",
    "authenticated must use authorized RPCs instead of direct incident updates",
    "authenticated must not directly INSERT into public.%",
):
    assert required in core_privileges, f"Missing exact core-table privilege assertion: {required}"
assert "PostgreSQL has_table_privilege(..., 'UPDATE') intentionally returns false" in core_privileges
core_migration = (MIGRATIONS / "20261118100000_restrict_core_table_destructive_privileges.sql").read_text()
assert "incident creation, updates, and assignment use authorization-checked RPCs" in core_migration
print("PASS: core privilege runtime contract checks narrow updates and RPC-only client creation.")

device_bounds = (MIGRATIONS / "20261119100000_bound_device_registry_metadata.sql").read_text()
for required in (
    "user_devices_device_model_length_check",
    "user_devices_app_version_length_check",
    "user_devices_public_key_length_check",
    "device_sessions_expiry_check",
    "device_sessions_revocation_consistency_check",
    "device_challenges_max_lifetime_check",
    "interval '10 minutes'",
    "device_security_events_details_size_check",
    "octet_length(details::text) <= 8192",
    "device_integrity_checks_summary_size_check",
    "octet_length(result_summary::text) <= 4096",
):
    assert required.lower() in device_bounds.lower(), (
        f"Missing device-registry resource/expiry bound: {required}"
    )
assert "GRANT" not in device_bounds.upper()
assert "DROP " not in device_bounds.upper()
print("PASS: device registry metadata and event payloads are bounded; challenges expire within ten minutes.")


# Public dependency probe is intentionally data-free and cannot mutate state.
dependency_health = (MIGRATIONS / "20261119110000_public_data_free_dependency_health_rpc.sql").read_text()
for required in (
    "CREATE OR REPLACE FUNCTION public.api_dependency_health()",
    "RETURNS text",
    "LANGUAGE sql",
    "IMMUTABLE",
    "PARALLEL SAFE",
    "SECURITY INVOKER",
    "SET search_path = pg_catalog",
    "SELECT 'tacresq_database_ok'::text",
    "REVOKE ALL PRIVILEGES",
    "FROM PUBLIC, anon, authenticated, service_role",
    "GRANT EXECUTE",
    "TO anon, authenticated",
):
    assert required.lower() in dependency_health.lower(), (
        f"Missing least-privilege database health RPC contract: {required}"
    )
assert "SECURITY DEFINER" not in dependency_health.upper()
assert "FROM public." not in dependency_health.lower()
assert "INSERT " not in dependency_health.upper()
assert "UPDATE " not in dependency_health.upper()
assert "DELETE " not in dependency_health.upper()
assert "TRUNCATE " not in dependency_health.upper()
assert "GRANT EXECUTE ON FUNCTION public.api_dependency_health() TO service_role" not in dependency_health
print("PASS: dependency health RPC is a fixed-result SECURITY INVOKER with no table access or mutation.")
